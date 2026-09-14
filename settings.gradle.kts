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
    }
}

rootProject.name = "Ferngeist"
include(":app")
include(":core:model")
include(":core:common")
include(":data:database")
include(":acp-bridge")
include(":gateway-client")
include(":feature:serverlist")
include(":feature:sessionlist")
include(":feature:chat")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
