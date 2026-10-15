import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun getAgoraAppId(): String {
    val localPropFile = rootProject.file("local.properties")
    val localPropKey = if (localPropFile.exists()) {
        val props = Properties()
        localPropFile.inputStream().use { props.load(it) }
        props.getProperty("AGORA_APP_ID")?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    return localPropKey
        ?: (project.findProperty("AGORA_APP_ID") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("AGORA_APP_ID")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "YOUR_AGORA_APP_ID_HERE"
}

fun getStreamTokenUrl(): String {
    val localPropFile = rootProject.file("local.properties")
    val localPropKey = if (localPropFile.exists()) {
        val props = Properties()
        localPropFile.inputStream().use { props.load(it) }
        props.getProperty("STREAM_TOKEN_URL")?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    return localPropKey
        ?: (project.findProperty("STREAM_TOKEN_URL") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("STREAM_TOKEN_URL")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "https://tronico.kujuhk.com/live/token"
}

fun getVideoUploadUrl(): String {
    val localPropFile = rootProject.file("local.properties")
    val localPropKey = if (localPropFile.exists()) {
        val props = Properties()
        localPropFile.inputStream().use { props.load(it) }
        props.getProperty("VIDEO_UPLOAD_URL")?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    return localPropKey
        ?: (project.findProperty("VIDEO_UPLOAD_URL") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("VIDEO_UPLOAD_URL")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "https://tronico.kujuhk.com/live/video"
}

fun getPhotoUploadUrl(): String {
    val localPropFile = rootProject.file("local.properties")
    val localPropKey = if (localPropFile.exists()) {
        val props = Properties()
        localPropFile.inputStream().use { props.load(it) }
        props.getProperty("PHOTO_UPLOAD_URL")?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    return localPropKey
        ?: (project.findProperty("PHOTO_UPLOAD_URL") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("PHOTO_UPLOAD_URL")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "https://tronico.kujuhk.com/live/photo"
}

fun getStreamTokenSecret(): String {
    val localPropFile = rootProject.file("local.properties")
    val localPropKey = if (localPropFile.exists()) {
        val props = Properties()
        localPropFile.inputStream().use { props.load(it) }
        props.getProperty("STREAM_TOKEN_SECRET")?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    return localPropKey
        ?: (project.findProperty("STREAM_TOKEN_SECRET") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("STREAM_TOKEN_SECRET")?.trim()?.takeIf { it.isNotEmpty() }
        ?: ""
}

fun getDjiAppKeyPhone(): String {
    val localPropFile = rootProject.file("local.properties")
    val localPropKey = if (localPropFile.exists()) {
        val props = Properties()
        localPropFile.inputStream().use { props.load(it) }
        props.getProperty("DJI_APP_KEY_PHONE")?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    return localPropKey
        ?: (project.findProperty("DJI_APP_KEY_PHONE") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("DJI_APP_KEY_PHONE")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "YOUR_DJI_APP_KEY_HERE"
}

val djiMsdkVersion: String =
    rootProject.findProperty("djiMsdkVersion") as? String ?: "5.14.0"

android {
    namespace = "com.dji.dronecontrol.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dji.dronecontrol.phone"
        minSdk = 24
        targetSdk = 34
        versionCode = 22
        versionName = "1.0.21"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val resolvedDjiAppKey = getDjiAppKeyPhone()
        manifestPlaceholders["DJI_API_KEY"] = resolvedDjiAppKey
        buildConfigField("String", "DJI_APP_KEY", "\"$resolvedDjiAppKey\"")
        val resolvedAgoraAppId = getAgoraAppId()
        manifestPlaceholders["AGORA_APP_ID"] = resolvedAgoraAppId
        buildConfigField("String", "AGORA_APP_ID", "\"$resolvedAgoraAppId\"")
        buildConfigField("String", "STREAM_TOKEN_URL", "\"${getStreamTokenUrl()}\"")
        buildConfigField("String", "STREAM_TOKEN_SECRET", "\"${getStreamTokenSecret()}\"")
        buildConfigField("String", "PHOTO_UPLOAD_URL", "\"${getPhotoUploadUrl()}\"")
        buildConfigField("String", "VIDEO_UPLOAD_URL", "\"${getVideoUploadUrl()}\"")

        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
            )
            listOf(
                "libconstants.so", "libdji_innertools.so", "libdjibase.so", "libDJICSDKCommon.so",
                "libDJIFlySafeCore-CSDK.so", "libdjifs_jni-CSDK.so", "libDJIRegister.so", "libdjisdk_jni.so",
                "libDJIUpgradeCore.so", "libDJIUpgradeJNI.so", "libDJIWaypointV2Core-CSDK.so", "libdjiwpv2-CSDK.so",
                "libFlightRecordEngine.so", "libvideo-framing.so", "libwaes.so", "libagora-rtsa-sdk.so",
                "libc++.so", "libc++_shared.so", "libmrtc_28181.so", "libmrtc_agora.so", "libmrtc_core.so",
                "libmrtc_core_jni.so", "libmrtc_data.so", "libmrtc_log.so", "libmrtc_onvif.so", "libmrtc_rtmp.so",
                "libmrtc_rtsp.so", "libdjivideo.so", "libSDKRelativeJNI.so", "libUpgradeVerify.so", "libFRCorkscrew.so",
            ).forEach { keepDebugSymbols += "**/$it" }
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":dji-adapter"))
    implementation(project(":app-ui"))
    implementation(project(":app-persistence"))

    implementation("com.dji:dji-sdk-v5-aircraft:$djiMsdkVersion")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:$djiMsdkVersion")
    runtimeOnly("com.dji:dji-sdk-v5-networkImp:$djiMsdkVersion")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
