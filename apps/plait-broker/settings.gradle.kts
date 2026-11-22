pluginManagement {
    repositories {
        google { content { includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*") } }
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "plait-broker"
include(":broker", ":testclient", ":shield", ":scanner")

// The shared look (../plait-design): theme, tonal components and the generated icon set
includeBuild("../plait-design") {
    dependencySubstitution { substitute(module("app.plait:design")).using(project(":design")) }
}
