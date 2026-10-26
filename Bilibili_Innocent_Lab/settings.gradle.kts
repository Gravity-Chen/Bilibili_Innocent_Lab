pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://jitpack.io/")
    }
}

plugins {
    id("com.highcapable.gropify") version "1.0.2"
}

gropify {
    rootProject {
        common {
            isEnabled = false
        }
    }
}

rootProject.name = "Bilibili_Innocent_Lab"

include(":app")

// Explicit development override; normal builds keep the official Maven artifacts.
providers.gradleProperty("lumen.sourceDir").orNull?.let { sourceDir ->
    includeBuild(file(sourceDir)) {
        dependencySubstitution {
            for (artifact in listOf("lumen-engine", "lumen-motion")) {
                substitute(module("com.github.jichuo1.LumenCoacervationEngine:$artifact"))
                    .using(project(":$artifact"))
            }
        }
    }
}

// Isolated full-replacement migration; source provenance is recorded alongside each fork.
include(":migration-hikage-core")
project(":migration-hikage-core").projectDir = file("migration-forks/hikage-core")
include(":migration-hikage-extension")
project(":migration-hikage-extension").projectDir = file("migration-forks/hikage-extension")
include(":migration-hikage-runtime-attribute")
project(":migration-hikage-runtime-attribute").projectDir = file("migration-forks/hikage-runtime-attribute")
include(":migration-system-extension")
project(":migration-system-extension").projectDir = file("migration-forks/system-extension")
include(":migration-ui-component")
project(":migration-ui-component").projectDir = file("migration-forks/ui-component")
include(":migration-ui-extension")
project(":migration-ui-extension").projectDir = file("migration-forks/ui-extension")
include(":migration-ref-compat")
project(":migration-ref-compat").projectDir = file("migration-forks/ref-compat")
