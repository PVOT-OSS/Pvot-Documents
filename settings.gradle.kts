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

rootProject.name = "PvotDocuments"
include(":app")
include(":pdf-engine")

// PvotLib builds from the submodule with its own build-logic and version catalog;
// com.prauga.pvot:design-system resolves to its :design-system project.
includeBuild("PvotLib")
