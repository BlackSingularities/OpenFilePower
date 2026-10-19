import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Wersja: plik VERSION (np. 0.2.0); CI podaje -PversionName/-PversionCode dla każdego wydania.
val baseVersion = rootProject.file("VERSION").readText().trim()
val appVersionName = (project.findProperty("versionName") as String?) ?: baseVersion
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Klucz wydania: w CI ze zmiennych środowiskowych, lokalnie z .secrets/ (poza repozytorium).
val localKey = rootProject.file(".secrets/release.jks")
val localPassword = rootProject.file(".secrets/signing-password.txt").takeIf { it.exists() }?.readText()?.trim()
val keyFile = System.getenv("SIGNING_KEY_PATH")?.let { file(it) } ?: localKey.takeIf { it.exists() && localPassword != null }
val hasReleaseKey = keyFile != null && keyFile.exists()

// SHA-256 certyfikatu, którym podpisywane są wydania. Aktualizator i CI sprawdzają zgodność.
val releaseCertSha256 = "470753b8716af4f6d59539aadacc0d9eadb17eabca4de18f6524a5ff2ae2fddb"

android {
    namespace = "pl.openfilepower"
    compileSdk = 36
    defaultConfig {
        applicationId = "pl.openfilepower"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPO", "\"BlackSingularities/OpenFilePower\"")
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCertSha256\"")
        buildConfigField("boolean", "UPDATES_ENABLED", "true")
        resValue("string", "app_name", "OpenFilePower")
    }
    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = keyFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: localPassword
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "openfilepower"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: localPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            // Bez klucza (np. fork) wydanie podpisuje się kluczem debug; CI odrzuca taki APK.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
        debug {
            // Osobny pakiet: wersja testowa nie koliduje z podpisanym wydaniem na tym samym telefonie.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "UPDATES_ENABLED", "false")
            resValue("string", "app_name", "OpenFilePower Debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { checkReleaseBuilds = false; disable += setOf("ScopedStorage", "QueryAllPackagesPermission", "ProtectedPermissions") }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "/META-INF/{AL2.0,LGPL2.1}") }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
