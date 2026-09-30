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
        versionCode = 4
        versionName = "1.3"
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
            // 폰(ARM) + 앱플레이어(PC, x86_64). 기기는 자기 CPU용 하나만 불러오므로 속도/메모리는 그대로, 용량만 약 11MB 증가
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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
