package com.dici.distributedThrottler.lambda.util

import com.dici.distributedThrottler.lambda.algorithms.RateLimiter
import com.dici.distributedThrottler.lambda.algorithms.RateLimiterResult
import com.dici.distributedThrottler.lambda.config.ThrottlingScope
import com.dici.distributedThrottler.lambda.valkey.FakeTicker
import com.dici.distributedThrottler.lambda.valkey.GlideAdapter
import com.dici.distributedThrottler.lambda.valkey.ValkeyTime
import com.dici.distributedThrottler.lambda.valkey.newLocalGlideClient
import glide.api.GlideClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.spy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

val OTHER_SCOPE = ThrottlingScope("other", "key")

/**
 * This class relies on the developer or the machine running the test to have a pre-existing Redis or Valky client running locally.
 * It would be cleaner to have an embedded Redis, with code managing the lifecycle of the Redis instance, however this is more than
 * good enough for testing a mini-project for fun and self-education purposes.
 */
abstract class ValkeyTestBase {
    protected val scope = ThrottlingScope("dummy", "test")
    protected lateinit var glideAdapter: GlideAdapter
    protected lateinit var glideClient: GlideClient
    protected lateinit var ticker: FakeTicker

    protected val valkeyTime: ValkeyTime
        get() = ValkeyTime.forTesting(ticker)

    @BeforeEach
    fun beforeEach() {
        glideClient = spy(newLocalGlideClient())
        glideAdapter = GlideAdapter(glideClient)
        glideClient.flushall().get()

        ticker = FakeTicker()
    }

    protected fun baseMultiThreadedTest(
        duration: Duration,
        totalRequests: Int,
        maxRequestedCapacity: Int,
        minRequestedCapacity: Int = 1,
        maxSleepMs: Long = 50
    ) {
        val numThreads = 5
        val executor = Executors.newFixedThreadPool(numThreads)
        val grantedCalls = AtomicInteger(0)
        val countDownLatch = CountDownLatch(1)

        val rateLimiter = newRealTimeRateLimiter(totalRequests / duration.seconds.toInt())
        try {
            for (i in 0 until numThreads) {
                executor.submit {
                    countDownLatch.await()

                    try {
                        val endTime = System.currentTimeMillis() + duration.toMillis()

                        while (System.currentTimeMillis() < endTime) {
                            val capacity = minRequestedCapacity + Random.nextInt(maxRequestedCapacity - minRequestedCapacity + 1)
                            val grant = rateLimiter.grant(capacity, scope)

                            if (grant == RateLimiterResult.GRANTED) grantedCalls.addAndGet(capacity)
                            if (maxSleepMs > 0) Thread.sleep(Random.nextLong(maxSleepMs))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } finally {
            countDownLatch.countDown()
            executor.shutdown()
            executor.awaitTermination(duration.seconds + 5, SECONDS)
        }

        val granted = grantedCalls.get()
        val lowerBound = totalRequests * 0.85
        val upperBound = totalRequests * 1.15
        assertThat(granted).isBetween(lowerBound.toInt(), upperBound.toInt())
    }

    protected abstract fun newRealTimeRateLimiter(tpsThreshold: Int = 10): RateLimiter
}