plugins {
    id("com.android.library")
}

android {
    namespace = "net.typeblog.lpac_jni"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            ndkBuild {
                cFlags(
                    "-fmacro-prefix-map=${project.projectDir}=/fake/path/",
                    "-fdebug-prefix-map=${project.projectDir}=/fake/path/",
                    "-ffile-prefix-map=${project.projectDir}=/fake/path/"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    externalNativeBuild {
        ndkBuild {
            path("src/main/jni/lpac-jni.mk")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
