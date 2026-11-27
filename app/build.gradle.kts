import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val defaultVersionCode = 1000
val defaultVersionName = "1.0.0"
val autoLedgerVersionCode = providers.gradleProperty("autoLedgerVersionCode")
    .orElse(providers.environmentVariable("AUTO_LEDGER_VERSION_CODE"))
    .map { value -> value.toIntOrNull() ?: error("autoLedgerVersionCode 必须是整数") }
val autoLedgerVersionName = providers.gradleProperty("autoLedgerVersionName")
    .orElse(providers.environmentVariable("AUTO_LEDGER_VERSION_NAME"))

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
        versionCode = autoLedgerVersionCode.orNull ?: defaultVersionCode
        versionName = autoLedgerVersionName.orNull ?: defaultVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testOptions {
        // 纯 JVM 单测里不碰真实 Android 运行时：CsvImporter 这类纯解析逻辑
        // 只用到 String/Regex/Calendar，未 stub 的 android.* 调用返回默认值即可。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.5-beta03")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.5-beta03")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.5-beta03")
    // 纯 Compose 图表库（无 AndroidView 互操作），替换报表页三套自绘 Canvas
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // 敏感凭据（AI Key / WebDAV 密码）加密落盘。见 SecurePrefs.kt
    implementation("androidx.security:security-crypto:1.1.0")

    // 不可变集合：给 Compose 提供「稳定」的类型保证，配合 @Immutable 让
    // List 类型的参数能被跳过重组（普通 List 是接口，编译器无法证明不被修改）。
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    ksp("androidx.room:room-compiler:2.8.5")

    // 解析器单测：CSV 表头定位、金额防护、编码探测都靠断言守住
    testImplementation("junit:junit:4.13.2")
    // Room 迁移测试：真 SQLite（内存库）跑一遍 MIGRATION_3_4 的 DDL。
    // 不用 room-testing + Robolectric —— 那要拖进整套 Android 运行时，
    // 而迁移里真正会写错的只有 DDL 本身，纯 SQL 就能验。
    testImplementation("org.xerial:sqlite-jdbc:3.54.1.0")
    // 单测里的 org.json 是 android.jar 的桩实现（isReturnDefaultValues 下方法返回
    // null/0），JSONObject().toString() 会返回 null。补一份真实现，让规则 JSON 的
    // 拼装与解析在 JVM 上真的跑起来。只在测试配置里，不进 APK。
    testImplementation("org.json:json:20260814")

    // Compose 图形层截图与 FileProvider URI 的仪器测试。
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.13.0-rc01")

    compileOnly("io.github.libxposed:api:102.0.0")
}
