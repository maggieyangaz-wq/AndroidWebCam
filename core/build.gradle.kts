import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Keep test output readable in CI logs.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Local viewer test server (no phone needed): streams an H.264 Annex B file and JPEG frames.
// Usage: ./gradlew :core:devServer --args="<port> <file.h264> <jpegDir> <infoJsonOut>"
tasks.register<JavaExec>("devServer") {
    group = "verification"
    description = "Runs the viewer against synthetic media on 127.0.0.1 for browser testing."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.androidwebcam.core.DevServerKt")
}

// The phone's relay client against a local relay (tools/run-relay-tests.sh).
// Usage: ./gradlew :core:relayPhoneSim --args="<relay url> <key dir> <output dir>"
tasks.register<JavaExec>("relayPhoneSim") {
    group = "verification"
    description = "Connects the phone's relay client to a local relay with a test key."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.androidwebcam.core.RelayPhoneSimKt")
}
