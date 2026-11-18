import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.forgemind.termuxbridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.forgemind.termuxbridge"
        minSdk = 28
        // Match the GitHub Termux execution environment. Main ForgeMind stays target 36.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "FORGEMIND_CERTIFICATE", "\"7ced078e33f7bf877763312f4992608dc183f82df3dfc3c09f288032eeb18ba8\"")
    }
    signingConfigs {
        create("termuxGithub") {
            // Public upstream test key, supplied explicitly from outside the repository.
            providers.gradleProperty("termuxBridgeKeystore").orNull?.let { storeFile = file(it) }
            storePassword = "xrj45yWGLbsO7W0v"
            keyAlias = "alias"
            keyPassword = "xrj45yWGLbsO7W0v"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("termuxGithub")
            val certificate = providers.gradleProperty("bridgeDebugCertificate").orElse("").get()
            require(certificate.isEmpty() || certificate.matches(Regex("[a-fA-F0-9]{64}")))
            buildConfigField("String", "DEBUG_CLIENT_CERTIFICATE", "\"$certificate\"")
        }
        release {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("termuxGithub")
            buildConfigField("String", "DEBUG_CLIENT_CERTIFICATE", "\"\"")
        }
    }
    buildFeatures { buildConfig = true }
    // This companion is distributed for GitHub Termux, not through Google Play.
    // Its execution target follows Termux. Keep every other release lint check enabled.
    lint { disable += "ExpiredTargetSdkVersion" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    implementation(project(":termux-bridge-api"))
    implementation(project(":terminal-emulator-remote"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

tasks.matching { it.name in setOf("validateSigningDebug", "validateSigningRelease") }.configureEach {
    doFirst {
        val source = file(providers.gradleProperty("termuxBridgeKeystore").get())
        val keyStore = KeyStore.getInstance("JKS")
        source.inputStream().use { keyStore.load(it, "xrj45yWGLbsO7W0v".toCharArray()) }
        val certificate = keyStore.getCertificate("alias") ?: error("Termux signing certificate is missing")
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        check(digest == "b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1") {
            "The supplied keystore does not match GitHub Termux"
        }
    }
}
