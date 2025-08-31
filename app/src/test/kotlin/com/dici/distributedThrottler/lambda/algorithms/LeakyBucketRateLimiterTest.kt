package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.util.OTHER_CONTEXT
import com.dici.distributedThrottler.lambda.util.ValkeyTestBase
import com.dici.distributedThrottler.lambda.valkey.FakeTicker
import com.dici.distributedThrottler.lambda.valkey.ValkeyTime
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MICROSECONDS
import java.util.concurrent.TimeUnit.NANOSECONDS
import kotlin.math.roundToInt

private const val LOW_TPS = 3
private val LOW_TPS_TIME_UNIT = TimeUnit.MINUTES
private val LOW_TPS_EXPECTED_LEAK_PERIOD = Duration.ofSeconds(20) // 60s / 3
private const val LOW_TPS_EXPECTED_LEAK_AMOUNT = 1 // the TPS is low enough that we can use a leak period of 20 seconds and leak a single token at a time

private const val HIGH_TPS = 30_000
private val HIGH_TPS_TIME_UNIT = TimeUnit.SECONDS
private val HIGH_TPS_EXPECTED_LEAK_PERIOD = Duration.ofMillis(10) // we don't ever go lower than 10 ms
private const val HIGH_TPS_EXPECTED_LEAK_AMOUNT = HIGH_TPS / 100 // we're going to use 10 ms leak periods, so we'll leak a token 100 times per second

private const val DEFAULT_KEY = "leaky-bucket:dummy:test"
private const val OTHER_KEY = "leaky-bucket:other:key"

@ExtendWith(MockitoExtension::class)
class LeakyBucketRateLimiterTest : ValkeyTestBase() {
    private lateinit var lowTpsRateLimiter: LeakyBucketRateLimiter
    private lateinit var highTpsRateLimiter: LeakyBucketRateLimiter

    @Mock private lateinit var sleeper: Sleeper

    @BeforeEach
    fun setUp() {
        ticker = FakeTicker()
        lowTpsRateLimiter = LeakyBucketRateLimiter(LOW_TPS, LOW_TPS_TIME_UNIT, glideClient, valkeyTime, sleeper)
        highTpsRateLimiter = LeakyBucketRateLimiter(HIGH_TPS, HIGH_TPS_TIME_UNIT, glideClient, valkeyTime, sleeper)
    }

    @ParameterizedTest
    @EnumSource(value = TimeUnit::class)
    fun testConstructor_rejectsTimeUnitUnderMilliseconds(unit: TimeUnit) {
        when (unit) {
            NANOSECONDS, MICROSECONDS ->
                assertThatThrownBy { LeakyBucketRateLimiter(LOW_TPS, unit, glideClient) }
                    .isExactlyInstanceOf(IllegalArgumentException::class.java)
                    .hasMessage("Please use MILLISECONDS resolution at most. Unit was: $unit")
            else ->
                assertThatCode { LeakyBucketRateLimiter(LOW_TPS, unit, glideClient) }
                    .doesNotThrowAnyException()
        }
    }

    @Test
    fun testGrant_newKey_lowTps_grantsSingleCall() {
        assertThatAvailableTokensAreNullFor(DEFAULT_KEY)
        assertThat(lowTpsRateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatAvailableTokensAre(DEFAULT_KEY, 0)

        verifyNoInteractions(sleeper)
    }

    @Test
    fun testGrant_newKey_highTps_grantsMultipleCalls() {
        assertThatAvailableTokensAreNullFor(DEFAULT_KEY)

        val requested = 53
        assertThat(highTpsRateLimiter.grant(requested, context)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatAvailableTokensAre(DEFAULT_KEY, HIGH_TPS_EXPECTED_LEAK_AMOUNT - requested)

        verifyNoInteractions(sleeper)
    }

    @Test
    fun testGrant_successiveGrantsWhileTokensAreAvailable() {
        val (r1, r2) = 50 to 135

        assertThat(highTpsRateLimiter.grant(r1, context)).isEqualTo(RateLimiterResult.GRANTED)
        assertThat(highTpsRateLimiter.grant(r2, context)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatAvailableTokensAre(DEFAULT_KEY, HIGH_TPS_EXPECTED_LEAK_AMOUNT - r1 - r2)

        verifyNoInteractions(sleeper)
    }

    @Test
    fun testGrant_consumeAllTokensAndThenHasToWait_lowTps() {
        testGrant_consumeAllTokensAndThenHasToWait(
            lowTpsRateLimiter,
            LOW_TPS_EXPECTED_LEAK_AMOUNT,
            LOW_TPS_EXPECTED_LEAK_PERIOD,
            Duration.ofSeconds(18).plusMillis(17), // less than leak period
        )
    }

    @Test
    fun testGrant_consumeAllTokensAndThenHasToWait_highTps() {
        testGrant_consumeAllTokensAndThenHasToWait(
            highTpsRateLimiter,
            HIGH_TPS_EXPECTED_LEAK_AMOUNT,
            HIGH_TPS_EXPECTED_LEAK_PERIOD,
            Duration.ofMillis(5).plusNanos(17_000), // less than leak period (using a round number of micros as Redis time is in micro precision)
        )
    }

    private fun testGrant_consumeAllTokensAndThenHasToWait(
        rateLimiter: RateLimiter,
        expectedLeakAmount: Int,
        expectedLeakPeriod: Duration,
        advanceDuration: Duration,
    ) {
        assertThatAvailableTokensAreNullFor(DEFAULT_KEY)
        assertThat(rateLimiter.grant(expectedLeakAmount, context)).isEqualTo(RateLimiterResult.GRANTED)

        // denied for a call that happens just after (we simulate that by not sleeping)
        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.DENIED)

        // advance the timeline a little to check that the returned wait time is well calculated
        ticker.advanceBy(advanceDuration)

        mockSleeper()

        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.GRANTED)
        assertThat(ticker.nanos).isEqualTo(expectedLeakPeriod.toNanos()) // we waited until the next leak period
    }

    @Test
    fun testGrant_requestedCapacityExceedsLeakAmount_lowTps() {
        assertThatThrownBy { lowTpsRateLimiter.grant(2, context) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cannot request more capacity in one go than the number of tokens leaked in each leak period (1.0), but requested capacity was: 2")

        // check there were no side effects
        assertThatAvailableTokensAreNullFor(DEFAULT_KEY)
    }

    @Test
    fun testGrant_requestedCapacityExceedsLeakAmount_highTps() {
        assertThatThrownBy { highTpsRateLimiter.grant(301, context) }
            .isExactlyInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cannot request more capacity in one go than the number of tokens leaked in each leak period (300.0), but requested capacity was: 301")

        // check there were no side effects
        assertThatAvailableTokensAreNullFor(DEFAULT_KEY)
    }

    @Test
    fun testGrant_keysAreIndependent() {
        assertThatAvailableTokensAreNullFor(DEFAULT_KEY)
        assertThatAvailableTokensAreNullFor(OTHER_KEY)

        val (r1, r2) = (160 to 34)
        assertThat(highTpsRateLimiter.grant(r1, context)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatAvailableTokensAre(DEFAULT_KEY, HIGH_TPS_EXPECTED_LEAK_AMOUNT - r1)

        assertThat(highTpsRateLimiter.grant(r2, OTHER_CONTEXT)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatAvailableTokensAre(OTHER_KEY, HIGH_TPS_EXPECTED_LEAK_AMOUNT - r2)

        assertThat(highTpsRateLimiter.grant(180, context)).isEqualTo(RateLimiterResult.DENIED)
        assertThat(highTpsRateLimiter.grant(180, OTHER_CONTEXT)).isEqualTo(RateLimiterResult.GRANTED)
    }

    private fun mockSleeper() {
        `when`(sleeper.sleep(anyLong(), anyInt())).thenAnswer { invocation ->
            ticker.advanceBy(Duration.ofMillis(invocation.getArgument(0)))
            ticker.advanceBy(Duration.ofNanos(invocation.getArgument<Int>(1).toLong()))
        }
    }

    private fun assertThatAvailableTokensAreNullFor(key: String) {
        assertThat(getAvailableTokens(key)).isNull()
    }

    private fun assertThatAvailableTokensAre(key: String, expected: Int) {
        assertThat(getAvailableTokens(key).toDouble().roundToInt()).isEqualTo(expected)
    }

    private fun getAvailableTokens(key: String) = glideClient.hget(key, "available_tokens").get()
}