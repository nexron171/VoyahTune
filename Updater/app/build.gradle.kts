plugins {
    id("com.android.application")
}

apply(from = rootProject.file("../SharedAndroid/release-versions.gradle"))
val voyahReleaseVersion: Provider<String> by extra

android {
    namespace = "ru.big.town.updater"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.big.town.updater"
        minSdk = 30
        targetSdk = 35
        versionName = voyahReleaseVersion.get()
        val parts = versionName!!.substringBefore('-').substringBefore('+').split('.').map(String::toInt)
        require(parts.size == 3 && parts.all { it in 0..999 })
        versionCode = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":updater-ui"))
}
