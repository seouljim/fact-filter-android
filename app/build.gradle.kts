plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.factfilter.probe"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.factfilter"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // 서버 접근 열쇠. 저장소(공개)에 적지 않고 GitHub Actions 의 비밀 값에서 받아 빌드에 넣는다.
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val appKey = (System.getenv("APP_ACCESS_KEY") ?: "").replace("\\", "").replace("\"", "")
        buildConfigField("String", "APP_KEY", "\"$appKey\"")
    }

    // 빌드할 때마다 서명이 바뀌면 업데이트 설치가 안 되므로, 저장소에 넣어 둔 시험용 키로 고정한다.
    // (배포용 키가 아니다. 플레이 스토어에 올릴 때는 별도의 비공개 키를 쓴다)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
