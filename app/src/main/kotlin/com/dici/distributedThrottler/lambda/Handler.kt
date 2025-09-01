package com.dici.distributedThrottler.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.dici.distributedThrottler.lambda.algorithms.RateLimiterResult
import com.dici.distributedThrottler.lambda.config.*
import com.dici.distributedThrottler.lambda.metrics.ThrottlingMetricsPublisher
import com.dici.distributedThrottler.lambda.valkey.newLocalClient
import glide.api.GlideClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

private val singletonThrottlingPolicyStore: ThrottlingPolicyStore by lazy {
    ThrottlingPolicyStore(mapOf(
        "HighTpsClient" to mapOf(
            "ListResources" to RateLimits(threshold = 25, unit = SECONDS),
            "AddResource" to RateLimits(threshold = 30, burstThreshold = 38, unit = SECONDS),
            GLOBAL to RateLimits(threshold = 40, burstThreshold = 60, unit = SECONDS),
        ),
        "LowTpsClient" to mapOf(
            "ListResources" to RateLimits(threshold = 20, unit = TimeUnit.MINUTES),
            "AddResource" to RateLimits(threshold = 8, unit = TimeUnit.MINUTES),
        ),
    ))
}

private val singletonGlideClient: GlideClient by lazy { newLocalClient() }
private val singleMetricsPublisher: ThrottlingMetricsPublisher by lazy { ThrottlingMetricsPublisher() }

class Handler(
    private val throttlingPolicyStore: ThrottlingPolicyStore,
    private val glideClient: GlideClient,
    private val metricsPublisher: ThrottlingMetricsPublisher,
) : RequestHandler<Request, RateLimiterResult> {
    // for Lambda
    @Suppress("unused")
    constructor() : this(singletonThrottlingPolicyStore, singletonGlideClient, singleMetricsPublisher)

    override fun handleRequest(request: Request, context: Context): RateLimiterResult {
        val algorithm = ThrottlingAlgorithm.valueOf(request.algorithm)

        val applicablePolicies = throttlingPolicyStore.getApplicablePolicies(request.identity, request.operation)
        val overallResult = RateLimiterResult.from(applicablePolicies.all { policy ->
            policy.newRateLimiter(algorithm, glideClient).grant(1, policy.scope) == RateLimiterResult.GRANTED
        })

        val fineGrainScope = ThrottlingScope(request.identity, request.operation)
        metricsPublisher.publish(fineGrainScope, overallResult, algorithm)

        return overallResult
    }
}

// mutable because Jackson uses the default constructor and a setter by default
data class Request(var identity: Identity, var operation: Operation, var algorithm: String) {
    @Suppress("unused") // required for deserialization
    constructor() : this("", "", "")
}