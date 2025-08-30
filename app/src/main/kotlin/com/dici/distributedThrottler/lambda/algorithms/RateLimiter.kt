package com.dici.distributedThrottler.lambda.algorithms

import java.util.concurrent.TimeUnit

interface RateLimiter {
    fun grant(requestedCapacity: Int, context: RequestContext): RateLimiterResult
}

data class RequestContext(val identity: String, val operation: String) {
    fun toFineGrainKey(namespace: String) = "$namespace:$identity:$operation"
}

data class RateLimiterResult(val granted: Boolean) {
    companion object {
        val GRANTED = RateLimiterResult(true)
        val DENIED = RateLimiterResult(false)
    }
}

internal fun TimeUnit.validateAtMostAsGranularAs(maximumGranularity: TimeUnit) {
    if (maximumGranularity.convert(1L, this) == 0L) {
        throw IllegalArgumentException("Please use $maximumGranularity resolution at most. Unit was: $this")
    }
}
