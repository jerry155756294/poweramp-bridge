plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
  releaseKeystorePath,
  releaseKeystorePassword,
  releaseKeyAlias,
  releaseKeyPassword
).all { !it.isNullOrBlank() }
val appVersionCode = providers.gradleProperty("appVersionCode")
  .orNull
  ?.toIntOrNull()
  ?: 3
val appVersionName = providers.gradleProperty("appVersionName")
  .orNull
  ?: "0.1.2"
val releaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
  taskName.substringAfterLast(':').contains("Release", ignoreCase = true)
}

check(appVersionCode > 0) { "appVersionCode must be a positive integer" }
check(!releaseTaskRequested || releaseSigningConfigured) {
  "Release builds require ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, " +
    "ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD"
}
if (releaseSigningConfigured) {
  check(file(releaseKeystorePath!!).isFile) {
    "Configured release keystore does not exist: $releaseKeystorePath"
  }
}

android {
  namespace = "com.jerry155756294.powerampbridge"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.jerry155756294.powerampbridge"
    minSdk = 26
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      if (releaseSigningConfigured) {
        storeFile = file(releaseKeystorePath!!)
        storePassword = releaseKeystorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      if (releaseSigningConfigured) {
        signingConfig = signingConfigs.getByName("release")
      }
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }

}
dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2026.03.01")

  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation("androidx.core:core-ktx:1.18.0")
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
  implementation("androidx.datastore:datastore-preferences:1.2.1")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.appcompat:appcompat:1.7.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("com.squareup.moshi:moshi:1.15.2")
  implementation("com.squareup.okio:okio:3.17.0")
  implementation("com.jakewharton.timber:timber:5.0.1")
  implementation("org.eclipse.jdt:org.eclipse.jdt.annotation:2.3.100")

  debugImplementation("androidx.compose.ui:ui-tooling")
  debugImplementation("androidx.compose.ui:ui-test-manifest")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.16.1")
}
