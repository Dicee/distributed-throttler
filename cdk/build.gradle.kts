plugins {
    application
    id("buildlogic.kotlin-common-conventions")
}

dependencies {
    implementation("software.amazon.awscdk:aws-cdk-lib:2.211.0")
    implementation("software.amazon.awscdk:core:1.204.0")
    implementation("software.constructs:constructs:10.4.2")
}

application {
    mainClass.set("com.dici.distributedThrottler.cdk.DistributedThrottlerApp")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}