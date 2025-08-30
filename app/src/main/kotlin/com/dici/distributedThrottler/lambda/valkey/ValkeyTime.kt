package com.dici.distributedThrottler.lambda.valkey

import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit.MICROSECONDS
import java.util.concurrent.TimeUnit.MILLISECONDS

private const val SERVER_SIDE_TIME = -1L

/**
 * This interface allows injected a mocked time function in Lua scripts to be able to more accurately test time-based logic which relies on server-side time.
 */
fun interface ValkeyTime {
    /**
     * @return the current UTC time in nanoseconds, or SERVER_SIDE_TIME.
     */
    fun currentNanos(): Long

    companion object {
        /**
         * Will use the TIME command from Redis to determine the current time. <b>Must</b> be used in production usage.
         */
        fun serverSide() = ValkeyTime { SERVER_SIDE_TIME }

        fun forTesting(ticker: FakeTicker) = ValkeyTime { ticker.nanos }
    }
}

class FakeTicker(instant: Instant = Instant.EPOCH) {
    private var _nanos: Long = 0

    init { setAt(instant) }

    val nanos: Long
        get() = _nanos

    fun advanceBy(duration: Duration) {
        _nanos += duration.toNanos()
    }

    fun setAt(instant: Instant) {
        _nanos = MICROSECONDS.convert(instant.toEpochMilli(), MILLISECONDS) + instant.nano
    }
}
