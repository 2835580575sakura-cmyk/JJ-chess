plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization") }
android {
    namespace = "dev.sakura.xiangqi.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    signingConfigs { getByName("debug") { storeFile = rootProject.file(".local-signing/debug.keystore") } }
    defaultConfig { applicationId = "dev.sakura.xiangqiassistant"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "0.1.0"; ndk { abiFilters += "arm64-v8a" }; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    androidResources { noCompress += "nnue" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core")); implementation(project(":vision")); implementation(project(":engine"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    testImplementation(libs.junit); testImplementation(libs.robolectric); testImplementation(libs.coroutines.test)
    androidTestImplementation("androidx.test:runner:1.6.2"); androidTestImplementation("androidx.test:rules:1.6.1"); androidTestImplementation(libs.junit)
}
