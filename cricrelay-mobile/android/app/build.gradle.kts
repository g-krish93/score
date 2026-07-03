import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// CI passes -PfirebaseEnabled=true together with the decoded google-services.json so the
// Crashlytics wiring is explicit, not inferred; local builds fall back to the file check so
// a dropped-in config still lights up Firebase without extra flags.
val firebaseEnabled: Boolean =
    (findProperty("firebaseEnabled") as? String)?.toBooleanStrictOrNull()
        ?: file("google-services.json").exists()

android {
    namespace = "uk.co.cricrelay.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.co.cricrelay.stream"
        minSdk = 24
        targetSdk = 35
        versionCode = 27
        versionName = "2.0.4"
    }

    signingConfigs {
        create("release") {
            val keystoreBase64 = System.getenv("ANDROID_KEYSTORE_BASE64")
            val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            val storePassword = System.getenv("ANDROID_STORE_PASSWORD")
            if (!keystoreBase64.isNullOrBlank() &&
                !keyAlias.isNullOrBlank() &&
                !keyPassword.isNullOrBlank() &&
                !storePassword.isNullOrBlank()
            ) {
                val keystoreFile = file("release.keystore")
                if (!keystoreFile.exists()) {
                    keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreBase64.trim()))
                }
                storeFile = keystoreFile
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                this.storePassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Inert while minify is off, but keeps the RootEncoder reflection targets
            // (Camera2Controls) safe the day R8 is enabled.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseConfig = signingConfigs.findByName("release")
            signingConfig = if (releaseConfig?.storeFile?.exists() == true) {
                releaseConfig
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // One :app:lintDebug invocation analyzes every local module the app depends on,
        // so CI needs a single lint task instead of eight.
        checkDependencies = true
        // Pre-existing findings live in the baseline; the gate only fails on NEW issues.
        baseline = file("lint-baseline.xml")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:home"))
    implementation(project(":feature:studio"))
    implementation(project(":feature:scoring"))
    implementation(project(":streaming"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    if (firebaseEnabled) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics)
        implementation(libs.firebase.analytics)
    }

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

if (firebaseEnabled) {
    check(file("google-services.json").exists()) {
        "firebaseEnabled=true but android/app/google-services.json is missing — " +
            "decode the GOOGLE_SERVICES_JSON secret before building."
    }
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}
