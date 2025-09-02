package com.dici.distributedThrottler.lambda.valkey

import com.dici.distributedThrottler.lambda.valkey.auth.iam.IamAuthTokenGenerator
import com.dici.distributedThrottler.lambda.valkey.auth.iam.TOKEN_EXPIRY_DURATION
import glide.api.GlideClient
import glide.api.models.configuration.GlideClientConfiguration
import glide.api.models.configuration.NodeAddress
import glide.api.models.configuration.ServerCredentials
import software.amazon.awssdk.regions.Region
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val DEFAULT_PORT = 6379

internal fun newLocalGlideClient(): GlideClient {
    val config = GlideClientConfiguration.builder()
        .address(NodeAddress.builder()
            .host("localhost")
            .port(DEFAULT_PORT)
            .build()
        )
        .build()

    return GlideClient.createClient(config).get()
}

class GlideAdapter internal constructor(private val glideClient: GlideClient, private val passwordRefresher: PasswordRefresher? = null) {
    companion object {
        fun newLocalGlideAdapter(): GlideAdapter {
            return GlideAdapter(newLocalGlideClient())
        }

        // https://github.com/valkey-io/valkey-glide/wiki/General-Concepts#using-iam-authentication-with-glide-for-elasticache-and-memorydb
        fun newIamAuthenticatedGlideAdapter(
            userName: String,
            cacheName: String,
            cacheEndpoint: String,
            cachePort: Int,
            region: Region
        ): GlideAdapter {
            val passwordRefresher = IamPasswordRefresher(IamAuthTokenGenerator(userName, cacheName, region))
            val config = GlideClientConfiguration.builder()
                .address(NodeAddress.builder()
                    .host(cacheEndpoint)
                    .port(cachePort)
                    .build()
                )
                .credentials(ServerCredentials.builder()
                    .username(userName)
                    .password(passwordRefresher.refresh())
                    .build()
                )
                .useTLS(true) // required for ElastiCache serverless
                .build()

            return GlideAdapter(GlideClient.createClient(config).get(), passwordRefresher)
        }
    }

    val client get() = {
        // because we're running on Lambda, we cannot rely on any async execution to complete in time, so we'll do it synchronously
        if (passwordRefresher != null && passwordRefresher.needsRefresh()) {
            // We do not need to immediately re-auth because the connection will be valid for 12 hours while the Lambda instance will be terminated
            // in 1-2 hours at most. See https://github.com/valkey-io/valkey-glide/wiki/General-Concepts#using-iam-authentication-with-glide-for-elasticache-and-memorydb
            glideClient.updateConnectionPassword(passwordRefresher.refresh(), false)
        }
        glideClient
    }
}

interface PasswordRefresher {
    fun refresh(): String

    fun needsRefresh(): Boolean
}

class IamPasswordRefresher(
    private val authTokenGenerator: IamAuthTokenGenerator,
    private val clock: Clock = Clock.systemUTC(),
) : PasswordRefresher {
    private var lastRefresh: Instant
    private var currentPassword: String

    init {
        lastRefresh = clock.instant()
        currentPassword = authTokenGenerator.getAuthToken()
    }

    // we try to refresh before it expires to never experience authentication issues
    override fun needsRefresh() =
        Duration.between(lastRefresh, clock.instant()) >= TOKEN_EXPIRY_DURATION.multipliedBy(2).dividedBy(3)

    override fun refresh(): String {
        if (needsRefresh()) {
            lastRefresh = clock.instant()
            currentPassword = authTokenGenerator.getAuthToken()
        }
        return currentPassword
    }
}