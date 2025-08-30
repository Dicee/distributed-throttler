package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.valkey.ValkeyTime
import com.dici.distributedThrottler.lambda.valkey.lua.LuaScripts
import glide.api.GlideClient
import glide.api.models.commands.ScriptOptions
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

private const val NAMESPACE = "leaky-bucket"

// According to several sources, on some systems Thread.sleep cannot actually guarantee better precision than 10 to 15 milliseconds.
// However, it also seems like some systems support sub-millisecond sleep times (see https://bugs.openjdk.org/browse/JDK-8306463).
// To offer a decent experience in all worlds, we limit the leak frequency to 10 ms so that when a sleep is required (to come back
// and fetch a leaked token on the next leak occurrence), the system is likely to be able to wait for this amount of time accurately
private const val MINIMUM_LEAK_FREQ_MS = 10L

/**
 * This class implements the leaky bucket algorithm, where a fixed amount of tokens is released at a regular time interval. For slow call rates
 * e.g. 5 calls per minute, the number of tokens released every time can easily be one, allowing a perfectly regular call rate. However for higher
 * call rates starting from 1000 TPS, releasing only 1 token at a time would mean that we have to release a token every millisecond. Since our
 * implementation relies on sleeping through the next leak occurrence if no token is available at the moment, this would require us to wait sub-millisecond
 * durations, which some systems cannot do, and the problem gets worse with even higher TPS. As a side-note, observe that we are waiting on the throttler
 * side, not in the Redis server inside a Lua script, as this would entirely block the event loop and all incoming requests during the sleep.
 *
 * To address this sleep time resolution problem, we do two things: 1) use a minimum leak period of 10ms 2) leak more than one token at a time when the TPS
 * starts exceeding 100TPS (not 1000, since we use 10ms resolution). This solution means that for higher TPS we will allow the client to make slightly less
 * regular traffic since they will be allowed to burst by 10ms intervals as opposed to evenly spacing their calls over the entire timeline. This is a fine
 * compromise though, as the average TPS smoothed over a minute should still look pretty regular.
 *
 * Now let's discuss the algorithm in details:
 * - when a request comes in, we call a Lua script on Redis
 * - the script first checks when the last leak occurred (if any) and the number of available tokens that have already been leaked and not used yet
 * - if the time passed since the last leak exceeds the leak period, we reset the amount of available tokens to the leak amount. This is because we
 *   do not want to allow the client to accumulate tokens and be able to exceed their quota. If they didn't use all the leak tokens during a leak period,
 *   these tokens are lost.
 * - if the number of available tokens is sufficient to grant the request, we allocate the tokens and return a wait time of 0 microseconds.
 *   The throttler will immediately return with GRANTED.
 * - otherwise, the Lua script returns the time to wait in microseconds until the next leak occurrence. The throttler will make a best-effort at waiting for
 *   this amount (with some jitter to prevent multiple hosts hammering Redis simultaneously), and then will retry obtaining a token. If the Lua script returns
 *   a wait time again, the throttler immediately returns DENIED, otherwise it returns GRANTED.
 */
class LeakyBucketRateLimiter(
    desiredRate: Int, // number of calls to grant within one unit of time
    unit: TimeUnit,
    private val glideClient: GlideClient,
    private val valkeyTime: ValkeyTime = ValkeyTime.serverSide(),
) : RateLimiter {
    init {
        unit.validateAtMostAsGranularAs(TimeUnit.MILLISECONDS)
    }

    val leakProperties = determineLeakProperties(desiredRate, unit)

    override fun grant(requestedCapacity: Int, context: RequestContext): RateLimiterResult {
        if (requestedCapacity > leakProperties.amount) {
            throw IllegalArgumentException("Cannot request more capacity in one go than the number of " +
                    "tokens leaked in each leak period (${leakProperties.amount}), but requested capacity was: $requestedCapacity")
        }

        val waitTimeMicros = getWaitTimeMicros(requestedCapacity, context)
        if (waitTimeMicros == 0L) return RateLimiterResult.GRANTED

        try {
            val jitterMs = Random.nextLong(5)
            val waitTimeMs = waitTimeMicros / 1000L
            val extraWaitNanos = ((waitTimeMicros - waitTimeMs * 1000) * 1000).toInt()
            // though the system may not be able to honor such precision, we use the most precise sleep signature
            // TODO: see about using coroutines instead of keeping the thread busy
            Thread.sleep(waitTimeMs + jitterMs, extraWaitNanos)
        } catch (_: InterruptedException) {
            return RateLimiterResult.DENIED
        }

        // we allow only one retry and deny the call if the retry is unsuccessful at obtaining a token
        val retryWaitTimeMicros = getWaitTimeMicros(requestedCapacity, context)
        return if (retryWaitTimeMicros == 0L) RateLimiterResult.GRANTED else RateLimiterResult.DENIED
    }

    private fun getWaitTimeMicros(requestedCapacity: Int, context: RequestContext) = glideClient.invokeScript(
        LuaScripts.GET_LEAKY_BUCKET_WAIT_TIME, ScriptOptions.builder()
            .key(context.toFineGrainKey(NAMESPACE))
            .args(
                listOf(
                    leakProperties.frequencyMicros.toString(),
                    leakProperties.amount.toString(),
                    requestedCapacity.toString(),
                    valkeyTime.currentNanos().toString(),
                )
            )
            .build()
    ).thenApply { it as Long }.get()
}

private fun determineLeakProperties(desiredRate: Int, unit: TimeUnit): LeakProperties {
    val desiredFreqMs = unit.toMillis(1L) / desiredRate.toDouble()
    return if (desiredFreqMs < MINIMUM_LEAK_FREQ_MS) {
        // We round the leaked amount to 3 decimal places to avoid sending huge strings over the network (as Glide seems to want all parameters
        // as a string). Taking 3 decimal places allows accumulating an error of less than 1 token per 10 seconds if we are at the minimum 10ms
        // leak frequency, which is the worst case in terms of accumulation of error.
        val amount = (MINIMUM_LEAK_FREQ_MS / desiredFreqMs).roundTo(4)
        LeakProperties(MINIMUM_LEAK_FREQ_MS * 1000, amount)
    } else {
        LeakProperties((desiredFreqMs * 1000).roundToLong(), 1.0)
    }
}

data class LeakProperties(val frequencyMicros: Long, val amount: Double)

fun Double.roundTo(decimals: Int): Double {
    val multiplier = 10.0.pow(decimals.toDouble())
    return (this * multiplier).roundToInt() / multiplier
}