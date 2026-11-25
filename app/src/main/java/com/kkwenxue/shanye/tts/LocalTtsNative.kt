package com.kkwenxue.shanye.tts

/** Thin JNI boundary. The native library is optional at install time and reports its capability explicitly. */
internal object LocalTtsNative {
    private var loaded = false

    init {
        loaded = runCatching { System.loadLibrary("shanye_tts_native"); true }.getOrDefault(false)
    }

    fun capabilities(): String = if (loaded) runCatching { nativeCapabilities() }.getOrDefault("{\"native\":false}")
    else "{\"native\":false,\"reason\":\"native library unavailable\"}"

    fun load(backbone: String, decoder: String, threads: Int): Boolean =
        loaded && runCatching { nativeLoad(backbone, decoder, threads) }.getOrDefault(false)

    fun synthesize(
        text: String,
        codesPath: String,
        maxFrames: Int,
        seed: Int,
        titleSynthesis: Boolean = false
    ): ByteArray? =
        if (!loaded) null else runCatching {
            nativeSynthesize(text, codesPath, maxFrames, seed, titleSynthesis)
        }.getOrNull()

    fun cancel() { if (loaded) runCatching { nativeCancel() } }
    fun release() { if (loaded) runCatching { nativeRelease() } }

    private external fun nativeCapabilities(): String
    private external fun nativeLoad(backbone: String, decoder: String, threads: Int): Boolean
    private external fun nativeSynthesize(
        text: String,
        codesPath: String,
        maxFrames: Int,
        seed: Int,
        stabilizeShortText: Boolean
    ): ByteArray?
    private external fun nativeCancel()
    private external fun nativeRelease()
}
