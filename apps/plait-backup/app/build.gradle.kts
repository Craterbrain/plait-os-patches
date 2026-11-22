import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Signed with the Plait Backup key the broker pins (~/plait-keys, never in the repo): without it grants are refused
val signing = Properties().apply {
  file(System.getProperty("user.home") + "/plait-keys/plait-backup.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
  namespace = "app.plait.backup"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.plait.backup"
    minSdk = 31
    targetSdk = 35
    versionCode = 44
    versionName = "0.42"
    // The ROM build (-PplaitRom=true, scripts/stage-plait-backup.sh in plait-os) has no root fallback at all: it only
    // talks to the plait_backup system service, and R8 drops the `su` code. Other builds keep the root dev backend.
    buildConfigField("boolean", "ROOT_FALLBACK", if (project.hasProperty("plaitRom")) "false" else "true")
  }

  signingConfigs {
    if (signing.isNotEmpty()) {
      create("plait") {
        storeFile = file(signing.getProperty("storeFile"))
        storePassword = signing.getProperty("storePassword")
        keyAlias = signing.getProperty("keyAlias")
        keyPassword = signing.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    debug { signingConfigs.findByName("plait")?.let { signingConfig = it } }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.findByName("plait") ?: signingConfigs.getByName("debug")
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation("app.plait:design")
  implementation(libs.kotlinx.coroutines.android)
  testImplementation(libs.junit)
  testImplementation(libs.org.json)
  testImplementation(libs.kxml2)
}
