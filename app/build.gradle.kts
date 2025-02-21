plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.braulio.miaucam"
    compileSdk = 35
    // Consider using 34 as 35 might be a preview SDK at the moment of writing

    defaultConfig {
        applicationId = "com.braulio.miaucam"
        minSdk = 24
        targetSdk =
            35// Minimum SDK for MediaProjection and good USB support. Consider raising if necessary.
        // Match compileSdk for targetSdk
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true // Enable minification for release builds
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug { // Explicitly define debug build type for clarity, though defaults are usually fine
            isDebuggable = true // Ensure debuggable for debug builds
        }
    }
    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_17 // Recommended Java version for latest Android development
        targetCompatibility = JavaVersion.VERSION_17 // Match sourceCompatibility
    }
    kotlinOptions {
        jvmTarget = "17" // Match Java version
    }
    buildFeatures {
        compose = true // Enable Compose features
        viewBinding = false // Disable ViewBinding if you are fully using Compose, to reduce build time and app size. Enable if still using XML layouts.
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3" // Or the latest stable version of Compose compiler. Check project's libs.versions.toml or Gradle plugin versions.
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}" // Recommended for reducing app size and avoiding potential license conflicts
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.core.ktx) // Or latest stable version
    implementation(libs.androidx.core.splashscreen) // Or latest stable version
    implementation(libs.androidx.appcompat) // Or latest stable version
    implementation(libs.androidx.core.ktx) // Or latest stable version
    implementation(libs.androidx.lifecycle.runtime.ktx) // CORRECT - Using version catalog
}