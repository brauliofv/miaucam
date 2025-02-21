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
        google() // Google's Maven repository (for AndroidX and Play Services)
        mavenCentral() // Maven Central repository (for other Java/Kotlin libraries)
        jcenter() // JCenter repository (legacy, but still might be needed for some older libraries - consider removing if not necessary)
        maven { url = uri("https://jitpack.io") } // JitPack (if you are using any JitPack dependencies)
        // Add any other repositories your project needs
    }
}

rootProject.name = "MIAUCAM"
include(":app")
