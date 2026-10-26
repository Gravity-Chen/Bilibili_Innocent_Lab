plugins { id("com.android.library") }
android {
    namespace = "com.highcapable.betterandroid.system.extension"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Upstream Broadcast.kt calls the API 34 sendBroadcast overload without a guard; BIL does not use it.
    lint { baseline = file("lint-baseline.xml") }
}
dependencies {
    implementation(project(":migration-ref-compat"))
    // Original lint checks from the upstream AAR, unchanged; build-time only.
    lintPublish(files("lint/lint.jar"))
    api("androidx.appcompat:appcompat:1.8.0")
}
