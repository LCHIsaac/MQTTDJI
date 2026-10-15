pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://developer.dji.com/mvn/") }
        maven { url = uri("https://jitpack.io") }
    }
}

include(":core")
include(":dji-adapter")
include(":app-ui")
include(":app-rcpro")
include(":app-phone")
include(":app-persistence")

rootProject.name = "drone-mqtt-control"
