plugins {
    id("buildlogic.kotlin-application-conventions")
}

val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    implementation("software.amazon.awssdk:auth:2.33.0")
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")

    implementation("io.valkey:valkey-glide:2.0.1:linux-x86_64")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.2")

    implementation("software.amazon.cloudwatchlogs:aws-embedded-metrics:4.2.0")
    implementation("org.apache.logging.log4j:log4j-api:2.13.3")
    runtimeOnly("org.apache.logging.log4j:log4j-core:2.13.3")
    runtimeOnly("com.amazonaws:aws-lambda-java-log4j2:1.6.0")

    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")

    testImplementation(libs.mockito)
    testImplementation("org.mockito:mockito-junit-jupiter:${libs.versions.mockito.get()}")

    mockitoAgent(libs.mockito) { isTransitive = false }
}

val jarName = "distributed-throttler.jar"
// used by the Docker build to get all runtime dependencies in the image
tasks.register<Copy>("assemble-jars") {
    from(layout.buildDirectory.dir("classes/kotlin/main")) {
        into("classes")
    }

    from(layout.buildDirectory.dir("resources/main")) {
        into("resources")
    }

    from(configurations.runtimeClasspath) {
        into("lib")
    }

    into(layout.buildDirectory.dir("assets"))
}

tasks.named<Jar>("jar") {
    archiveFileName.set(jarName)
}

tasks {
    test {
        jvmArgs?.add("-javaagent:${mockitoAgent.asPath}")

        useJUnitPlatform()
    }
}
