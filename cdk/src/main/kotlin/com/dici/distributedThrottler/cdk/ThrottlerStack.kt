package com.dici.distributedThrottler.cdk;

import software.amazon.awscdk.Stack
import software.amazon.awscdk.StackProps
import software.amazon.awscdk.services.s3.Bucket
import software.constructs.Construct

class ThrottlerStack(scope: Construct, props: StackProps? = null) : Stack(scope, "ThrottlerStack", props) {
    init {
        val bucket = Bucket.Builder.create(this, "MyBucket")
            .bucketName("courtino-test-my-bucket")
            .build()
    }
}
