package com.dici.distributedThrottler.lambda.valkey.auth.iam

import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.signer.Aws4Signer
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams
import software.amazon.awssdk.http.SdkHttpFullRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.regions.Region
import java.net.URI.create
import java.time.Duration
import java.time.Instant

val TOKEN_EXPIRY_DURATION = Duration.ofMinutes(15)

private val REQUEST_METHOD: SdkHttpMethod = SdkHttpMethod.GET
private const val REQUEST_PROTOCOL: String = "https://"

// Inspired from https://github.com/aws-samples/elasticache-iam-auth-demo-app/blob/main/src/main/java/com/amazon/elasticache/IAMAuthTokenRequest.java
// but adapted to AwsV4HttpSigner, which is now the recommendation. The same setup is also documented with a Python client:
// https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/LambdaRedis.html#LambdaRedis.step2
class IamAuthTokenGenerator(
    private val userId: String,
    private val cacheName: String,
    private val region: Region,
    private val credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.builder().build(),
) {
    fun getAuthToken(): String {
        val request: SdkHttpRequest = sign(getSignableRequest(), credentialsProvider.resolveCredentials())
        return request.uri.toString().replace(REQUEST_PROTOCOL, "");
    }

    private fun getSignableRequest(): SdkHttpFullRequest {
        return SdkHttpFullRequest.builder()
            .method(REQUEST_METHOD)
            .uri(create("${REQUEST_PROTOCOL}${cacheName}/"))
            .appendRawQueryParameter("Action", "connect")
            .appendRawQueryParameter("User", userId)
            .appendRawQueryParameter("ResourceType", "ServerlessCache")
            .build()
    }

    private fun sign(request: SdkHttpFullRequest, credentials: AwsCredentials): SdkHttpRequest {
//        return AwsV4HttpSigner.create().sign { it
//            .request(request)
//            .identity(credentials)
//            .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "elasticache")
//            .putProperty(AwsV4HttpSigner.REGION_NAME, region.id())
//            .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, TOKEN_EXPIRY_DURATION)
//        }.request()

        // this signer is deprecated but I ran into issues using the newer one above, so for now it will have to do
        val params = Aws4PresignerParams.builder()
            .signingRegion(region)
            .signingName("elasticache")
            .expirationTime(Instant.now().plus(TOKEN_EXPIRY_DURATION))
            .awsCredentials(credentials)
            .build()

        return Aws4Signer.create().presign(request, params)
    }
}