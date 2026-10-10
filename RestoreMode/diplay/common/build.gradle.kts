plugins {
    id("com.android.library")
    alias(diplayLibs.plugins.kotlin.compose)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all { it.maxHeapSize = "1g" }
    }
}

dependencies {
    api(project(":diplay-shared"))
    implementation(platform(diplayLibs.androidx.compose.bom))
    implementation(diplayLibs.androidx.activity.compose)
    implementation(diplayLibs.androidx.compose.material3)
    implementation(diplayLibs.androidx.compose.ui)
    implementation(diplayLibs.androidx.compose.ui.graphics)
    implementation(diplayLibs.androidx.compose.ui.tooling.preview)
    implementation(diplayLibs.androidx.core.ktx)
    implementation(diplayLibs.androidx.lifecycle.runtime.ktx)
    implementation(diplayLibs.androidx.work.runtime)
    implementation(diplayLibs.androidx.media3.exoplayer)
    implementation(diplayLibs.androidx.media3.exoplayer.hls)
    implementation(diplayLibs.androidx.media3.ui)
    testImplementation(diplayLibs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(diplayLibs.jmdns)
}
