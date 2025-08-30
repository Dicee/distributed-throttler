package com.dici.distributedThrottler.lambda.valkey.lua

import glide.api.models.GlideString
import glide.api.models.Script
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

object LuaScripts {
    val UPDATE_TOKEN_BUCKET = Script(loadResource("update_token_bucket.lua"), false)
    val GET_LEAKY_BUCKET_WAIT_TIME = Script(loadResource("get_leaky_bucket_wait_time.lua"), false)
    val UPDATE_SLIDING_WINDOW_COUNTER = Script(loadResource("update_sliding_window_counter.lua"), false)

    private fun loadResource(path: String): GlideString {
        val inputStream = LuaScripts.javaClass.getResourceAsStream(path) ?: throw FileNotFoundException("Missing resource <$path>")
        return GlideString.of(inputStream.bufferedReader(charset = StandardCharsets.UTF_8).use { it.readText() })
    }
}