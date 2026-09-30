pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:8.5.2")
            }
        }
    }
    repositories {
        maven { url = uri(rootDir.resolve("../offline-m2")) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // CameraX is mirrored locally for deterministic builds in restricted/offline environments.
        maven { url = uri(rootDir.resolve("../offline-m2")) }
        google()
        mavenCentral()
    }
}

rootProject.name = "SHELTER"
include(":app")
include(":whispercpp")
project(":whispercpp").projectDir = file("third_party/ggml-org-whisper.cpp-6e4ab85/examples/whisper.android/lib")
