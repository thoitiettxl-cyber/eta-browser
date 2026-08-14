plugins {
    alias(libs.plugins.android.application)
}

val releaseKeystorePath = providers.environmentVariable("ETA_RELEASE_KEYSTORE_PATH")
val releaseStorePassword = providers.environmentVariable("ETA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ETA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ETA_RELEASE_KEY_PASSWORD")
val releaseSigningInputs = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningInputs.all { it.isPresent }

if (releaseSigningInputs.any { it.isPresent } && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is only allowed when all ETA_RELEASE_* environment variables are set.",
    )
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

android {
    namespace = "com.thoitiettxl.eta"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thoitiettxl.eta"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
}
