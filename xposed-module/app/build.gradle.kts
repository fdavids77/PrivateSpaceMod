plugins {
    id("com.android.application")
}

android {
    namespace = "com.fdavids77.privatespacmod"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fdavids77.privatespacmod"
        minSdk = 33
        targetSdk = 35
        versionCode = 6
        versionName = "2.5"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "PrivateSpaceMod.apk"
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")
}
