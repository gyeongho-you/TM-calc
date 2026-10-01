import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 배포용 서명 키 설정 (레포에 넣지 않음). 없으면 디버그 키로 서명해 누구나 빌드는 할 수 있다.
// 형식: storeFile=..., storePassword=..., keyAlias=..., keyPassword=...
val releaseKeyProps = File(System.getProperty("user.home"), ".android/tmcalc-keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "net.g1project.tmcalc"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.g1project.tmcalc"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        if (releaseKeyProps != null) create("release") {
            storeFile = file(releaseKeyProps.getProperty("storeFile"))
            storePassword = releaseKeyProps.getProperty("storePassword")
            keyAlias = releaseKeyProps.getProperty("keyAlias")
            keyPassword = releaseKeyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 배포용 키가 있으면 그걸로, 없으면 디버그 키로 서명 (다른 사람이 만든 APK로 덮어쓰기 방지)
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // 폰용 CPU만 포함해 용량을 줄인다 (디버그 빌드는 에뮬레이터용 x86_64 포함). 앱플레이어는 PC 프로그램(desktop)으로 지원
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
