pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // 允许 Gradle 自动下载所需 JDK（本机无 JDK 21，由 toolchain 自动获取）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "i18n-jetbrains-plugin"
