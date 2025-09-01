package com.dici.distributedThrottler.lambda.algorithms

import java.util.concurrent.TimeUnit

interface RateLimiter {
    fun grant(requestedCapacity: Int, scope: ThrottlingScope): RateLimiterResult
}

data class ThrottlingScope(val identity: String, val operation: String) {
    fun toThrottlingKey(namespace: String) = "$namespace:$identity:$operation"

    companion object {
        fun global(identity: String) = ThrottlingScope(identity, "*")
    }
}

enum class RateLimiterResult {
    GRANTED,
    DENIED;

    companion object {
        fun from(granted: Boolean) = if (granted) GRANTED else DENIED
    }
}

internal fun TimeUnit.validateAtMostAsGranularAs(maximumGranularity: TimeUnit) {
    if (maximumGranularity.convert(1L, this) == 0L) {
        throw IllegalArgumentException("Please use $maximumGranularity resolution at most. Unit was: $this")
    }
}
