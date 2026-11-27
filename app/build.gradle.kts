plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "local.checklist"
    compileSdk = 34
    // 钉住 build-tools，与 CI 里安装的版本一致；否则 AGP 默认会去找别的版本
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "local.checklist"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        // 改版本号时同步改 AppInfo 里的常量说明（版本由 BuildConfig.VERSION_NAME 注入前端）
        versionName = "0.1.5"
        // 单架构够用：WebView 是系统组件，APK 内没有原生库
    }

    buildFeatures {
        // 前端 index.html 的 __APP_VERSION__ 由服务端用 BuildConfig.VERSION_NAME 替换
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 首版刻意不启 R8：本地无法验证混淆后的行为，先保证"打出来就能用"。
            // 回归通过后把下面两行改成 true，APK 约再小一半。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 侧载自用：用 debug keystore 签名，装得上、能直接用。
            // 注意：CI 每次生成的 debug keystore 不同，覆盖安装前需先卸载。
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

    // 单测只跑纯 JVM 逻辑，不需要打包资源和 lint 阻断
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += listOf("META-INF/*.kotlin_module", "META-INF/*.version")
        }
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation("junit:junit:4.13.2")
}
