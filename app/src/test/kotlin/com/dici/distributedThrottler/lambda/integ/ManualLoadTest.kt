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
fun main(): Unit = runBlocking {
    try {
        val trafficFileName = "traffic-LowTpsClient-ListResources.tsv"
        val simulatedTraffic = parseTrafficFile(File(trafficFileName))
        val throttlingAlgorithm = ThrottlingAlgorithm.TOKEN_BUCKET

        val lambdaClient = LambdaClient.builder()
            .region(Region.EU_NORTH_1)
            .credentialsProvider(ProfileCredentialsProvider.builder()
                .profileName(System.getProperty("AWS_DEFAULT_PROFILE"))
                .build()
            )
            .build()

        val (client, operation) = simulatedTraffic
        log.info("Starting simulation for $client:$operation")

        simulateTraffic(simulatedTraffic, throttlingAlgorithm, lambdaClient)
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
private fun simulateTraffic(simulatedTraffic: SimulatedTraffic, throttlingAlgorithm: ThrottlingAlgorithm, lambdaClient: LambdaClient) {
    // the payload will be the same for all the calls in this file, so serialize it only once
    val request = Request(simulatedTraffic.client, simulatedTraffic.operation, throttlingAlgorithm.name)
    val payload = serializeRequest(request)

    // assume the whole file honors the same interval, which is true for files created by TrafficGenerator
    val intervalMillis = simulatedTraffic.calls[1].timestamp - simulatedTraffic.calls[0].timestamp
    for (callsToMake in simulatedTraffic.calls) {
        val (_, callCount) = callsToMake

        runBlocking {
            if (callCount == 0) {
                log.info("No calls to make for this time interval, delaying by $intervalMillis ms")
                delay(intervalMillis)
            } else {
                val delayMillis = intervalMillis / callCount
                for (i in 1..callCount) {
                    launch(newFixedThreadPoolContext(5, "TrafficGenWorkers")) {
                        callLambda(lambdaClient, payload)
                        log.info("Completed call $i of $callCount")
                    }
                    delay(delayMillis)
                }
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