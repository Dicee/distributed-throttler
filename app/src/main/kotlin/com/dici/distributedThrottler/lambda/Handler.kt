package com.dici.distributedThrottler.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger
import software.amazon.cloudwatchlogs.emf.model.DimensionSet
import software.amazon.cloudwatchlogs.emf.model.StorageResolution
import software.amazon.cloudwatchlogs.emf.model.Unit


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