package com.dici.distributedThrottler.lambda.valkey

import glide.api.GlideClient
import glide.api.models.configuration.GlideClientConfiguration
import glide.api.models.configuration.NodeAddress

fun newLocalClient(): GlideClient {
    val config = GlideClientConfiguration.builder()
        .address(NodeAddress.builder()
            .host("localhost")
            .port(6379)
            .build()
        )
        .build()

    return GlideClient.createClient(config).get()
}