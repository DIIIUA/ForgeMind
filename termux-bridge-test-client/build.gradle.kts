plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.forgemind.bridgeprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.forgemind.bridgeprobe"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "test-only"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { implementation(project(":termux-bridge-api")) }
