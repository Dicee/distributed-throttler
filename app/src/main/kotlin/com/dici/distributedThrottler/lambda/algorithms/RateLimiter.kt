package com.dici.distributedThrottler.lambda.algorithms

interface RateLimiter {
    fun grant(requestedCapacity: Int, context: RequestContext): RateLimiterResult
}

data class RequestContext(val identity: String, val operation: String) {
    fun toFineGrainKey(namespace: String) = "$namespace:$identity:$operation"
}

data class RateLimiterResult(val granted: Boolean)