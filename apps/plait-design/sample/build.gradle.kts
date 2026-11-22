plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "app.plait.design.sample"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.plait.design.sample"
    minSdk = 31
    targetSdk = 35
    versionCode = 1
    versionName = "0.1"
  }

  buildFeatures { compose = true }
}

dependencies {
  implementation(project(":design"))
  implementation(libs.androidx.activity.compose)
}
