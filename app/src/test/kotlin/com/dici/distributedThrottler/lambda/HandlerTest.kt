package com.dici.distributedThrottler.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.dici.distributedThrottler.lambda.algorithms.RateLimiter
import com.dici.distributedThrottler.lambda.algorithms.RateLimiterResult
import com.dici.distributedThrottler.lambda.algorithms.RateLimiterResult.DENIED
import com.dici.distributedThrottler.lambda.algorithms.RateLimiterResult.GRANTED
import com.dici.distributedThrottler.lambda.config.GLOBAL
import com.dici.distributedThrottler.lambda.config.ThrottlingAlgorithm.TOKEN_BUCKET
import com.dici.distributedThrottler.lambda.config.ThrottlingPolicy
import com.dici.distributedThrottler.lambda.config.ThrottlingPolicyStore
import com.dici.distributedThrottler.lambda.config.ThrottlingScope
import com.dici.distributedThrottler.lambda.metrics.ThrottlingMetricsPublisher
import glide.api.GlideClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoSettings

private const val CLIENT = "client"
private const val OPERATION = "operation"

@MockitoSettings
class HandlerTest {
    val scope = ThrottlingScope(CLIENT, OPERATION)
    val globalScope = scope.copy(operation = GLOBAL)

    val algorithm = TOKEN_BUCKET
    val request = Request(CLIENT, OPERATION, algorithm.name)

    @Mock private lateinit var throttlingPolicyStore: ThrottlingPolicyStore

    @Mock private lateinit var glideClient: GlideClient
    @Mock private lateinit var metricsPublisher : ThrottlingMetricsPublisher
    @Mock private lateinit var context : Context

    @Mock private lateinit var operationPolicy: ThrottlingPolicy
    @Mock private lateinit var globalPolicy: ThrottlingPolicy

    @Mock private lateinit var operationRateLimiter: RateLimiter
    @Mock private lateinit var globalRateLimiter: RateLimiter

    private lateinit var handler: Handler

    @BeforeEach
    fun setUp() {
        handler = Handler(throttlingPolicyStore, glideClient, metricsPublisher)
    }

    @Test
    fun testHandle_noPolicyApplies() {
        assertSuccessfulHandling(GRANTED)
    }

    @ParameterizedTest
    @EnumSource(RateLimiterResult::class)
    fun testHandle_singlePolicyApplies(rateLimiterResult: RateLimiterResult) {
        mockOperationPolicy()

        `when`(throttlingPolicyStore.getApplicablePolicies(CLIENT, OPERATION)).thenReturn(listOf(operationPolicy))
        `when`(operationRateLimiter.grant(1, scope)).thenReturn(rateLimiterResult)

        assertSuccessfulHandling(rateLimiterResult)
    }

    @Test
    fun testHandle_multiplePolicyApply_allGrant() {
        mockOperationPolicy()
        mockGlobalPolicy()

        `when`(throttlingPolicyStore.getApplicablePolicies(CLIENT, OPERATION)).thenReturn(listOf(operationPolicy, globalPolicy))
        `when`(operationRateLimiter.grant(1, scope)).thenReturn(GRANTED)
        `when`(globalRateLimiter.grant(1, globalScope)).thenReturn(GRANTED)

        assertSuccessfulHandling(GRANTED)
    }

    @Test
    fun testHandle_multiplePolicyApply_oneDenies() {
        mockOperationPolicy()
        mockGlobalPolicy()

        `when`(throttlingPolicyStore.getApplicablePolicies(CLIENT, OPERATION)).thenReturn(listOf(operationPolicy, globalPolicy))
        `when`(operationRateLimiter.grant(1, scope)).thenReturn(GRANTED)
        `when`(globalRateLimiter.grant(1, globalScope)).thenReturn(DENIED)

        assertSuccessfulHandling(DENIED)
    }

    private fun mockOperationPolicy() {
        `when`(operationPolicy.newRateLimiter(algorithm, glideClient)).thenReturn(operationRateLimiter)
        `when`(operationPolicy.scope).thenReturn(scope)
    }

    private fun mockGlobalPolicy() {
        `when`(globalPolicy.newRateLimiter(algorithm, glideClient)).thenReturn(globalRateLimiter)
        `when`(globalPolicy.scope).thenReturn(globalScope)
    }

    private fun assertSuccessfulHandling(result: RateLimiterResult) {
        assertThat(handler.handleRequest(request, context)).isEqualTo(result)
        verify(metricsPublisher).publish(scope, result, algorithm)
    }
}