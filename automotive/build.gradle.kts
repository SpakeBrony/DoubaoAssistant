import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

val credentialProperties = Properties().apply {
    val file = rootProject.file("doubao-asr.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun credentialBuildConfigValue(key: String): String {
    val value = credentialProperties.getProperty(key, "").trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "")
        .replace("\r", "")
    return "\"$value\""
}

android {
    namespace = "com.spake.brony.doubaoassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.spake.brony.doubaoassistant"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
        buildConfigField("String", "DOUBAO_ASR_RESOURCE_ID", "\"volc.seedasr.sauc.duration\"")
        buildConfigField("String", "DOUBAO_CHAT_MODEL", "\"doubao-seed-2-0-mini-260428\"")
        buildConfigField("String", "DOUBAO_TTS_RESOURCE_ID", "\"seed-tts-2.0\"")
        buildConfigField("String", "DOUBAO_TTS_SPEAKER", "\"zh_female_vv_uranus_bigtts\"")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("general") {
            dimension = "distribution"
            applicationIdSuffix = ".general"
            buildConfigField("String", "DOUBAO_ASR_APP_ID", "\"\"")
            buildConfigField("String", "DOUBAO_ASR_ACCESS_TOKEN", "\"\"")
            buildConfigField("String", "DOUBAO_ARK_API_KEY", "\"\"")
        }
        create("personal") {
            dimension = "distribution"
            isDefault = true
            // 自用版才嵌入本机凭证，用于首次配置表单预填。
            buildConfigField("String", "DOUBAO_ASR_APP_ID", credentialBuildConfigValue("appId"))
            buildConfigField("String", "DOUBAO_ASR_ACCESS_TOKEN", credentialBuildConfigValue("accessToken"))
            buildConfigField("String", "DOUBAO_ARK_API_KEY", credentialBuildConfigValue("arkApiKey"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions {
        jvmTarget = "11"
    }
    androidResources {
        noCompress += "onnx"
    }
    buildFeatures {
        dataBinding = true
        buildConfig = true
    }
    lint {
        error += "StaticFieldLeak"
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("com.bytedance.speechengine:speechengine_tob:0.0.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    kapt(libs.androidx.room.compiler)
}
