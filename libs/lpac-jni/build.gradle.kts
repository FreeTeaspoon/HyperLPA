plugins {
    id("com.android.library")
}

android {
    namespace = "net.typeblog.lpac_jni"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")

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
    testImplementation("junit:junit:4.13.2")
}

val derutilHostTestBinary = layout.buildDirectory.file("host-native-tests/derutil-hardening-test")
val profileParserHostTestBinary = layout.buildDirectory.file("host-native-tests/profile-parser-hardening-test")
val compileDerutilHostTest = tasks.register<Exec>("compileDerutilHostTest") {
    val testSource = layout.projectDirectory.file("src/test/native/derutil_hardening_test.c")
    val derutilSource = layout.projectDirectory.file("src/main/jni/lpac/euicc/derutil.c")
    val includeDirectory = layout.projectDirectory.dir("src/main/jni/lpac/euicc")
    inputs.files(testSource, derutilSource, includeDirectory.file("derutil.h"))
    outputs.file(derutilHostTestBinary)
    doFirst { derutilHostTestBinary.get().asFile.parentFile.mkdirs() }
    commandLine(
        providers.environmentVariable("CC").orElse("cc").get(),
        "-std=c11",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-fsanitize=address,undefined",
        "-fno-omit-frame-pointer",
        "-I${includeDirectory.asFile.absolutePath}",
        testSource.asFile.absolutePath,
        derutilSource.asFile.absolutePath,
        "-o",
        derutilHostTestBinary.get().asFile.absolutePath,
    )
}

val compileProfileParserHostTest = tasks.register<Exec>("compileProfileParserHostTest") {
    val testSource = layout.projectDirectory.file("src/test/native/profile_parser_hardening_test.c")
    val nativeDirectory = layout.projectDirectory.dir("src/main/jni/lpac/euicc")
    val nativeSources = listOf(
        "es10c.c",
        "es10c_ex.c",
        "es8p.c",
        "derutil.c",
        "base64.c",
        "hexutil.c",
    ).map(nativeDirectory::file)
    val nativeHeaders = listOf(
        "es10c.h",
        "es10c_ex.h",
        "es8p.h",
        "euicc.h",
        "euicc.private.h",
        "derutil.h",
        "base64.h",
        "hexutil.h",
        "rsp_limits.h",
    ).map(nativeDirectory::file)
    inputs.files(testSource, nativeSources, nativeHeaders)
    outputs.file(profileParserHostTestBinary)
    doFirst { profileParserHostTestBinary.get().asFile.parentFile.mkdirs() }
    commandLine(
        providers.environmentVariable("CC").orElse("cc").get(),
        "-std=gnu11",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-fsanitize=address,undefined",
        "-fno-omit-frame-pointer",
        "-I${nativeDirectory.asFile.absolutePath}",
        testSource.asFile.absolutePath,
        *nativeSources.map { it.asFile.absolutePath }.toTypedArray(),
        "-o",
        profileParserHostTestBinary.get().asFile.absolutePath,
    )
}

val testProfileParserHardening = tasks.register<Exec>("testProfileParserHardening") {
    dependsOn(compileProfileParserHostTest)
    commandLine(profileParserHostTestBinary.get().asFile.absolutePath)
}

val testNativeHardening = tasks.register<Exec>("testNativeHardening") {
    dependsOn(compileDerutilHostTest, testProfileParserHardening)
    commandLine(derutilHostTestBinary.get().asFile.absolutePath)
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(testNativeHardening)
}
