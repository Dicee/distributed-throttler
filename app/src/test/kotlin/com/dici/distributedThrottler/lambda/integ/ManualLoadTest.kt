package com.dici.distributedThrottler.lambda.integ

import com.dici.distributedThrottler.lambda.Request
import com.dici.distributedThrottler.lambda.config.ThrottlingAlgorithm
import com.dici.distributedThrottler.lambda.logging.log
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvokeResponse
import java.io.File
import java.util.concurrent.TimeUnit

private val mapper = ObjectMapper()

// I do not have CI/CD for this project so this file is aimed at making manual runs against different algorithms
@OptIn(DelicateCoroutinesApi::class)
fun main(): Unit = runBlocking {
    try {
        val lambdaClient = LambdaClient.builder()
            .region(Region.EU_NORTH_1)
            .credentialsProvider(ProfileCredentialsProvider.builder()
                .profileName(System.getProperty("AWS_DEFAULT_PROFILE"))
                .build()
            )
            .build()

        val algorithm = ThrottlingAlgorithm.SLIDING_WINDOW_COUNTER
        runHighTpsSimulation(lambdaClient, algorithm)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun runHighTpsSimulation(lambdaClient: LambdaClient, algorithm: ThrottlingAlgorithm) {
    runBlocking(newFixedThreadPoolContext(8, "ManualLoadTest")) {
        launch { runSimulationFor(lambdaClient, "traffic-HighTpsClient-ListResources.tsv", algorithm) }
//        launch { runSimulationFor(lambdaClient, "traffic-HighTpsClient-AddResource.tsv", algorithm) }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun runLowTpsSimulation(lambdaClient: LambdaClient, algorithm: ThrottlingAlgorithm) {
    runBlocking(newFixedThreadPoolContext(5, "ManualLoadTest")) {
        runSimulationFor(lambdaClient, "traffic-LowTpsClient-ListResources.tsv", algorithm)
    }
}

private suspend fun CoroutineScope.runSimulationFor(lambdaClient: LambdaClient, trafficFileName: String, algorithm: ThrottlingAlgorithm) {
    val simulatedTraffic = parseTrafficFile(File(trafficFileName))

    val (client, operation) = simulatedTraffic
    log.info("Starting simulation for $client:$operation with algorithm $algorithm")

    try {
        simulateTraffic(simulatedTraffic, algorithm, lambdaClient)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun parseTrafficFile(file: File): SimulatedTraffic {
    val parts = file.nameWithoutExtension.split("-")
    val client = parts[1]
    val operation = parts[2]

    val lines = file.readLines()
    val header = lines.first()

    val timeUnit = TimeUnit.valueOf(header.substringAfter("(").substringBefore(")"))
    val calls = lines.drop(1).map { line ->
        val (timestampSeconds, calls) = line.split("\t")
        // Timestamps are stored as a floating point seconds number to be more human-readable than a long millis timestamp. Helps when
        // manually picking a randomized traffic file that seems decent.
        CallsToMake((timestampSeconds.toDouble() * 1000).toLong(), calls.toInt())
    }

    return SimulatedTraffic(client, operation, timeUnit, calls)
}

private fun serializeRequest(request: Request) = SdkBytes.fromUtf8String(mapper.writeValueAsString(request))

@OptIn(DelicateCoroutinesApi::class)
private suspend fun CoroutineScope.simulateTraffic(simulatedTraffic: SimulatedTraffic, throttlingAlgorithm: ThrottlingAlgorithm, lambdaClient: LambdaClient) {
    // the payload will be the same for all the calls in this file, so serialize it only once
    val request = Request(simulatedTraffic.client, simulatedTraffic.operation, throttlingAlgorithm.name)
    val payload = serializeRequest(request)

    val start = System.currentTimeMillis()

    // assume the whole file honors the same interval, which is true for files created by TrafficGenerator
    val intervalMillis = simulatedTraffic.calls[1].timestamp - simulatedTraffic.calls[0].timestamp
    for (callsToMake in simulatedTraffic.calls) {
        val (timestampMs, callCount) = callsToMake

        if (callCount == 0) {
            if (Math.random() < 0.2)  log.info("No calls to make for this time interval, delaying by $intervalMillis ms")
            delay(intervalMillis)
        } else {
            val delayMillis = intervalMillis / callCount
            for (i in 1..callCount) {
                launch {
                    callLambda(lambdaClient, payload)
                    if (Math.random() < 0.01) {
                        log.info("Completed call $i of $callCount. Actual millis vs expected: ${System.currentTimeMillis() - start - timestampMs}")
                    }
                }
                delay(delayMillis)
            }
        }
    }
}

private fun callLambda(lambdaClient: LambdaClient, payload: SdkBytes): InvokeResponse {
    return lambdaClient.invoke { it
        .functionName("ThrottlerLambda")
        .payload(payload)
    }
}

private data class SimulatedTraffic(val client: String, val operation: String, val timeUnit: TimeUnit, val calls: List<CallsToMake>)
private data class CallsToMake(val timestamp: Long, val callCount: Int)