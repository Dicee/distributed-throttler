package com.dici.distributedThrottler.lambda.integ

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.collections.forEachIndexed

private val rootDir = File("/home/courtino/repos/personal/distributed-throttler")

fun main() {
    try {
        generateTrafficFor(
            "LowTpsClient", "ListResources", TrafficOutlineConfig(
                simulationLength = 15,
                timeUnit = TimeUnit.MINUTES,
                baselineSegmentConfig = TrafficOutlineSegmentConfig(
                    maxCallRate = 30,
                    maxInterval = 4,
                    minGap = -2,
                    maxGap = 2,
                ),
                burstSegmentConfig = TrafficOutlineSegmentConfig(
                    maxCallRate = 40,
                    maxInterval = 1,
                    minGap = 1,
                    maxGap = 3,
                )
            ), TrafficScramblingConfig(
                stepMillis = 20000,
                scramblingFactor = 0.3,
            )
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun generateTrafficFor(
    client: String,
    operation: String,
    trafficOutlineConfig: TrafficOutlineConfig,
    trafficScramblingConfig: TrafficScramblingConfig,
) {
    val traffic = generateTrafficOutline(trafficOutlineConfig)
    println(traffic)

    val timeUnit = trafficOutlineConfig.timeUnit
    val scrambledTraffic = scrambleTraffic(traffic, trafficScramblingConfig, timeUnit)
    println(scrambledTraffic)

    rootDir.resolve("traffic-$client-$operation.tsv").printWriter().use { out ->
        out.println("time($timeUnit)\tcalls")
        scrambledTraffic.forEachIndexed { i, bucket -> out.println("${i * trafficScramblingConfig.stepMillis / 1000.0}\t${bucket}") }
    }
}

fun generateTrafficOutline(config: TrafficOutlineConfig): List<Int> {
    val tpsBuckets = MutableList(config.simulationLength) { 0 }

    generateTraffic(config.baselineSegmentConfig, tpsBuckets)
    generateTraffic(config.burstSegmentConfig, tpsBuckets)

    return tpsBuckets
}

fun generateTraffic(config: TrafficOutlineSegmentConfig, tpsBuckets: MutableList<Int>) {
    var i = 0
    while (i < tpsBuckets.size) {
        val numCalls = Random.nextInt(config.maxCallRate)
        val interval = Random.nextInt(config.maxInterval)

        for (j in i until min(i + interval, tpsBuckets.size)) {
            tpsBuckets[i] = tpsBuckets[i] + numCalls
            i++
        }

        i += Random.nextInt(config.minGap, config.maxGap)
        i = max(0, i)
    }
}

fun scrambleTraffic(traffic: List<Int>, config: TrafficScramblingConfig, timeUnit: TimeUnit): List<Int> {
    val clock = Clock()
    val trafficScrambler = TrafficScrambler(traffic, config.stepMillis, timeUnit, config.scramblingFactor, clock)
    val scrambledTraffic = mutableListOf<Int>()

    var numberOfCalls = trafficScrambler.nextNumberOfCalls()
    while (numberOfCalls >= 0) {
        scrambledTraffic.add(numberOfCalls)
        numberOfCalls = trafficScrambler.nextNumberOfCalls()
    }

    return scrambledTraffic
}

class Clock {
    private var _millis: Long = 0

    val now: Long get() = _millis

    fun advance(millis: Int) {
        _millis += millis
    }
}

class TrafficScrambler(
    private val traffic: List<Int>,
    private val stepMillis: Int,
    timeUnit: TimeUnit,
    private val scramblingFactor: Double,
    private val clock: Clock,
) {
    private var i = 0
    private var callsMade = 0
    private val timeUnitMillis = timeUnit.toMillis(1L).toInt()

    init {
        if (scramblingFactor <= 0 || scramblingFactor > 1) throw RuntimeException("Scrambling factor should be in ]0, 1] but was $scramblingFactor")
        if (stepMillis <= 0 || timeUnitMillis % stepMillis != 0) throw RuntimeException("The step (in milliseconds) should be a positive divisor of $timeUnit but was $stepMillis")
    }

    fun nextNumberOfCalls(): Int {
        if (i >= traffic.size) return -1
        if (i != (clock.now / timeUnitMillis).toInt()) throw RuntimeException("It appears that time was modified outside of TrafficScrambler")

        val target = traffic[i]
        if ((clock.now + stepMillis) % timeUnitMillis == 0L) {
            val calls = max(target - callsMade, 0)
            i++
            callsMade = 0
            clock.advance(stepMillis)
            return calls
        }

        val baseline = target / timeUnitMillis.toDouble() * stepMillis
        val scramblingSign = if (Random.nextBoolean()) 1 else -1
        val scrambledCalls = (baseline * (1 + scramblingSign * Random.nextDouble(scramblingFactor))).roundToInt()

        callsMade += scrambledCalls
        clock.advance(stepMillis)

        return scrambledCalls
    }
}

data class TrafficOutlineConfig(
    val simulationLength: Int,
    val baselineSegmentConfig: TrafficOutlineSegmentConfig,
    val burstSegmentConfig: TrafficOutlineSegmentConfig,
    val timeUnit: TimeUnit, // all integers in this code without an explicit unit name are using this unit
)

data class TrafficOutlineSegmentConfig(
    val maxCallRate: Int,
    val maxInterval: Int,
    val minGap: Int,
    val maxGap: Int,
)

data class TrafficScramblingConfig(
    val stepMillis: Int,
    val scramblingFactor: Double,
)
