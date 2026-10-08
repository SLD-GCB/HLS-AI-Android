import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 从 local.properties 读发布签名信息（没有就退化成不签名，仍能编译）
val 发布签名 = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val 有发布签名 = 发布签名.getProperty("RELEASE_STORE_FILE") != null

android {
    namespace = "com.honglusi.tavern"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.honglusi.tavern"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.4.2"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    signingConfigs {
        if (有发布签名) {
            create("release") {
                storeFile = rootProject.file(发布签名.getProperty("RELEASE_STORE_FILE"))
                storePassword = 发布签名.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = 发布签名.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = 发布签名.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    // 本地模型层 = llama-android 那个成熟绑定的源码（它的 JNI + Kotlin API），我们只负责接进 App。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
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
            if (有发布签名) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // 源码在工程根的「后端/」「UI/」（我们的代码）和「模型层/」（llama-android 成熟绑定）里
    sourceSets {
        getByName("main") {
            java.srcDirs(
                "${rootProject.projectDir}/后端",
                "${rootProject.projectDir}/UI",
                "${rootProject.projectDir}/模型层",
            )
            // 「打包时把配置一起带上」：工程根的 预置数据/ 当作 assets 源，
            // 里面的 配置/、模型/ 会原样进 APK，首次启动由 后端.预置数据 展开到数据目录。
            assets.srcDirs("src/main/assets", "${rootProject.projectDir}/预置数据")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
}
