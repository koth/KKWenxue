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
val localTtsVoiceAssets = listOf(
    Triple("林川", "林川.mp3", "林川.txt") to "LinChuan.codes.npy",
    Triple("Shuqing", "Shuqing.mp3", "Shuqing.txt") to "Shuqing.codes.npy",
    Triple("Story", "Story.mp3", "Story.txt") to "Story.codes.npy"
)

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
    description = "Validate the packaged local ZONOS3 reference audio, transcripts and pre-encoded voices."
    localTtsVoiceAssets.forEach { (reference, codes) ->
        inputs.file(rootProject.file("sounds/${reference.second}"))
        inputs.file(rootProject.file("sounds/${reference.third}"))
        inputs.file(rootProject.file("sounds/$codes"))
    }
    doLast {
        localTtsVoiceAssets.forEach { (reference, codes) ->
            val audio = rootProject.file("sounds/${reference.second}")
            val text = rootProject.file("sounds/${reference.third}")
            val encoded = rootProject.file("sounds/$codes")
            require(audio.isFile) { "缺少${reference.first}参考音频：${audio.absolutePath}" }
            require(text.isFile && text.readText(Charsets.UTF_8).trim().isNotBlank()) {
                "缺少${reference.first}逐字稿：${text.absolutePath}"
            }
            require(encoded.isFile && encoded.length() > 0) {
                "缺少${reference.first}预编码声线：${encoded.absolutePath}"
            }
        }
        logger.lifecycle("已绑定本地声线：林川、Shuqing、Story")
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
        include(localTtsVoiceAssets.map { it.second })
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
