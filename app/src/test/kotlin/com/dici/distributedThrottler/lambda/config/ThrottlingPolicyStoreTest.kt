
package com.dici.distributedThrottler.lambda.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ThrottlingPolicyStoreTest {
    private val identity = "test-identity"
    private val operation = "test-operation"
    private val rateLimits = RateLimits(10, unit = TimeUnit.SECONDS)

    @Test
    fun testGetApplicablePolicies_empty() {
        val policyStore = ThrottlingPolicyStore(emptyMap())
        val policies = policyStore.getApplicablePolicies(identity, operation)
        assertThat(policies).isEmpty()
    }

    @Test
    fun testGetApplicablePolicies_operationLevel_present_noGlobal() {
        val policyStore = ThrottlingPolicyStore(mapOf(identity to mapOf(operation to rateLimits)))
        val policies = policyStore.getApplicablePolicies(identity, operation)
        assertThat(policies).containsExactly(ThrottlingPolicy(ThrottlingScope(identity, operation), rateLimits))
    }

    @Test
    fun testGetApplicablePolicies_operationLevel_present_withGlobal() {
        val operationRateLimits = RateLimits(5, unit = TimeUnit.MINUTES)
        val policyStore = ThrottlingPolicyStore(mapOf(
            identity to mapOf(
                operation to operationRateLimits,
                GLOBAL to rateLimits
            )
        ))

        val policies = policyStore.getApplicablePolicies(identity, operation)
        assertThat(policies).containsExactlyInAnyOrder(
            ThrottlingPolicy(ThrottlingScope(identity, operation), operationRateLimits),
            ThrottlingPolicy(ThrottlingScope(identity, GLOBAL), rateLimits)
        )
    }

    @Test
    fun testGetApplicablePolicies_operationLevel_absent_noGlobal() {
        val policyStore = ThrottlingPolicyStore(mapOf(identity to mapOf("other-operation" to rateLimits)))
        assertThat(policyStore.getApplicablePolicies(identity, operation)).isEmpty()
    }

    @Test
    fun testGetApplicablePolicies_operationLevel_absent_withGlobal() {
        val policyStore = ThrottlingPolicyStore(mapOf(identity to mapOf(GLOBAL to rateLimits)))

        val policies = policyStore.getApplicablePolicies(identity, operation)
        assertThat(policies).containsExactly(ThrottlingPolicy(ThrottlingScope(identity, GLOBAL), rateLimits))
    }

    @Test
    fun testGetApplicablePolicies_global_present() {
        val policyStore = ThrottlingPolicyStore(mapOf(identity to mapOf(GLOBAL to rateLimits)))
        val policies = policyStore.getApplicablePolicies(identity, GLOBAL)

        assertThat(policies).containsExactly(ThrottlingPolicy(ThrottlingScope(identity, GLOBAL), rateLimits))
    }
}
