import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val nineEsimKeystorePropertiesFile = rootProject.file("nineesim-keystore.properties")
val nineEsimKeystoreProperties = Properties().apply {
    if (nineEsimKeystorePropertiesFile.isFile) {
        nineEsimKeystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "app.hyperlpa"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.hyperlpa"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(
                    requireNotNull(keystoreProperties.getProperty("storeFile")) {
                        "storeFile is missing from keystore.properties"
                    },
                )
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword")) {
                    "storePassword is missing from keystore.properties"
                }
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias")) {
                    "keyAlias is missing from keystore.properties"
                }
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword")) {
                    "keyPassword is missing from keystore.properties"
                }
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        if (nineEsimKeystorePropertiesFile.isFile) {
            create("nineEsimRelease") {
                storeFile = rootProject.file(
                    requireNotNull(nineEsimKeystoreProperties.getProperty("storeFile")) {
                        "storeFile is missing from nineesim-keystore.properties"
                    },
                )
                storePassword = requireNotNull(nineEsimKeystoreProperties.getProperty("storePassword")) {
                    "storePassword is missing from nineesim-keystore.properties"
                }
                keyAlias = requireNotNull(nineEsimKeystoreProperties.getProperty("keyAlias")) {
                    "keyAlias is missing from nineesim-keystore.properties"
                }
                keyPassword = requireNotNull(nineEsimKeystoreProperties.getProperty("keyPassword")) {
                    "keyPassword is missing from nineesim-keystore.properties"
                }
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        if (nineEsimKeystorePropertiesFile.isFile) {
            create("nineEsimRelease") {
                initWith(getByName("release"))
                signingConfig = signingConfigs.getByName("nineEsimRelease")
                versionNameSuffix = "-9esim"
                matchingFallbacks += listOf("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":libs:lpac-jni"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3.ui)

    implementation(libs.hiddenapibypass)
    implementation(libs.quickie.bundled)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
