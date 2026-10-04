plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "big.town.runyn"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "big.town.runyn"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        providers.gradleProperty("voyahReleaseVersion").orNull?.let { release ->
            val parts = release.substringBefore('-').substringBefore('+').split('.').map { it.toInt() }
            require(parts.size == 3 && parts.all { it in 0..999 }) {
                "voyahReleaseVersion must have a major.minor.patch base"
            }
            versionCode = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
            versionName = release
        }
        require(providers.gradleProperty("voyahInfrastructure").orElse("pi").get() == "pi") {
            "RunYN belongs to PI infrastructure"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Preserve the existing local signing identity used by the other APKs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
