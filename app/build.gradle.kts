import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.eta.autoledger"

    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.eta.autoledger"
        minSdk = 27
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.0"
    }

    signingConfigs {
        // 存在 keystore.properties 时启用真实 release 签名；否则回退 debug（不阻塞本地构建）
        val ksFile = rootProject.file("keystore.properties")
        if (ksFile.exists()) {
            val ks = Properties().apply { ksFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 调试包允许明文 HTTP，方便连本地 / 自建 WebDAV、AI 网关
            manifestPlaceholders["usesCleartext"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (rootProject.file("keystore.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // 发布包禁止全局明文流量：凭据与账单不再经明文 HTTP 传输
            manifestPlaceholders["usesCleartext"] = "false"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.5-beta01")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.5-beta01")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.5-beta01")
    // 纯 Compose 图表库（无 AndroidView 互操作），替换报表页三套自绘 Canvas
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // 敏感凭据（AI Key / WebDAV 密码）加密落盘。见 SecurePrefs.kt
    implementation("androidx.security:security-crypto:1.1.0")

    // 不可变集合：给 Compose 提供「稳定」的类型保证，配合 @Immutable 让
    // List 类型的参数能被跳过重组（普通 List 是接口，编译器无法证明不被修改）。
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    ksp("androidx.room:room-compiler:2.8.5")

    compileOnly("io.github.libxposed:api:102.0.0")
}