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
        // NewPipe Extractor (YouTube trailer streams) is only published here.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.teamnewpipe")
                includeGroup("com.github.TeamNewPipe")
            }
        }
    }
}

rootProject.name = "MarqueeTV"
include(":app")
