import com.android.build.api.dsl.SettingsExtension

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.settings") version "9.2.1"
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "kapovpn-android"

include(":tunnel")
include(":ui")

configure<SettingsExtension> {
    // Android 16 (API 36). New apps/updates must target 36 by Aug 31, 2026.
    // Install "Android 16 (API 36)" SDK Platform + Build-Tools 36.0.0 via
    // Android Studio > SDK Manager, then rebuild.
    buildToolsVersion = "36.0.0"
    compileSdk = 36
    minSdk = 24
    // NDK r27+ links native libraries with 16 KB page alignment by default,
    // which Google Play now requires. r26 aligned to 4 KB and fails the check.
    // Install this NDK via Android Studio > SDK Manager > SDK Tools > NDK.
    ndkVersion = "27.2.12479018"
}
