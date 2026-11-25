import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Version and signing come from CI (see .github/workflows/release.yml).
val awcVersionCode = providers.gradleProperty("awcVersionCode").map { it.toInt() }.getOrElse(1)
val awcVersionName = providers.gradleProperty("awcVersionName").getOrElse("0.0.0-dev")
val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull ?: "androidwebcam"
val hasReleaseSigning = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

android {
    namespace = "app.androidwebcam"
    compileSdk = 36

    defaultConfig {
        // Never change applicationId: upgrades install over the old version only with the same id and key.
        applicationId = "app.androidwebcam"
        minSdk = 23
        targetSdk = 36
        versionCode = awcVersionCode
        versionName = awcVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Native code: only Conscrypt (Android 6-9). Phones are ARM; x86_64 for the test emulators.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = keystorePassword
                storeType = "PKCS12"
                // Android 6.0 (API 23) only verifies v1 (JAR) signatures; newer versions use v2/v3.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time, java.util.Base64 etc. before Android 8 (API 26).
        isCoreLibraryDesugaringEnabled = true
    }

    androidResources {
        // Only ship the languages the app is translated into (also drops unused library translations).
        localeFilters += setOf("en", "zh-rCN")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Compressed in the APK (smaller download); the system unpacks it at installation.
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    // No Google "dependency metadata" blob in the APK (privacy, reproducibility).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        animationsDisabled = true
    }

    lint {
        checkDependencies = true
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.zxing.core)
    // TLS 1.3 on Android 6-9, whose built-in TLS stops at 1.2 (loaded only there, see Tls.provider).
    implementation(libs.conscrypt.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
