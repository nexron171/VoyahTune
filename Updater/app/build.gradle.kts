plugins { id("com.android.application") }
android {
    namespace = "ru.big.town.updater"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.big.town.updater"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":updater-ui"))
}
