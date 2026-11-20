import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProps = Properties().apply {
    val keystorePropsFile = rootProject.file("keystore.properties")
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

fun signEnv(key: String): String =
    keystoreProps.getProperty(key) ?: System.getenv(key) ?: ""

val hasSigning = signEnv("KEYSTORE_BASE64").isNotBlank() || signEnv("KEYSTORE_FILE").isNotBlank()
val compressNativeLibs = providers.gradleProperty("compressNativeLibs")
    .map(String::toBoolean)
    .orElse(true)
    .get()

android {
    namespace = "com.usquebox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.usquebox"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = (project.findProperty("versionName") as? String) ?: "1.0.0"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                val ksFile = signEnv("KEYSTORE_FILE")
                storeFile = if (ksFile.isNotBlank()) rootProject.file(ksFile) else rootProject.file("usquebox-release.jks")
                storePassword = signEnv("KEYSTORE_PASS")
                keyAlias = signEnv("ALIAS_NAME")
                keyPassword = signEnv("ALIAS_PASS")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Compress Go's native library in the APK. Android extracts it
            // before loading, while the uncompressed mode remains available
            // for diagnostics and compatibility testing.
            useLegacyPackaging = compressNativeLibs
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val suffix = if (hasSigning) "" else "-unsigned"
            output.outputFileName = "usquebox-${variant.versionName}-${variant.buildType.name}${suffix}.apk"
        }
    }
}

dependencies {
    implementation(files("libs/usque.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
