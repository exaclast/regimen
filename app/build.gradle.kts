import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
  if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { load(it) }
  }
}

val resolvedKeystorePath: String? =
  System.getenv("KEYSTORE_FILE") ?: keystoreProperties.getProperty("storeFile")
val resolvedKeystorePassword: String? =
  System.getenv("KEYSTORE_PASSWORD") ?: keystoreProperties.getProperty("storePassword")
val resolvedKeyAlias: String? =
  System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
    ?: keystoreProperties.getProperty("keyAlias")
    ?: "regimen"
val resolvedKeyPassword: String? =
  System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: keystoreProperties.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
    ?: resolvedKeystorePassword

val hasReleaseSigning = !resolvedKeystorePath.isNullOrBlank() &&
  !resolvedKeystorePassword.isNullOrBlank() &&
  !resolvedKeyAlias.isNullOrBlank() &&
  !resolvedKeyPassword.isNullOrBlank()

android {
  namespace = "com.exaclast.regimen"
  // Material 3 1.5 requires compiling against API 37; use the installed 37.2 platform. targetSdk stays 36.
  compileSdk {
    version = release(37) { minorApiLevel = 2 }
  }
  defaultConfig {
    applicationId = "com.exaclast.regimen"
    minSdk = 26
    targetSdk = 36
    versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
    versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(resolvedKeystorePath!!)
        storePassword = resolvedKeystorePassword
        keyAlias = resolvedKeyAlias
        keyPassword = resolvedKeyPassword
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }
  testOptions {
    unitTests.isReturnDefaultValues = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.window)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose & Adaptive UI
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
  implementation(libs.androidx.compose.material3.adaptive)
  implementation(libs.androidx.compose.material3.adaptive.layout)
  implementation(libs.androidx.compose.material3.adaptive.navigation)
  implementation(libs.androidx.compose.material.icons.extended)

  // Serialization
  implementation(libs.kotlinx.serialization.json)

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // On-device Gemini Nano / AICore Label Scanner (ML Kit GenAI Prompt API)
  implementation(libs.mlkit.genai.prompt)
}
