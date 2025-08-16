package com.dici.distributedThrottler.cdk;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

object DistributedThrottlerApp {
    @JvmStatic
    fun main(args: Array<String>) {
        val app = App()

        ThrottlerStack(app, StackProps.builder()
                .env(Environment.builder()
                    .account(System.getenv("AWS_ACCOUNT_ID"))
                    .region("us-east-1")
                    .build()
                )
                .build()
        );
        
        app.synth();
    }
}
