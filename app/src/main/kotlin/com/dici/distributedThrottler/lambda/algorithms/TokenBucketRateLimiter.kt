package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.valkey.lua.LuaScripts
import glide.api.GlideClient
import glide.api.models.commands.ScriptOptions
import java.util.concurrent.TimeUnit

private const val NAMESPACE = "token-bucket"

class TokenBucketRateLimiter(
    rateThreshold: Int,
    private val burstRateThreshold: Int,
    unit: TimeUnit,
    private val glideClient: GlideClient,
) : RateLimiter {
    private val refillRate = rateThreshold / unit.toMillis(1).toDouble()

    override fun grant(requestedCapacity: Int, context: RequestContext): RateLimiterResult {
        val granted = glideClient.invokeScript(
            LuaScripts.UPDATE_TOKEN_BUCKET, ScriptOptions.builder()
                .key(context.toFineGrainKey(NAMESPACE))
                .args(listOf(
                    requestedCapacity.toString(),
                    refillRate.toString(),
                    burstRateThreshold.toString(),
                ))
                .build()
        ).thenApply { (it as Long) == 1L }

        return RateLimiterResult(granted.get())
    }
}