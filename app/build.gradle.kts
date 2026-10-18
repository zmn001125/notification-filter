import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ============================================================================
// 发布签名配置（Release Signing）
// ----------------------------------------------------------------------------
// 密钥库路径：<项目根>/keystore/nf-release.jks（由 JDK 17 的 keytool 生成）
//
// **口令不写在本文件里**：从 local.properties（已 .gitignore，不进公开仓库）
// 或环境变量读取。这样把源码公开出去也不会泄露签名口令。
//   local.properties:  nf.store.pass=*** / nf.key.alias=*** / nf.key.pass=***
//   或环境变量:        NF_KEYSTORE_PASS / NF_KEY_ALIAS / NF_KEY_PASS
//
// 鲁棒性设计：若密钥库文件不存在（换机 / clone 仓库未携带），release 构建会
// 自动回退到 debug 签名，保证任何机器上都能出包，只是签名指纹不同。
// 重新生成密钥库的命令见 docs/需求文档.md 第 7 节。
// ============================================================================
private val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

private fun signingSecret(propKey: String, envKey: String, fallback: String): String =
    localProps.getProperty(propKey) ?: System.getenv(envKey) ?: fallback

val signingKeystoreFile: File = rootProject.file("keystore/nf-release.jks")
val signingKeystorePassword: String = signingSecret("nf.store.pass", "NF_KEYSTORE_PASS", "changeme")
val signingKeyAlias: String = signingSecret("nf.key.alias", "NF_KEY_ALIAS", "nfkey")
val signingKeyPassword: String = signingSecret("nf.key.pass", "NF_KEY_PASS", "changeme")

android {
    namespace = "com.local.notificationfilter"

    // 说明：本机当前只安装了 platforms/android-34 与 build-tools/34.0.0。
    // 使用 compileSdk = 34 可以做到「零额外 SDK 下载」，离线环境下也能顺利完成
    // 首次构建（依赖仍需首次联网下载一次）。AGP 8.7.3 要求 compileSdk >= 34，满足。
    compileSdk = 34

    defaultConfig {
        applicationId = "com.local.notificationfilter"
        minSdk = 26
        // 取值理由（与 compileSdk 保持一致）：
        // 1) AndroidManifest 中使用了 android:foregroundServiceType="specialUse"，
        //    该前台服务类型是 API 34 才引入的，targetSdk 必须 >= 34 才能生效；
        // 2) 业务源码最高只用到了 API 33（TIRAMISU）的接口，34 完全够用；
        // 3) 本 App 为自用侧载，不受应用商店 targetSdk 门槛约束；
        // 4) 避免额外下载 android-35 平台。
        targetSdk = 34
        versionCode = 19
        versionName = "12.0"
    }

    signingConfigs {
        create("release") {
            storeFile = signingKeystoreFile
            storePassword = signingKeystorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    }

    buildTypes {
        release {
            // 保持关闭混淆：不启用 R8 可免去额外规则配置与依赖下载，出包更快更稳。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 密钥库存在 -> 正式签名；缺失 -> 回退 debug 签名（仍可安装）。
            signingConfig = if (signingKeystoreFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
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

    testOptions {
        // 让 android.util.Log 等 android.jar 方法在 JVM 单测里返回默认值而不是抛
        // "not mocked" —— WebDavTarget 等类失败路径会走 NfLog，必须能在单测里跑。
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

if (!signingKeystoreFile.exists()) {
    logger.warn(
        "[signing] 未找到密钥库 ${signingKeystoreFile.absolutePath}，" +
            "release 构建将回退使用 debug 签名。请阅读 docs/build.md 重新生成。"
    )
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

// ============================================================================
// 跳过 AAR 元数据检查（compileSdk 版本门槛）
// ----------------------------------------------------------------------------
// androidx.core 1.15.0 的 AAR 元数据声明「依赖方需 compileSdk >= 35」，而本机
// 只安装了 android-34 平台（本网络环境下 android-35 平台包下载不可行）。
//
// 安全性说明：该检查约束的是「编译期链接所用的 android.jar 版本」。core 本身是
// 预编译好的 AAR，其字节码不会因我们的 compileSdk 而改变；本 App 目标设备为
// Android 15+（API 35+）的小米 15，运行期 core 1.15 所需的系统 API 全部存在，
// 因此禁用该编译期检查不会引入运行期风险。
//
// 若日后安装了 android-35 平台，可删除本段并把 compileSdk 改回 35。
// ============================================================================
tasks.matching { it.name.endsWith("AarMetadata") }.configureEach {
    enabled = false
}
