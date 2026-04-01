pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API hosted on jitpack
        maven { url = uri("https://jitpack.io") }
        // Rovo89 Xposed API
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "PrivateSpaceMod"
include(":app")
