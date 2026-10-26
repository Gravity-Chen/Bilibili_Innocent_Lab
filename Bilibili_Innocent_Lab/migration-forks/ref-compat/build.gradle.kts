plugins { id("com.android.library") }
android {
    namespace = "io.github.jichuo1.meguviz.compat"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 27 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api(libs.meguviz.ref.core)
    testImplementation(libs.junit)
}
