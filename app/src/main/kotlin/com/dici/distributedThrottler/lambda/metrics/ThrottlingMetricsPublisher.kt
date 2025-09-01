package com.dici.distributedThrottler.lambda.metrics

import com.dici.distributedThrottler.lambda.algorithms.RateLimiterResult
import com.dici.distributedThrottler.lambda.algorithms.RateLimiterResult.GRANTED
import com.dici.distributedThrottler.lambda.config.ThrottlingAlgorithm
import com.dici.distributedThrottler.lambda.config.ThrottlingScope
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger
import software.amazon.cloudwatchlogs.emf.model.DimensionSet

private val defaultLogger: MetricsLogger by lazy {
    val logger = MetricsLogger().setNamespace("DistributedThrottler")
    logger.isFlushPreserveDimensions = false
    logger
}

class ThrottlingMetricsPublisher(private val metricsLogger : MetricsLogger = defaultLogger) {
    fun publish(scope: ThrottlingScope, rateLimiterResult: RateLimiterResult, algorithm: ThrottlingAlgorithm) {
        metricsLogger
            .putDimensions(DimensionSet.of(
                "Algorithm", algorithm.name,
                "Identity", scope.identity,
                "Operation", scope.operation,
            ))
            .putMetric("Granted", if (rateLimiterResult == GRANTED) 1.0 else 0.0)
            .flush()
    }
}