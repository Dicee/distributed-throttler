package com.dici.distributedThrottler.lambda.valkey.lua

import glide.api.models.GlideString
import glide.api.models.Script
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

object LuaScripts {
    val UPDATE_TOKEN_BUCKET = Script(loadResource("update_token_bucket.lua"), false)

    private fun loadResource(path: String): GlideString {
        val inputStream = LuaScripts.javaClass.getResourceAsStream(path) ?: throw FileNotFoundException("Missing resource <$path>")
        return GlideString.of(inputStream.bufferedReader(charset = StandardCharsets.UTF_8).use { it.readText() })
    }
}