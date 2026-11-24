plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val signReleaseForLocalInstall = providers.gradleProperty("signReleaseForLocalInstall").orNull == "true"
val localTtsVoiceDir = providers.gradleProperty("shanyeZonosVoiceDir").orNull
    ?.let(::file)
    ?: rootProject.file("sounds")
val localTtsAssets = layout.buildDirectory.dir("generated/localTtsAssets")
val localTtsReferenceAudio = rootProject.file("sounds/林川.mp3")
val localTtsReferenceText = rootProject.file("sounds/林川.txt")
val localTtsReferenceCodes = rootProject.file("sounds/LinChuan.codes.npy")

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

val validateEmbeddedLinChuan = tasks.register("validateEmbeddedLinChuan") {
    group = "distribution"
    description = "Validate the packaged 林川 reference audio, transcript and pre-encoded voice."
    inputs.file(localTtsReferenceAudio)
    inputs.file(localTtsReferenceText)
    inputs.file(localTtsReferenceCodes)
    doLast {
        require(localTtsReferenceAudio.isFile) {
            "缺少默认林川参考音频：${localTtsReferenceAudio.absolutePath}"
        }
        val transcript = localTtsReferenceText.takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            .orEmpty()
        require(transcript.isNotBlank()) {
            "缺少默认林川逐字稿：${localTtsReferenceText.absolutePath}"
        }
        require(localTtsReferenceCodes.isFile && localTtsReferenceCodes.length() > 0) {
            "缺少林川预编码声线：${localTtsReferenceCodes.absolutePath}"
        }
        logger.lifecycle("默认林川声线已绑定 sounds/林川.mp3 + sounds/林川.txt")
    }
}

val syncEmbeddedLocalTtsAssets = tasks.register<Sync>("syncEmbeddedLocalTtsAssets") {
    group = "distribution"
    description = "Copy ZONOS3 weights and pre-encoded voices into the release APK assets."
    dependsOn(validateEmbeddedLinChuan)
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from(localTtsVoiceDir) {
        // Keep the checked-in manifest as the single asset entry point. The
        // release voice directory may also contain a server manifest, which
        // would collide with app/src/main/assets/voices/voices.json.
        include("*.codes.npy")
        into("voices")
    }
    from(localTtsReferenceCodes) {
        include("LinChuan.codes.npy")
        into("voices")
    }
    into(localTtsAssets)
}

tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("Build")) {
        dependsOn(syncEmbeddedLocalTtsAssets)
    }
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
