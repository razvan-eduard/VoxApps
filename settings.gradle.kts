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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "VoxApps"
include(":vox-commander")
include(":vox-notes")
include(":vox-vision")
include(":vox-expenses")
include(":vox-hub")
include(":vox-calendar")
include(":core:attachments")
include(":core:design")
include(":core:onboarding")
include(":core:calendar")
include(":core:apppicker")
include(":core:location")
include(":core:backup")
include(":core:services")
include(":core:widget")
include(":core:testing")
include(":core:ipc")
include(":core:voxconnect")
include(":core:identity")
include(":core:logging")
include(":core:nativelibs")
include(":core:textmatch")
include(":core:wakeword")
include(":core:schema-annotations")
include(":core:schema-processor")
include(":core:datahygiene")
include(":core:docread")
include(":core:fieldmemory")
include(":core:audio")
include(":core:preferences")
include(":core:recordflow")
include(":core:suggestions")
include(":vendor:ppocr-sdk")
include(":vendor:docquad-sdk")
