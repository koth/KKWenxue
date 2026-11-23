plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val signReleaseForLocalInstall = providers.gradleProperty("signReleaseForLocalInstall").orNull == "true"
val localTtsVoiceDir = providers.gradleProperty("shanyeZonosVoiceDir")
    .orElse(layout.projectDirectory.dir("../tmp/zonos3-publish/voices").asFile.absolutePath)
val localTtsAssets = layout.buildDirectory.dir("generated/localTtsAssets")

android {
    namespace = "com.kkwenxue.shanye"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.kkwenxue.shanye"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "0.9.17"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.named("main") { assets.srcDir(localTtsAssets) }
    androidResources { noCompress += listOf("npy") }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    if (signReleaseForLocalInstall) {
        buildTypes.named("release") { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val syncEmbeddedLocalTtsAssets = tasks.register<Sync>("syncEmbeddedLocalTtsAssets") {
    group = "distribution"
    description = "Copy ZONOS3 weights and pre-encoded voices into the release APK assets."
    from(localTtsVoiceDir) {
        include("*.codes.npy", "voices.json")
        into("voices")
    }
    // The two current Chinese narrator presets are kept in the local encoder cache
    // until the server voice directory is exported for release builds.
    from(rootProject.file(".workbench/reference-voices/23915d12ec06c1fca031203a127696efddbf52264a245c54d45e6d2582eca5c0/reference.codes.npy")) {
        rename { "shuoshuren1.codes.npy" }
        into("voices")
    }
    from(rootProject.file(".workbench/reference-voices/d54c9aec5d26dba1566eeebad09bc24838dec9c485b0ff1c4c3ca9d8ea8dc735/reference.codes.npy")) {
        rename { "shuoshuren2.codes.npy" }
        into("voices")
    }
    from(rootProject.file("sounds/shuoshuren1.codes.npy")) {
        into("voices")
    }
    into(localTtsAssets)
}

tasks.whenTaskAdded {
    if (name == "preReleaseBuild") dependsOn(syncEmbeddedLocalTtsAssets)
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
