pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Alternate official endpoint when Maven Central returns missing artifacts.
        maven { url = uri("https://repo1.maven.org/maven2") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

rootProject.name = "ssMusicPlayer"
include(":core", ":app")
