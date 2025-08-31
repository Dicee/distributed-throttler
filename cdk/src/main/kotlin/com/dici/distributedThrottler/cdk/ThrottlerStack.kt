package com.dici.distributedThrottler.cdk;

import software.amazon.awscdk.Duration
import software.amazon.awscdk.Stack
import software.amazon.awscdk.StackProps
import software.amazon.awscdk.services.iam.ManagedPolicy
import software.amazon.awscdk.services.iam.Role
import software.amazon.awscdk.services.iam.ServicePrincipal
import software.amazon.awscdk.services.lambda.DockerImageCode
import software.amazon.awscdk.services.lambda.DockerImageFunction
import software.amazon.awscdk.services.lambda.Function
import software.constructs.Construct

class ThrottlerStack(scope: Construct, props: StackProps? = null) : Stack(scope, "ThrottlerStack", props) {
    init {
        createThrottlerLambda()
    }

    private fun createThrottlerLambda(): Function {
        val functionName = "ThrottlerLambda"
        val roleName = "${functionName}InvocationRole"

        val role = Role.Builder.create(this, roleName)
            .roleName(roleName)
            .assumedBy(ServicePrincipal("lambda.amazonaws.com"))
            .managedPolicies(listOf(ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")))
            .build()

        return DockerImageFunction.Builder.create(this, functionName)
            .functionName(functionName)
            .code(DockerImageCode.fromImageAsset("docker"))
            .role(role)
            .timeout(Duration.seconds(30))
            .memorySize(256)
            .build()
    }
}
