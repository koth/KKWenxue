plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val signReleaseForLocalInstall = providers.gradleProperty("signReleaseForLocalInstall").orNull == "true"

android {
    namespace = "com.kkwenxue.shanye"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kkwenxue.shanye"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "0.9.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    if (signReleaseForLocalInstall) {
        buildTypes.named("release") { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.register<Copy>("distReleaseApk") {
    group = "distribution"
    description = "Build the release APK and put it in the root dist directory."
    dependsOn("assembleRelease")
    val apkName = if (signReleaseForLocalInstall) "app-release.apk" else "app-release-unsigned.apk"
    val suffix = if (signReleaseForLocalInstall) "local" else "unsigned"
    from(layout.buildDirectory.file("outputs/apk/release/$apkName"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "ShanYe-${android.defaultConfig.versionName}-release-$suffix.apk" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
