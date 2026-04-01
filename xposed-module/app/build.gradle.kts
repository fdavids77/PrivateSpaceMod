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
        versionCode = 1
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Output APK with predictable name for Magisk module bundling
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
