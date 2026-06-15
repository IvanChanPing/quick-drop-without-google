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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // libadb-android (self-ADB Wi-Fi)
    }
}

rootProject.name = "Bada"

include(":app")
include(":radio-helper")
include(":service-android")
include(":discovery-android")
include(":core-protocol")
include(":core-protocol-test")
