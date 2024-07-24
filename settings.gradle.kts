pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

private val javaVersion = JavaVersion.current()

require(javaVersion.isCompatibleWith(JavaVersion.VERSION_17)) {
    "Project requires Java 17 or higher, but found ${javaVersion.majorVersion}."
}

rootProject.name = "pkl-lsp"
