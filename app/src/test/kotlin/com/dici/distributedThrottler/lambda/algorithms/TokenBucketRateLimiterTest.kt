package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.util.OTHER_SCOPE
import com.dici.distributedThrottler.lambda.util.ValkeyTestBase
import com.dici.distributedThrottler.lambda.valkey.FakeTicker
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MICROSECONDS
import java.util.concurrent.TimeUnit.NANOSECONDS
import kotlin.math.roundToInt

private const val THRESHOLD = 3
private const val BURST_THRESHOLD = 5
private val TOKEN_GENERATION_FREQ = Duration.ofMillis(333) // we accept a base TPS of 3, so we'll add a token every 333 ms

private const val DEFAULT_KEY = "token-bucket:dummy:test"
private const val OTHER_KEY = "token-bucket:other:key"

class TokenBucketRateLimiterTest : ValkeyTestBase() {
    private lateinit var rateLimiter: TokenBucketRateLimiter

    @BeforeEach
    fun setUp() {
        ticker = FakeTicker()
        rateLimiter = TokenBucketRateLimiter(THRESHOLD, BURST_THRESHOLD, TimeUnit.SECONDS, glideClient, valkeyTime)
    }

    @ParameterizedTest
    @EnumSource(value = TimeUnit::class)
    fun testConstructor_rejectsTimeUnitUnderMilliseconds(unit: TimeUnit) {
        when (unit) {
            NANOSECONDS, MICROSECONDS ->
                assertThatThrownBy { TokenBucketRateLimiter(THRESHOLD, 1, unit, glideClient) }
                    .isExactlyInstanceOf(IllegalArgumentException::class.java)
                    .hasMessage("Please use MILLISECONDS resolution at most. Unit was: $unit")
            else ->
                assertThatCode { TokenBucketRateLimiter(THRESHOLD, 1, unit, glideClient) }
                    .doesNotThrowAnyException()
        }
    }

    @Test
    fun testGrant_newKey() {
        val requested = 4

        assertThatRemainingIsNullFor(DEFAULT_KEY)
        assertThat(rateLimiter.grant(requested, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatRemainingIs(DEFAULT_KEY, BURST_THRESHOLD - requested)
    }

    @Test
    fun testGrant_successiveGrants() {
        val (r1, r2) = 2 to 1

        assertThat(rateLimiter.grant(r1, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThat(rateLimiter.grant(r2, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatRemainingIs(DEFAULT_KEY, BURST_THRESHOLD - r1 - r2)
    }

    @Test
    fun testGrant_consumeAllTokensAndThenDenied() {
        assertThat(rateLimiter.grant(BURST_THRESHOLD, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatRemainingIs(DEFAULT_KEY, 0)

        assertThat(rateLimiter.grant(1, scope)).isEqualTo(RateLimiterResult.DENIED)
        assertThatRemainingIs(DEFAULT_KEY, 0)
    }

    @Test
    fun testGrant_deniedButLaterGranted_singleTokenRequested() {
        assertThat(rateLimiter.grant(BURST_THRESHOLD, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThat(rateLimiter.grant(1, scope)).isEqualTo(RateLimiterResult.DENIED)

        ticker.advanceBy(Duration.ofMillis(333)) // we accept a base TPS of 3, so we'll add a token every 333 ms
        assertThat(rateLimiter.grant(1, scope)).isEqualTo(RateLimiterResult.DENIED)

        ticker.advanceBy(Duration.ofMillis(1))
        assertThat(rateLimiter.grant(1, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatRemainingIs(DEFAULT_KEY, 0)
    }

    @Test
    fun testGrant_deniedButLaterGranted_multipleTokensRequested() {
        assertThat(rateLimiter.grant(BURST_THRESHOLD, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThat(rateLimiter.grant(1, scope)).isEqualTo(RateLimiterResult.DENIED)

        ticker.advanceBy(TOKEN_GENERATION_FREQ)
        assertThat(rateLimiter.grant(2, scope)).isEqualTo(RateLimiterResult.DENIED) // a single token is not enough

        ticker.advanceBy(Duration.ofMillis(1))
        assertThat(rateLimiter.grant(1, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatRemainingIs(DEFAULT_KEY, 0)
    }

    @Test
    fun testGrant_remainingCountRemainsCappedByBurstThreshold() {
        assertThatRemainingIsNullFor(DEFAULT_KEY)

        val requested = 3
        assertThat(rateLimiter.grant(requested, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatRemainingIs(DEFAULT_KEY, BURST_THRESHOLD - requested)

        ticker.advanceBy(Duration.ofHours(5))

        // proves that even when we wait for a very long time, we fill back to at most the burst threshold
        assertThat(rateLimiter.grant(BURST_THRESHOLD, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatRemainingIs(DEFAULT_KEY, 0)
    }

    @Test
    fun testGrant_keysAreIndependent() {
        assertThatRemainingIsNullFor(DEFAULT_KEY)
        assertThatRemainingIsNullFor(OTHER_KEY)

        val (r1, r2) = (3 to 1)
        assertThat(rateLimiter.grant(r1, scope)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatRemainingIs(DEFAULT_KEY, BURST_THRESHOLD - r1)

        assertThat(rateLimiter.grant(r2, OTHER_SCOPE)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatRemainingIs(OTHER_KEY, BURST_THRESHOLD - r2)

        assertThat(rateLimiter.grant(3, scope)).isEqualTo(RateLimiterResult.DENIED)
        assertThat(rateLimiter.grant(3, OTHER_SCOPE)).isEqualTo(RateLimiterResult.GRANTED)
    }

    @Test
    fun testGrant_multiThreaded() {
        baseMultiThreadedTest(Duration.ofSeconds(5), totalRequests = 300, maxRequestedCapacity = 5)
    }

    private fun assertThatRemainingIsNullFor(key: String) {
        assertThat(getRemainingTokens(key)).isNull()
    }

    private fun assertThatRemainingIs(key: String, expected: Int) {
        assertThat(getRemainingTokens(key)!!.toDouble().roundToInt()).isEqualTo(expected)
    }

    private fun getRemainingTokens(key: String): String? = glideClient.hget(key, "remaining").get()

    override fun newRealTimeRateLimiter(tpsThreshold: Int) = TokenBucketRateLimiter(tpsThreshold, tpsThreshold, TimeUnit.SECONDS, glideClient)
}