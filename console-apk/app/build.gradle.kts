plugins {
    id("com.android.application")
}

android {
    namespace = "com.yuntai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yuntai"
        minSdk = 24
        targetSdk = 36
        versionCode = 31
        versionName = "1.0.6"
    }

    signingConfigs {
        create("release") {
            // v1.0.12 修复：优先使用仓库内固定签名密钥，保证每次构建签名一致，
            // 老版本控制台才能通过覆盖安装完成自更新（否则签名不同会被系统拒绝）。
            // 增强：密钥缺失时自动回退仓库内置 debug.keystore（CI 无 secrets 也能出可安装包）
            val repoKey = rootProject.file("../signing/lzdz-release.keystore")
            val envKey = System.getenv("CONSOLE_KEYSTORE_PATH")
            val envFile = envKey?.let { p -> if (File(p).isAbsolute) File(p) else file("${rootProject.projectDir}/${p.removePrefix("./")}") }
            val dbgKey = rootProject.file("../debug.keystore")
            val chosen = when {
                envFile != null && envFile.exists() -> envFile
                repoKey.exists() -> repoKey
                else -> dbgKey
            }
            val useDebugFallback = (chosen == dbgKey)
            if (useDebugFallback) println("==> 警告：未找到正式签名密钥，回退使用 debug.keystore（产物为调试签名）")
            storeFile = chosen
            storePassword = if (useDebugFallback) "android" else (System.getenv("CONSOLE_STORE_PASSWORD") ?: "lzdz123456")
            keyAlias = if (useDebugFallback) "androiddebugkey" else (System.getenv("CONSOLE_KEY_ALIAS") ?: "lzdz-release")
            keyPassword = if (useDebugFallback) "android" else (System.getenv("CONSOLE_KEY_PASSWORD") ?: "lzdz123456")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // FileProvider（控制台自更新安装 APK 用）
    implementation("androidx.core:core:1.13.1")
}
