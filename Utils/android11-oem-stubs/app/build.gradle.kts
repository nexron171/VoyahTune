plugins {
    id("com.android.application")
}

android {
    namespace = "qa.voyahtune.oemstub"
    compileSdk = 30

    defaultConfig {
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0-stub"
    }

    flavorDimensions += "oemTarget"
    productFlavors {
        create("launcher") {
            dimension = "oemTarget"
            applicationId = "com.qinggan.app.launcher"
            manifestPlaceholders["targetProcess"] = applicationId!!
            manifestPlaceholders["stubLabel"] = "VoyahTune launcher stub"
        }
        create("systemservice") {
            dimension = "oemTarget"
            applicationId = "com.qinggan.systemservice"
            manifestPlaceholders["targetProcess"] = applicationId!!
            manifestPlaceholders["stubLabel"] = "VoyahTune system service stub"
        }
        create("qgime") {
            dimension = "oemTarget"
            applicationId = "com.qinggan.app.qgime"
            manifestPlaceholders["targetProcess"] = applicationId!!
            manifestPlaceholders["stubLabel"] = "VoyahTune keyboard stub"
        }
        create("vehiclesetting") {
            dimension = "oemTarget"
            applicationId = "com.qinggan.app.vehiclesetting"
            manifestPlaceholders["targetProcess"] = applicationId!!
            manifestPlaceholders["stubLabel"] = "VoyahTune vehicle settings stub"
        }
        create("keymanager") {
            dimension = "oemTarget"
            applicationId = "com.qinggan.keymanager.service"
            manifestPlaceholders["targetProcess"] = applicationId!!
            manifestPlaceholders["stubLabel"] = "VoyahTune key manager stub"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
