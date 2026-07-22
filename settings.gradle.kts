pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Tether"
include(":app")
include(":dhizuku-api")
include(":hidden-api")

project(":dhizuku-api").projectDir = file("dhizuku-api/dhizuku-api")
project(":hidden-api").projectDir = file("dhizuku-api/hidden-api")