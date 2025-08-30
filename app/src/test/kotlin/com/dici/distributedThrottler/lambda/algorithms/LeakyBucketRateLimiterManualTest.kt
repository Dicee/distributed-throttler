package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.util.ValkeyTestBase
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class LeakyBucketRateLimiterManualTest : ValkeyTestBase() {
    // just a quick manual test, real unit tests coming soon
    @Test
    fun test() {
        val desiredRate = 30
        val unit = TimeUnit.MINUTES
        val rateLimiter = LeakyBucketRateLimiter(desiredRate, unit, glideClient)

        val context = RequestContext("courtino", "test")
        var throttledCount = 0
        var grantedCount = 0

        val start = Instant.now()
        (1..1000).forEach { _ ->
            val granted = rateLimiter.grant(requestedCapacity = 1, context).granted

            val elapsed = Duration.between(start, Instant.now())
            if (!granted) println("[${elapsed.toSeconds()}s] throttled: ${++throttledCount}")
            else println("[${elapsed.toSeconds()}s] granted: ${++grantedCount}")
        }
    }
}