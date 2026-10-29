rootProject.name = "ElTermino"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(
    ":core:ssh",
    ":shared",
    ":app-android",
    ":app-desktop",
    ":spikes:common",
    ":spikes:mina",
    ":spikes:cbssh",
    ":spikes:terminal",
)
