import java.util.Properties

/**
 * Signing details are kept outside the repository, so the key and its passwords are
 * never published. Copy keystore.properties.example to keystore.properties and fill
 * it in, or set the MILELOG_* environment variables. Without either, debug builds
 * still work and the release build simply comes out unsigned.
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, envName: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(envName)

val releaseKeystore: File? = signingValue("storeFile", "MILELOG_KEYSTORE")
    ?.let { File(it) }
    ?.takeIf { it.exists() }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.milelog"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.milelog"
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "2.6"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            releaseKeystore?.let { key ->
                storeFile = key
                storePassword = signingValue("storePassword", "MILELOG_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MILELOG_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "MILELOG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 strips the unused half of the icon set; without it the APK is 50 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures { compose = true }

    // Room writes the schema here so migrations can be written against a real v1.
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.preference:preference-ktx:1.2.1")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
