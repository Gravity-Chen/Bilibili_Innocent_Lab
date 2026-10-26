plugins {
    alias(libs.plugins.android.application) apply false
    // Keep KGP on the classpath as AGP's built-in Kotlin compiler version anchor.
    // Applying it would disable built-in Kotlin; removing it would fall back to AGP's
    // older compiler, which cannot read all dependency metadata used by this project.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.ksp) apply false
}
subprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.highcapable.hikage:hikage-core")).using(project(":migration-hikage-core"))
            substitute(module("com.highcapable.hikage:hikage-extension")).using(project(":migration-hikage-extension"))
            substitute(module("com.highcapable.hikage:hikage-runtime-attribute")).using(project(":migration-hikage-runtime-attribute"))
            substitute(module("com.highcapable.betterandroid:system-extension")).using(project(":migration-system-extension"))
            substitute(module("com.highcapable.betterandroid:ui-component")).using(project(":migration-ui-component"))
            substitute(module("com.highcapable.betterandroid:ui-extension")).using(project(":migration-ui-extension"))
        }
        exclude(group = "com.highcapable.kavaref")
    }
}
