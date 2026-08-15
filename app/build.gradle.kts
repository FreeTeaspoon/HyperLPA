import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.Base64
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

fun Properties.requiredSigningValue(name: String, source: String): String {
    getProperty("${name}Base64")?.let { encoded ->
        return try {
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw GradleException("$name is not valid Base64 in $source", error)
        }.also { value ->
            require(value.isNotEmpty()) { "$name must not be empty in $source" }
        }
    }
    return requireNotNull(getProperty(name)) { "$name is missing from $source" }
        .also { value -> require(value.isNotEmpty()) { "$name must not be empty in $source" } }
}

fun Properties.loadConfiguredCertificate(source: String): Certificate {
    val storeFile = rootProject.file(
        requireNotNull(getProperty("storeFile")) { "storeFile is missing from $source" },
    )
    require(storeFile.isFile) { "Signing keystore does not exist: ${storeFile.absolutePath}" }
    val alias = requiredSigningValue("keyAlias", source)
    val password = requiredSigningValue("storePassword", source).toCharArray()
    return try {
        listOf(KeyStore.getDefaultType(), "JKS", "PKCS12")
            .distinct()
            .firstNotNullOfOrNull { storeType ->
                runCatching {
                    val keyStore = KeyStore.getInstance(storeType).apply {
                        storeFile.inputStream().use { input -> load(input, password) }
                    }
                    requireNotNull(keyStore.getCertificate(alias)) {
                        "keyAlias does not identify a certificate in $source"
                    }
                }.getOrNull()
            }
            ?: throw GradleException("Could not read the signing certificate from $source")
    } finally {
        password.fill('\u0000')
    }
}

fun loadExpectedCertificate(file: File): Certificate {
    require(file.isFile) { "Expected signing certificate is missing: ${file.absolutePath}" }
    return file.inputStream().use { input ->
        CertificateFactory.getInstance("X.509").generateCertificate(input)
    }
}

fun verifyConfiguredCertificate(
    properties: Properties,
    source: String,
    expectedCertificateFile: File,
) {
    val actual = MessageDigest.getInstance("SHA-256")
        .digest(properties.loadConfiguredCertificate(source).encoded)
    val expected = MessageDigest.getInstance("SHA-256")
        .digest(loadExpectedCertificate(expectedCertificateFile).encoded)
    if (!MessageDigest.isEqual(actual, expected)) {
        throw GradleException(
            "$source does not contain the signing certificate pinned in " +
                expectedCertificateFile.relativeTo(rootProject.projectDir).path,
        )
    }
}
val isBuildingAppBundle = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':').contains("bundle", ignoreCase = true)
}
val appVersionCode = providers.gradleProperty("hyperLpaVersionCode")
    .orElse(providers.environmentVariable("HYPERLPA_VERSION_CODE"))
    .orElse("1")
    .get()
    .toInt()
    .also { require(it > 0) { "hyperLpaVersionCode must be positive" } }
val appVersionName = providers.gradleProperty("hyperLpaVersionName")
    .orElse(providers.environmentVariable("HYPERLPA_VERSION_NAME"))
    .orElse("0.1.0")
    .get()
    .also { require(it.isNotBlank()) { "hyperLpaVersionName must not be blank" } }

val appProjectPath = project.path
gradle.taskGraph.whenReady {
    fun includesPackagingVariant(variantName: String): Boolean {
        val packagingTasks = setOf(
            "assemble$variantName",
            "bundle$variantName",
            "package$variantName",
            "package${variantName}Bundle",
            "sign${variantName}Bundle",
        )
        return allTasks.any { task ->
            task.path.startsWith("$appProjectPath:") && task.name in packagingTasks
        }
    }

    // Inspect the resolved graph rather than only command-line task names. This
    // also catches aggregate invocations such as `build` and `assemble`.
    if (includesPackagingVariant("Release") && !nineEsimKeystorePropertiesFile.isFile) {
        throw GradleException(
            "The standard release requires the ignored nineesim-keystore.properties file.",
        )
    }
    if (includesPackagingVariant("PrivilegedRelease") && !keystorePropertiesFile.isFile) {
        throw GradleException(
            "The privileged private release requires the ignored keystore.properties file. " +
                "Use assemblePrivilegedDebug for a development build.",
        )
    }
    if (includesPackagingVariant("NineEsimRelease") && !nineEsimKeystorePropertiesFile.isFile) {
        throw GradleException(
            "The 9eSIM community build requires the ignored nineesim-keystore.properties file.",
        )
    }
    if (includesPackagingVariant("PrivilegedRelease")) {
        verifyConfiguredCertificate(
            properties = keystoreProperties,
            source = "keystore.properties",
            expectedCertificateFile = rootProject.file("signing/hyperlpa-release-cert.pem"),
        )
    }
    if (includesPackagingVariant("Release") || includesPackagingVariant("NineEsimRelease")) {
        verifyConfiguredCertificate(
            properties = nineEsimKeystoreProperties,
            source = "nineesim-keystore.properties",
            expectedCertificateFile = rootProject.file("signing/9esim-community-cert.pem"),
        )
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
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HAS_PRIVILEGED_TELEPHONY", "false")
    }

    splits {
        abi {
            isEnable = !isBuildingAppBundle
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
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
                storePassword = keystoreProperties.requiredSigningValue("storePassword", "keystore.properties")
                keyAlias = keystoreProperties.requiredSigningValue("keyAlias", "keystore.properties")
                keyPassword = keystoreProperties.requiredSigningValue("keyPassword", "keystore.properties")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
        if (nineEsimKeystorePropertiesFile.isFile) {
            create("nineEsimRelease") {
                storeFile = rootProject.file(
                    requireNotNull(nineEsimKeystoreProperties.getProperty("storeFile")) {
                        "storeFile is missing from nineesim-keystore.properties"
                    },
                )
                storePassword = nineEsimKeystoreProperties.requiredSigningValue(
                    "storePassword",
                    "nineesim-keystore.properties",
                )
                keyAlias = nineEsimKeystoreProperties.requiredSigningValue(
                    "keyAlias",
                    "nineesim-keystore.properties",
                )
                keyPassword = nineEsimKeystoreProperties.requiredSigningValue(
                    "keyPassword",
                    "nineesim-keystore.properties",
                )
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("nineEsimRelease")
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("nineEsimRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".nineesim"
            versionNameSuffix = "-9esim"
            signingConfig = signingConfigs.findByName("nineEsimRelease")
            matchingFallbacks += listOf("release")
        }
        create("privilegedDebug") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".privileged.debug"
            versionNameSuffix = "-privileged-debug"
            buildConfigField("boolean", "HAS_PRIVILEGED_TELEPHONY", "true")
            matchingFallbacks += listOf("debug")
        }
        create("privilegedRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".privileged"
            versionNameSuffix = "-privileged"
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("boolean", "HAS_PRIVILEGED_TELEPHONY", "true")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
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
    implementation(libs.androidx.navigationevent)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)

    implementation(libs.hiddenapibypass)
    implementation(libs.quickie.bundled)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.libphonenumber)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
