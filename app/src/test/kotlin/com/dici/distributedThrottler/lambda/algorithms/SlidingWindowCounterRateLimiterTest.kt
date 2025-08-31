package com.dici.distributedThrottler.lambda.algorithms

import com.dici.distributedThrottler.lambda.util.OTHER_CONTEXT
import com.dici.distributedThrottler.lambda.util.ValkeyTestBase
import glide.api.models.commands.ScriptOptions
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Captor
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoSettings
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.*

private const val THRESHOLD = 20
private val UNIT = MINUTES
private val WINDOW_DURATION = Duration.ofMinutes(1)
private val BUCKET_DURATION = Duration.ofMillis(600) // 60s / 100 buckets

private const val DEFAULT_KEY_TIMESTAMPS = "{sliding-window:dummy:test}:timestamps"
private const val DEFAULT_KEY_COUNTS = "{sliding-window:dummy:test}:counts"
private const val OTHER_KEY_TIMESTAMPS = "{sliding-window:other:key}:timestamps"
private const val OTHER_KEY_COUNTS = "{sliding-window:other:key}:counts"

@MockitoSettings
class SlidingWindowCounterRateLimiterTest : ValkeyTestBase() {
    private lateinit var rateLimiter: SlidingWindowCounterRateLimiter

    @Captor private lateinit var scriptOptionsCaptor: ArgumentCaptor<ScriptOptions>

    @BeforeEach
    fun setUp() {
        rateLimiter = SlidingWindowCounterRateLimiter(THRESHOLD, UNIT, glideClient, valkeyTime)
    }

    @ParameterizedTest
    @EnumSource(value = TimeUnit::class)
    fun testConstructor_rejectsTimeUnitUnderSeconds(unit: TimeUnit) {
        when (unit) {
            NANOSECONDS, MICROSECONDS, MILLISECONDS ->
                assertThatThrownBy { SlidingWindowCounterRateLimiter(THRESHOLD, unit, glideClient) }
                    .isExactlyInstanceOf(IllegalArgumentException::class.java)
                    .hasMessage("Please use SECONDS resolution at most. Unit was: $unit")
            else ->
                assertThatCode { SlidingWindowCounterRateLimiter(THRESHOLD, unit, glideClient) }
                    .doesNotThrowAnyException()
        }
    }

    @Test
    fun testGrant_bucketConfiguration_largeTimeUnit_usesTimeUnit() {
        val callRateThreshold = 300
        rateLimiter = SlidingWindowCounterRateLimiter(callRateThreshold, MINUTES, glideClient, valkeyTime)
        rateLimiter.grant(1, context)

        // voluntarily hardcoding rather than calculating values so that the test's code does not simply duplicate the tested code
        assertThatBucketConfigurationWas(
            expectedWindowDuration = Duration.ofMinutes(1),
            expectedWindowThreshold = callRateThreshold,
            expectedBucketDuration = Duration.ofMillis(600), // 100 buckets within 1 minute
        )
    }

    @Test
    fun testGrant_bucketConfiguration_secondTimeUnit_usesMinimumWindowDuration() {
        val callRateThreshold = 300
        rateLimiter = SlidingWindowCounterRateLimiter(callRateThreshold, SECONDS, glideClient, valkeyTime)
        rateLimiter.grant(1, context)

        // voluntarily hardcoding rather than calculating values so that the test's code does not simply duplicate the tested code
        assertThatBucketConfigurationWas(
            expectedWindowDuration = Duration.ofSeconds(5), // the expected minimum window duration
            expectedWindowThreshold = 1500, // 5 seconds window instead of the 1s the TPS threshold was expressed in
            expectedBucketDuration = Duration.ofMillis(50), // 100 buckets within 5 seconds
        )
    }

    @Test
    fun testGrant_newKey() {
        assertNoDataFor(DEFAULT_KEY_TIMESTAMPS, DEFAULT_KEY_COUNTS)

        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.GRANTED)

        assertThatTimestampsAre(DEFAULT_KEY_TIMESTAMPS, listOf(0))
        assertThatCountsAre(DEFAULT_KEY_COUNTS, mapOf(
            "total_count" to "1",
            "0" to "1"
        ))
    }

    @Test
    fun testGrant_bucketAggregation_roundsCurrentTimeToClosestBucket() {
        ticker.advanceBy(BUCKET_DURATION.plusMillis(25))
        assertThat(rateLimiter.grant(2, context)).isEqualTo(RateLimiterResult.GRANTED)

        assertThatTimestampsAre(DEFAULT_KEY_TIMESTAMPS, listOf(600))
        assertThatCountsAre(DEFAULT_KEY_COUNTS, mapOf(
            "total_count" to "2",
            "600" to "2",
        ))
    }

    @Test
    fun testGrant_bucketAggregation_createsNextBucketWhenNeeded() {
        assertThat(rateLimiter.grant(2, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.advanceBy(BUCKET_DURATION.multipliedBy(2).plusMillis(5))
        assertThat(rateLimiter.grant(3, context)).isEqualTo(RateLimiterResult.GRANTED)

        assertThatTimestampsAre(DEFAULT_KEY_TIMESTAMPS, listOf(0, 1200))
        assertThatCountsAre(DEFAULT_KEY_COUNTS, mapOf(
            "total_count" to "5",
            "0" to "2",
            "1200" to "3",
        ))
    }

    @Test
    fun testGrant_bucketAggregation_aggregatesToSameBucketWhenTimeIsStillWithinIt() {
        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.advanceBy(BUCKET_DURATION.multipliedBy(2))
        assertThat(rateLimiter.grant(2, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.advanceBy(Duration.ofMillis(50))
        assertThat(rateLimiter.grant(3, context)).isEqualTo(RateLimiterResult.GRANTED)

        assertThatTimestampsAre(DEFAULT_KEY_TIMESTAMPS, listOf(0, 1200))
        assertThatCountsAre(DEFAULT_KEY_COUNTS, mapOf(
            "total_count" to "6",
            "0" to "1",
            "1200" to "5",
        ))
    }

    @Test
    fun testGrant_bucketAggregation_expiresBucketsOutsideWindow() {
        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.advanceBy(BUCKET_DURATION.multipliedBy(2))
        assertThat(rateLimiter.grant(2, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.advanceBy(BUCKET_DURATION)
        assertThat(rateLimiter.grant(3, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.setAt(Instant.EPOCH.plus(WINDOW_DURATION)) // now we're at the very limit
        assertThat(rateLimiter.grant(4, context)).isEqualTo(RateLimiterResult.GRANTED)

        assertThatTimestampsAre(DEFAULT_KEY_TIMESTAMPS, listOf(0, 1200, 1800, 60000))
        assertThatCountsAre(DEFAULT_KEY_COUNTS, mapOf(
            "total_count" to "10",
            "0" to "1",
            "1200" to "2",
            "1800" to "3",
            "60000" to "4",
        ))

        // the first 2 data points are completely outside the window, while the third still overlaps it since it's stored in a bucket corresponding
        // to the [BUCKET_DURATION * 3, BUCKET_DURATION * 4] interval
        ticker.advanceBy(BUCKET_DURATION.multipliedBy(3).plusMillis(5))
        assertThat(rateLimiter.grant(5, context)).isEqualTo(RateLimiterResult.GRANTED)

        assertThatTimestampsAre(DEFAULT_KEY_TIMESTAMPS, listOf(1800, 60_000, 61_800))
        assertThatCountsAre(DEFAULT_KEY_COUNTS, mapOf(
            "total_count" to "12",
            "1800" to "3",
            "60000" to "4",
            "61800" to "5",
        ))
    }

    @Test
    fun testGrant_bucketAggregation_allBucketsAreExpired() {
        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.advanceBy(BUCKET_DURATION.multipliedBy(2))
        assertThat(rateLimiter.grant(2, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.advanceBy(WINDOW_DURATION.multipliedBy(2))
        assertThat(rateLimiter.grant(3, context)).isEqualTo(RateLimiterResult.GRANTED)

        assertThatTimestampsAre(DEFAULT_KEY_TIMESTAMPS, listOf(121_200))
        assertThatCountsAre(DEFAULT_KEY_COUNTS, mapOf(
            "total_count" to "3",
            "121200" to "3",
        ))
    }

    @Test
    fun testGrant_consumeAllTokensAndThenDenied() {
        assertThat(rateLimiter.grant(THRESHOLD - 3, context)).isEqualTo(RateLimiterResult.GRANTED)
        assertThat(rateLimiter.grant(3, context)).isEqualTo(RateLimiterResult.GRANTED)
        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.DENIED)
    }

    @Test
    fun testGrant_deniedButLaterGranted() {
        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.advanceBy(BUCKET_DURATION)
        assertThat(rateLimiter.grant(THRESHOLD - 1, context)).isEqualTo(RateLimiterResult.GRANTED)

        ticker.setAt(Instant.EPOCH.plus(WINDOW_DURATION).plus(BUCKET_DURATION))
        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.DENIED)

        ticker.advanceBy(Duration.ofMillis(1))
        assertThat(rateLimiter.grant(1, context)).isEqualTo(RateLimiterResult.GRANTED)
    }

    @Test
    fun testGrant_keysAreIndependent() {
        assertNoDataFor(DEFAULT_KEY_TIMESTAMPS, DEFAULT_KEY_COUNTS)
        assertNoDataFor(OTHER_KEY_TIMESTAMPS, OTHER_KEY_COUNTS)

        val (r1, r2) = (THRESHOLD - 2 to 1)
        assertThat(rateLimiter.grant(r1, context)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatCountsAre(DEFAULT_KEY_COUNTS, mapOf(
            "total_count" to r1.toString(),
            "0" to r1.toString()
        ))

        assertThat(rateLimiter.grant(r2, OTHER_CONTEXT)).isEqualTo(RateLimiterResult.GRANTED)
        assertThatCountsAre(OTHER_KEY_COUNTS, mapOf(
            "total_count" to r2.toString(),
            "0" to r2.toString()
        ))

        assertThat(rateLimiter.grant(3, context)).isEqualTo(RateLimiterResult.DENIED)
        assertThat(rateLimiter.grant(3, OTHER_CONTEXT)).isEqualTo(RateLimiterResult.GRANTED)
    }

    private fun assertThatBucketConfigurationWas(
        expectedWindowDuration: Duration,
        expectedWindowThreshold: Int,
        expectedBucketDuration: Duration,
    ) {
        verify(glideClient).invokeScript(any(), scriptOptionsCaptor.capture())

        // have to resort to this because Glide models do not implement equals...
        assertThat(scriptOptionsCaptor.value.args).isEqualTo(
            listOf(
                "1",
                expectedWindowDuration.toMillis().toString(),
                expectedWindowThreshold.toString(),
                expectedBucketDuration.toMillis().toString(),
                valkeyTime.currentNanos().toString(),
            )
        )
    }

    private fun assertNoDataFor(timestampsKey: String, countsKey: String) {
        assertThatTimestampsAreEmptyFor(timestampsKey)
        assertThatCountsAreEmptyFor(countsKey)
    }

    private fun assertThatTimestampsAreEmptyFor(key: String) {
        assertThat(getTimestamps(key)).isEmpty();
    }

    private fun assertThatTimestampsAre(key: String, timestamps: List<Int>) {
        assertThat(getTimestamps(key))
            .containsExactlyElementsOf(timestamps.flatMap { listOf(it, it) }.map(Any::toString))
    }

    private fun assertThatCountsAreEmptyFor(key: String) {
        assertThat(getCounts(key)).isEmpty()
    }

    private fun assertThatCountsAre(key: String, counts: Map<String, String>) {
        assertThat(getCounts(key)).isEqualTo(counts)
    }

    private fun getTimestamps(key: String) = glideClient.zscan(key, "0").get()[1] as Array<*>

    private fun getCounts(key: String) = glideClient.hgetall(key).get()
}