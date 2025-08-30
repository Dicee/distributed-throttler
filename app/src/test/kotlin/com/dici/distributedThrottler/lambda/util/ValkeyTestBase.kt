package com.dici.distributedThrottler.lambda.util

import com.dici.distributedThrottler.lambda.algorithms.RequestContext
import com.dici.distributedThrottler.lambda.valkey.newLocalClient
import glide.api.GlideClient
import org.junit.jupiter.api.BeforeEach

val OTHER_CONTEXT = RequestContext("other", "key")

/**
 * This class relies on the developer or the machine running the test to have a pre-existing Redis or Valky client running locally.
 * It would be cleaner to have an embedded Redis, with code managing the lifecycle of the Redis instance, however this is more than
 * good enough for testing a mini-project for fun and self-education purposes.
 */
open class ValkeyTestBase {
    protected val context = RequestContext("dummy", "test")
    protected lateinit var glideClient: GlideClient

    @BeforeEach
    fun beforeEach() {
        glideClient = newLocalClient()
        glideClient.flushall().get()
    }
}