buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Keep standalone browser compilation aligned with the Kotlin version used by the extracted source.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
