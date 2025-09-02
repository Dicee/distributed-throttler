package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.config.ThrottlingScope
import com.dici.distributedThrottler.lambda.valkey.GlideAdapter
import com.dici.distributedThrottler.lambda.valkey.ValkeyTime
import com.dici.distributedThrottler.lambda.valkey.hashSlotKey
import com.dici.distributedThrottler.lambda.valkey.lua.LuaScripts
import glide.api.models.commands.ScriptOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max

// We'll use 100 points at most pey key, which is good precision and still reasonable memory usage compared to storing all calls (starting from 100 calls per period).
// We voluntarily use a divisor of 1000 to have clean intervals starting at integer millisecond values.
const val BUCKET_COUNT = 100

 // If we take a window of 1s, our buckets will be 10 ms. Any latency in the throttler significantly greater than 10 ms will therefore mess up the calculations.
 // To address this, we'll take the average TPS on 5 seconds (buckets of 40 ms). This does mean we allow a burst 5 times greater than the call rate threshold,
 // but it may not actually be a big issue as giving some leeway to clients on short periods is desirable to prevent over-throttling.
 //
 // Note that if in a production system we found that we could not run the throttler logic under 40s most of the time (e.g. p99), we could increase the window
 // duration. We would then hit an even bigger burst problem, which could be addressed by applying a linear and then exponential ramp down. The ramp down could
 // work as follows:
 // - check the sum over the last 10 points (at least 10% of the window's total duration)
 // - if more than 80% of the window's budget has been consumed in less than 20% of the window's duration, kick in the exponential ramp down
 // - if more than 50% of the window's budget has been consumed in less than 20% of the window's duration, kick in the linear ramp down
 //
 // The ramp down consists would be calculated as follows:
 // - calculate the amount of time between the first of the 10 selected points and the end of the window starting at that point. Call that dt.
 // - for a linear ramp down, draw a random number between 0 and 1 and compare it to dt/W where W is the window's duration. If the random number is smaller,
 //   allow the call, otherwise deny it. This means that the fastest has the burst been in the last 10 points, the least chance we have to allow the call.
 // - for an exponential ramp down, we'll do the same thing but with a threshold of exp(-10 * (1 - dt/W)), which will cause the probability to decrease even faster.
 //
 // This is just a mini project so let's keep things simple and not use this ramp down logic if we don't know whether it would actually be needed or not.
 // Final note: keep this constant a multiple of 1000 or change the calculation of <code>windowThreshold</code> to use floating-point numbers.
private const val MINIMUM_WINDOW_DURATION_MS = 5000

private const val NAMESPACE = "sliding-window"

class SlidingWindowCounterRateLimiter(
    callRateThreshold: Int,
    unit: TimeUnit,
    private val glideAdapter: GlideAdapter,
    private val valkeyTime: ValkeyTime = ValkeyTime.serverSide(),
) : RateLimiter {
    private val windowDurationMs: Int
    private val bucketDurationMs: Int
    private val windowThreshold: Int

    init {
        // Seems a bit ridiculous to have a window on a period smaller than one millisecond, especially as we split the space in 250 buckets. Seconds is
        // a good enough granularity, and the most typical use case for a throttler.
        unit.validateAtMostAsGranularAs(TimeUnit.SECONDS)

        val unitToMillis = unit.toMillis(1L).toInt()
        windowDurationMs = max(MINIMUM_WINDOW_DURATION_MS, unitToMillis)
        windowThreshold = windowDurationMs / unitToMillis * callRateThreshold
        bucketDurationMs = windowDurationMs / BUCKET_COUNT
    }

    override fun grant(requestedCapacity: Int, scope: ThrottlingScope): RateLimiterResult {
        val hashSlot = scope.toThrottlingKey(NAMESPACE)
        val granted = glideAdapter.client().invokeScript(LuaScripts.UPDATE_SLIDING_WINDOW_COUNTER, ScriptOptions.builder()
            .keys(listOf(
                hashSlotKey(hashSlot, "timestamps"),
                hashSlotKey(hashSlot, "counts"),
            ))
            .args(listOf(
                requestedCapacity.toString(),
                windowDurationMs.toString(),
                windowThreshold.toString(),
                bucketDurationMs.toString(),
                valkeyTime.currentNanos().toString(),
            ))
            .build()
        ).thenApply { (it as Long) == 1L }

        return RateLimiterResult.from(granted.get())
    }
}