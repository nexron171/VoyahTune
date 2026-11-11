pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "VoyahTuneUpdater"

include(":updater-client")
project(":updater-client").projectDir = file("client")
include(":updater-ui")
project(":updater-ui").projectDir = file("ui")
include(":app")
