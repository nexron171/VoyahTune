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

include(":updater-ui")
project(":updater-ui").projectDir = file("../Updater/ui")

include(":diplay-common", ":diplay-shared")
project(":diplay-common").projectDir = file("diplay/common")
project(":diplay-shared").projectDir = file("diplay/shared")
