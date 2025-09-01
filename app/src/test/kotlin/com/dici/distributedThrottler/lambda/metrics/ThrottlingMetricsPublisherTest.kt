
package com.dici.distributedThrottler.lambda.metrics

import com.dici.distributedThrottler.lambda.algorithms.RateLimiterResult
import com.dici.distributedThrottler.lambda.config.ThrottlingAlgorithm
import com.dici.distributedThrottler.lambda.config.ThrottlingScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoSettings
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger

@MockitoSettings
class ThrottlingMetricsPublisherTest {
    @Mock private lateinit var metricsLogger: MetricsLogger

    private lateinit var publisher: ThrottlingMetricsPublisher

    @BeforeEach
    fun setup() {
        `when`(metricsLogger.putDimensions(any())).thenReturn(metricsLogger)
        `when`(metricsLogger.putMetric(anyString(), anyDouble())).thenReturn(metricsLogger)

        publisher = ThrottlingMetricsPublisher(metricsLogger)
    }

    @Test
    fun testPublish_granted() {
        testPublish(RateLimiterResult.GRANTED, 1.0)
    }

    @Test
    fun testPublish_denied() {
        testPublish(RateLimiterResult.DENIED, 0.0)
    }

    private fun testPublish(rateLimiterResult: RateLimiterResult, grantedCount: Double) {
        val scope = ThrottlingScope("identity", "operation")
        val algorithm = ThrottlingAlgorithm.TOKEN_BUCKET

        publisher.publish(scope, rateLimiterResult, algorithm)

        // unfortunately DimensionSet doesn't implement equals so we have to do it a bit uglily
        verify(metricsLogger).putDimensions(assertArg { dimensionSet ->
            assertThat(dimensionSet.dimensionKeys).containsExactly("Algorithm", "Identity", "Operation")
            assertThat(dimensionSet.getDimensionValue("Algorithm")).isEqualTo("TOKEN_BUCKET")
            assertThat(dimensionSet.getDimensionValue("Identity")).isEqualTo("identity")
            assertThat(dimensionSet.getDimensionValue("Operation")).isEqualTo("operation")
        })
        verify(metricsLogger).putMetric("Granted", grantedCount)
        verify(metricsLogger).flush()
    }
}
