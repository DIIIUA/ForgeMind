plugins { id("com.android.library") }
android {
    namespace = "com.forgemind.termuxbridge.api"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
