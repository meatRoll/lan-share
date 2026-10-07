plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}

// Development builds pass -PenableLogs=true; formal release builds default to logging off.
val enableLogs = providers.gradleProperty("enableLogs").map { it.toBoolean() }.orElse(false)

android {
    namespace = "com.lanshare.app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    compileSdk = 33

    signingConfigs {
        create("release") {
            val keyFile = System.getenv("LANSHARE_RELEASE_STORE_FILE")
            if (!keyFile.isNullOrBlank()) {
                storeFile = file(keyFile)
                storePassword = System.getenv("LANSHARE_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("LANSHARE_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("LANSHARE_RELEASE_KEY_PASSWORD")
            }
            enableV1Signing = true
            enableV2Signing = true
        }
        getByName("debug") {
            // Xiaomi TV installers may require the legacy v1 signature in addition to v2.
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "LOGGING_ENABLED", enableLogs.get().toString())
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "LOGGING_ENABLED", enableLogs.get().toString())
        }
    }

    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            versionCode = providers.gradleProperty("lanshare.phone.versionCode").get().toInt()
            versionName = providers.gradleProperty("lanshare.phone.versionName").get()
        }
        create("tvLegacy") {
            applicationIdSuffix = ".tvlegacy"
            minSdk = 21
            targetSdk = 28
            versionCode = providers.gradleProperty("lanshare.tvLegacy.versionCode").get().toInt()
            versionName = providers.gradleProperty("lanshare.tvLegacy.versionName").get()
        }
        create("tvModern") {
            applicationIdSuffix = ".tv"
            minSdk = 26
            targetSdk = 33
            versionCode = providers.gradleProperty("lanshare.tvModern.versionCode").get().toInt()
            versionName = providers.gradleProperty("lanshare.tvModern.versionName").get()
        }
    }

    sourceSets {
        getByName("tvLegacy") {
            manifest.srcFile("src/tv/AndroidManifest.xml")
            res.srcDirs("src/tv/res")
        }
        getByName("tvModern") {
            manifest.srcFile("src/tv/AndroidManifest.xml")
            res.srcDirs("src/tv/res")
        }
    }

    defaultConfig {
        applicationId = "com.lanshare.app"
        minSdk = 26
        targetSdk = 33
    }
}

android {
    lintOptions {
        isCheckReleaseBuilds = false
        isAbortOnError = false
    }
}

// Refuse unsigned or debug-signed release packages; source-only compilation needs no key.
tasks.matching { it.name.startsWith("package") && it.name.endsWith("Release") }.configureEach {
    doFirst {
        listOf("LANSHARE_RELEASE_STORE_FILE", "LANSHARE_RELEASE_STORE_PASSWORD", "LANSHARE_RELEASE_KEY_ALIAS", "LANSHARE_RELEASE_KEY_PASSWORD").forEach {
            require(!System.getenv(it).isNullOrBlank()) { "Missing release signing environment variable: $it" }
        }
    }
}

// Keep the first prototype dependency-light so it builds offline with the local Android SDK.
