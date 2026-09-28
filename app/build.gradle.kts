plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.g1project.tmcalc"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.g1project.tmcalc"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 개인 사이드로드용: 디버그 키로 서명해 바로 설치 가능하게 한다
            signingConfig = signingConfigs.getByName("debug")
            // 폰용 CPU만 포함해 용량을 줄인다 (디버그 빌드는 에뮬레이터용 x86_64 포함)
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
