pluginManagement {
    repositories {
        maven { url = uri("https://chaquo.com/maven") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://chaquo.com/maven") }
        google()
        mavenCentral()
    }
}

rootProject.name = "GaitDashboard"
include(":app")
