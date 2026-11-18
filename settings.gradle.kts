pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ForgeMind"
include(":app")
include(":termux-bridge-api", ":termux-bridge")
include(":terminal-emulator-remote")
if (providers.gradleProperty("bridgeTests").orNull == "true") {
    include(":termux-bridge-test-client")
}
