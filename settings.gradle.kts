// 鸿胪寺 · 安卓工程
//
// ⚠ 依赖源必须用镜像：本机实测 maven.google.com / repo1.maven.org 被墙，dl.google.com 与
//    maven.aliyun.com 可用。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://dl.google.com/dl/android/maven2/")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://dl.google.com/dl/android/maven2/")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "Honglusi"
include(":app")
