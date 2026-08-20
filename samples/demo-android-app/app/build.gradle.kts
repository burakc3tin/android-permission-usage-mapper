plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.demo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.demo.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("com.google.android.gms:play-services-ads:22.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
