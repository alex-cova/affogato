import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

rootProject.name = "affogato"

include("affogato-runtime")
include("affogato-compiler")
include("affogato-gradle-plugin")
include("affogato-intellij-plugin")

// Note: affogato-samples/hello is intentionally NOT included here. It is a standalone
// consumer build (see affogato-samples/hello/settings.gradle.kts) that applies the
// `dev.affogato` plugin via `includeBuild("../..")`. Including it here too would be
// circular — configuring the root build would force `hello` to resolve the very plugin
// this build produces, which is only available after a prior publish to mavenLocal.
