package com.dici.distributedThrottler.lambda.valkey

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS

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
        // note that the nano field contains the milliseconds of the last second
        _nanos = NANOSECONDS.convert(instant.toEpochMilli() / 1000, SECONDS) + instant.nano
    }

    fun asClock(): Clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(_nanos / 1000 / 1000)
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock { throw UnsupportedOperationException() }
    }
}
