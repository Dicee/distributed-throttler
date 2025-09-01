package com.dici.distributedThrottler.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.dici.distributedThrottler.lambda.config.GLOBAL
import com.dici.distributedThrottler.lambda.config.RateLimits
import com.dici.distributedThrottler.lambda.config.ThrottlingPolicyStore
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger
import software.amazon.cloudwatchlogs.emf.model.DimensionSet
import software.amazon.cloudwatchlogs.emf.model.StorageResolution
import software.amazon.cloudwatchlogs.emf.model.Unit
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

val throttlingPolicyStore = ThrottlingPolicyStore(mapOf(
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

class Handler : RequestHandler<User, String> {
    override fun handleRequest(user: User, context: Context): String {
        log.info("Hello ${user.name}")
        println("Hello ${user.name} with a println")

        val metrics = MetricsLogger()
        metrics.setNamespace("courtino-test")
        metrics.putDimensions(DimensionSet.of("Service", "Throttler"))
        metrics.putMetric("ProcessingLatency", 100.0, Unit.MILLISECONDS, StorageResolution.STANDARD)
        metrics.putMetric("Memory.HeapUsed", 1600424.0, Unit.BYTES, StorageResolution.HIGH)
        metrics.flush()

        return ""
    }
}

// mutable because Jackson uses the default constructor and a setter by default
data class User(var name: String) {
    @Suppress("unused") // required for deserialization
    constructor() : this("")
}