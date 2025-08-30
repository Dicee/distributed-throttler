package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.util.ValkeyTestBase
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class SlidingWindowCounterRateLimiterManualTest : ValkeyTestBase() {
    // just a quick manual test, real unit tests coming soon
    @Test
    fun test() {
        val desiredRate = 1
        val unit = TimeUnit.SECONDS
        val rateLimiter = SlidingWindowCounterRateLimiter(desiredRate, unit, glideClient)

        val context = RequestContext("courtino", "test")
        var throttledCount = 0
        var grantedCount = 0

        val start = Instant.now()
        (1..1000).forEach { _ ->
            val granted = rateLimiter.grant(requestedCapacity = 1, context).granted

            val elapsed = Duration.between(start, Instant.now())
            if (!granted) println("[${elapsed.toSeconds()}s] throttled: ${++throttledCount}")
            else println("[${elapsed.toSeconds()}s] granted: ${++grantedCount}")

            Thread.sleep(Random.nextLong(1000))
        }
    }
}