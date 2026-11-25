import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hopplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hopplay"
        minSdk = 26
        targetSdk = 34
        // CI uses its monotonic run number; local debug builds retain a default version.
        versionCode = providers.environmentVariable("HOPPLAY_BUILD_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.3.1"
    }

    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
        }
        create("tv") {
            dimension = "device"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        // Private keystore material is supplied at build time, never committed to Git.
        val store = providers.environmentVariable("HOPPLAY_SIGNING_STORE").orNull
        if (store != null) {
            create("persistent") {
                storeFile = file(store)
                storePassword = providers.environmentVariable("HOPPLAY_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("HOPPLAY_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("HOPPLAY_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            if (signingConfigs.findByName("persistent") != null)
                signingConfig = signingConfigs.getByName("persistent")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/INDEX.LIST",
            "META-INF/versions/**",
            "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("androidx.media3.common.util.UnstableApi")
    }
}

val media3 = "1.5.0"

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // Embedded VLC/FFmpeg video and audio engine for Hi10P/HEVC Main10/legacy AVI.
    implementation("org.videolan.android:libvlc-all:3.6.5")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.hierynomus:sshj:0.38.0")
    implementation("com.hierynomus:smbj:0.13.0")

    if (providers.gradleProperty("ffmpeg").orNull == "true") {
        implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    }
}
