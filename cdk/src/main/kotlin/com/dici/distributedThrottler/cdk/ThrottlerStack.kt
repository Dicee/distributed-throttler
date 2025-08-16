package com.dici.distributedThrottler.cdk;

import software.amazon.awscdk.services.lambda.DockerImageCode
import software.amazon.awscdk.services.lambda.DockerImageFunction
import software.amazon.awscdk.Stack
import software.amazon.awscdk.StackProps
import software.constructs.Construct

class ThrottlerStack(scope: Construct, props: StackProps? = null) : Stack(scope, "ThrottlerStack", props) {
    init {
        DockerImageFunction.Builder.create(this, "ThrottlerLambda")
            .functionName("Throttler")
            .code(DockerImageCode.fromImageAsset("docker"))
            .build()
    }
}
