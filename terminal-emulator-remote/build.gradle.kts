import java.security.MessageDigest

plugins { id("com.android.library") }
val upstreamNative by configurations.creating { isTransitive = false }
dependencies { add(upstreamNative.name, "com.github.termux.termux-app:terminal-emulator:v0.118.3@aar") }
val nativeDirectory = layout.buildDirectory.dir("generated/upstream-jni")
val extractNative by tasks.registering(Sync::class) {
    val archive = upstreamNative
    from({ zipTree(archive.singleFile) }) {
        include("jni/**")
        eachFile { path = path.removePrefix("jni/") }
        includeEmptyDirs = false
    }
    into(nativeDirectory)
    doFirst {
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.singleFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "cbb915c4d7c51883f85397b46b74bf4b82fd32a8bbf0baeba1e0bc6d990d3e8a") { "Upstream native artifact checksum changed" }
    }
}
android {
    namespace = "com.termux.terminal"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    sourceSets.getByName("main").jniLibs.srcDir(nativeDirectory)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild").configure { dependsOn(extractNative) }
