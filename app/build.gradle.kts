plugins {
    id("buildlogic.kotlin-application-conventions")
}

dependencies {
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
    implementation("io.valkey:valkey-glide:2.0.1:linux-x86_64")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.2")

    testImplementation("org.assertj:assertj-core:3.27.3")
}

val jarName = "distributed-throttler.jar"
// used by the Docker build to get all runtime dependencies in the image
tasks.register<Copy>("assemble-jars") {
    from(layout.buildDirectory.dir("classes/")) {
        into("classes")
    }

    from(configurations.runtimeClasspath) {
        into("lib")
    }

    into(layout.buildDirectory.dir("assets"))
}

tasks.named<Jar>("jar") {
    archiveFileName.set(jarName)
}