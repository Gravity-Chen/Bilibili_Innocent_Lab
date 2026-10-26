plugins { id("com.android.library") }
android {
    namespace = "com.highcapable.hikage.runtime.attribute"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":migration-ref-compat"))
    api("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    api("com.highcapable.betterandroid:system-extension:1.1.6")
    api("androidx.core:core-ktx:1.19.1")
}
