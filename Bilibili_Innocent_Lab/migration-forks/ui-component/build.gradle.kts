plugins { id("com.android.library") }
android {
    namespace = "com.highcapable.betterandroid.ui.component"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":migration-ref-compat"))
    // Original lint checks from the upstream AAR, unchanged; build-time only.
    lintPublish(files("lint/lint.jar"))
    api("androidx.databinding:viewbinding:9.3.2")
    api("com.highcapable.betterandroid:ui-extension:1.1.6")
    api("com.highcapable.betterandroid:system-extension:1.1.6")
    api("androidx.core:core-ktx:1.19.1")
    api("androidx.appcompat:appcompat:1.8.0")
}
