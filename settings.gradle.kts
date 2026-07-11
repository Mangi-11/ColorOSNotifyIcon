pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            name = "GoogleMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            mavenContent { releasesOnly() }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            name = "GoogleMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            mavenContent { releasesOnly() }
        }
    }
}
plugins {
    id("com.highcapable.gropify") version "1.0.1"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
gropify {
    global {
        common {
            includeKeys("^project\\..*\$".toRegex())
            locations(GropifyLocation.RootProject)
        }
    }
    rootProject { common { isEnabled = false } }
}
rootProject.name = "ColorOSNotifyIcon"
include(":app")
