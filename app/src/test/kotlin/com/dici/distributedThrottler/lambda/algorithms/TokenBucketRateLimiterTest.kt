package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.valkey.newLocalClient
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.TimeUnit

class TokenBucketRateLimiterTest {
    // just a quick manual test, real unit tests coming soon
    @Test
    fun test() {
        val glideClient = newLocalClient()

        var rateThreshold = 3
        val rateLimiter = TokenBucketRateLimiter(rateThreshold, rateThreshold, TimeUnit.SECONDS,glideClient)

        val context = RequestContext("courtino", "test")
        var throttledCount = 0
        var grantedCount = 0

        for (i in 1..1000) {
            val granted = rateLimiter.grant(requestedCapacity = 1, context).granted

            if (!granted) println("throttled: ${++throttledCount}")
            else println("granted: ${++grantedCount}")

//            Thread.sleep(Random.nextLong(100))
            Thread.sleep(250)
        }
    }
}