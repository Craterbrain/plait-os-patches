plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "app.loudness"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.loudness"
    // DynamicsProcessing is API 28+
    minSdk = 28
    targetSdk = 35
    versionCode = 3
    versionName = "1.2"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      // Personal sideloaded tool: signed with the local debug key
      signingConfig = signingConfigs.getByName("debug")
    }
  }
}
