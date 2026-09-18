plugins {
    alias(libs.plugins.android.application)
}

val releaseStoreFile = providers.environmentVariable("AIRPLAY_RECEIVER_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("AIRPLAY_RECEIVER_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("AIRPLAY_RECEIVER_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("AIRPLAY_RECEIVER_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.aquiles.airplayandroidtv"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.aquiles.airplayandroidtv"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("javax.jmdns:jmdns:3.4.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
