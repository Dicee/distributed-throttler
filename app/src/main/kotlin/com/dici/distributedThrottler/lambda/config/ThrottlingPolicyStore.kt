package com.dici.distributedThrottler.lambda.config

import com.dici.distributedThrottler.lambda.algorithms.LeakyBucketRateLimiter
import com.dici.distributedThrottler.lambda.algorithms.RateLimiter
import com.dici.distributedThrottler.lambda.algorithms.SlidingWindowCounterRateLimiter
import com.dici.distributedThrottler.lambda.algorithms.TokenBucketRateLimiter
import com.dici.distributedThrottler.lambda.config.ThrottlingAlgorithm.*
import com.dici.distributedThrottler.lambda.valkey.GlideAdapter
import java.util.concurrent.TimeUnit

// just makes things nice to read, it doesn't contribute to type safety at all
typealias Identity = String
typealias Operation = String

// used to configure throttling at a global level for a client
const val GLOBAL: Operation = "*"

/**
 * We could make this more elaborate (and use an external data store) for a real use-case, for example with fallback configurations, priorities between
 * clients etc. However, this little class is enough for our mini project.
 */
class ThrottlingPolicyStore(private val policies: Map<Identity, Map<Operation, RateLimits>>) {
    fun getApplicablePolicies(identity: Identity, operation: Operation): List<ThrottlingPolicy> {
        val clientPolicies = policies[identity] ?: return listOf()

        val operationPolicy = clientPolicies[operation]
        val globalPolicy = if (operation == GLOBAL) null else clientPolicies[GLOBAL]

        fun toList(limits: RateLimits?, identity: Identity, operation: Operation): List<ThrottlingPolicy> =
            limits.let { if (it == null) listOf() else listOf(ThrottlingPolicy(ThrottlingScope(identity, operation), it)) }

        return toList(operationPolicy, identity, operation) + toList(globalPolicy, identity, GLOBAL)
    }
}

data class ThrottlingScope(val identity: Identity, val operation: Operation) {
    fun toThrottlingKey(namespace: String) = "$namespace:$identity:$operation"
}

data class ThrottlingPolicy(val scope: ThrottlingScope, val rateLimits: RateLimits) {
    fun newRateLimiter(algorithm: ThrottlingAlgorithm, glideAdapter: GlideAdapter): RateLimiter {
        val (threshold, burstThreshold, unit) = rateLimits
        return when (algorithm) {
            TOKEN_BUCKET -> TokenBucketRateLimiter(threshold, burstThreshold ?: threshold, unit, glideAdapter)
            LEAKY_BUCKET -> LeakyBucketRateLimiter(threshold, unit, glideAdapter)
            SLIDING_WINDOW_COUNTER -> SlidingWindowCounterRateLimiter(threshold, unit, glideAdapter)
        }
    }
}

data class RateLimits(val threshold: Int, val burstThreshold: Int? = null, val unit: TimeUnit)

enum class ThrottlingAlgorithm {
    TOKEN_BUCKET,
    LEAKY_BUCKET,
    SLIDING_WINDOW_COUNTER,
}