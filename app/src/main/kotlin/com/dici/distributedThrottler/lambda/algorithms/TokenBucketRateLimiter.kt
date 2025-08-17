package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.valkey.lua.LuaScripts
import glide.api.GlideClient
import glide.api.models.commands.ScriptOptions.builder
import java.time.Clock
import java.util.concurrent.TimeUnit

private const val NAMESPACE = "token-bucket"

class TokenBucketRateLimiter(
    rateThreshold: Int,
    private val burstRateThreshold: Int,
    private val unit: TimeUnit,
    private val glideClient: GlideClient,
    private val clock: Clock
) : RateLimiter {
    private val refillRate = rateThreshold / unit.toMillis(1).toDouble()

    override fun grant(requestedCapacity: Int, context: RequestContext): RateLimiterResult {
        val granted = glideClient.invokeScript(
            LuaScripts.UPDATE_TOKEN_BUCKET, builder()
                .key(context.toFineGrainKey(NAMESPACE))
                .args(mutableListOf(
                    requestedCapacity.toString(),
                    refillRate.toString(),
                    clock.instant().toEpochMilli().toString(),
                    burstRateThreshold.toString(),
                ))
                .build()
        ).thenApply { (it as Long) == 1L }

        return RateLimiterResult(granted.get())
    }
}