pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    versionCatalogs {
        create("diplayLibs") { from(files("diplay/gradle/libs.versions.toml")) }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RestoreMode"
include(":app")

include(":updater-client")
project(":updater-client").projectDir = file("../Updater/client")

include(":diplay-common", ":diplay-shared")
project(":diplay-common").projectDir = file("diplay/common")
project(":diplay-shared").projectDir = file("diplay/shared")
