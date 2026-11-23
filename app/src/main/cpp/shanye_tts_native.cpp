#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#ifdef SHANYE_ZONOS3_AVAILABLE
#include "npy.h"
#include "zac.h"
#include "zonos3.h"
#include "zonos3-generation.h"
#include "ggml-cpu.h"
#endif

namespace {

constexpr const char * kTag = "ShanyeLocalTts";
std::mutex g_mutex;
std::atomic_bool g_cancelled{false};

std::string toString(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * raw = env->GetStringUTFChars(value, nullptr);
    std::string result = raw ? raw : "";
    if (raw) env->ReleaseStringUTFChars(value, raw);
    return result;
}

jbyteArray pcm16(JNIEnv * env, const std::vector<float> & audio) {
    std::vector<int16_t> out(audio.size());
    for (size_t i = 0; i < audio.size(); ++i) {
        const float value = std::isfinite(audio[i]) ? std::clamp(audio[i], -1.0f, 1.0f) : 0.0f;
        out[i] = static_cast<int16_t>(std::lrint(value * 32767.0f));
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size() * sizeof(int16_t)));
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size() * sizeof(int16_t)),
                            reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

#ifdef SHANYE_ZONOS3_AVAILABLE
struct Runtime {
    zonos3_model backbone;
    zac_model decoder;
    bool backbone_ready = false;
    bool decoder_ready = false;
    std::string backbone_path;
    std::string decoder_path;

    ~Runtime() {
        if (decoder_ready) zac_free(decoder);
        if (backbone_ready) zonos3_model_free(backbone);
    }
};
std::unique_ptr<Runtime> g_runtime;

bool load_codes(const std::string & path, std::vector<int32_t> & out, int & frames) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return false;
    const auto length = input.tellg();
    if (length <= 0 || length > 64 * 1024 * 1024) return false;
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    input.seekg(0);
    input.read(reinterpret_cast<char *>(bytes.data()), length);
    npy::Array array;
    std::string error;
    if (!npy::parse(bytes.data(), bytes.size(), array, error) || array.shape.size() != 2 || array.shape[1] != 10 ||
        array.shape[0] <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "voice code parse failed: %s", error.c_str());
        return false;
    }
    frames = static_cast<int>(array.shape[0]);
    out.resize(array.data.size());
    for (size_t i = 0; i < array.data.size(); ++i) {
        const double value = array.data[i];
        if (!std::isfinite(value) || value < 0 || value >= 4096 || std::floor(value) != value) return false;
        out[i] = static_cast<int32_t>(value);
    }
    return true;
}

// A short title can produce a valid but unrelated waveform because the model
// was trained mostly on complete sentences. The release server solves this
// with a pause marker plus a filler sentence. On Android we keep the same
// conditioning and cut at the long pause before the filler.
size_t short_text_cut(const std::vector<float> & audio) {
    constexpr size_t kSampleRate = 44100;
    constexpr size_t kWindow = kSampleRate / 50; // 20 ms
    constexpr int kMinimumVoicedWindows = 5;    // 100 ms of speech
    constexpr int kRequiredSilenceWindows = 8;  // 160 ms pause
    constexpr float kVoicedRms = 0.012f;
    constexpr float kSilentRms = 0.006f;
    if (audio.size() < kWindow * (kMinimumVoicedWindows + kRequiredSilenceWindows)) return 0;

    int voiced_windows = 0;
    int silent_windows = 0;
    size_t silence_start = 0;
    for (size_t offset = 0; offset + kWindow <= audio.size(); offset += kWindow) {
        double power = 0.0;
        for (size_t i = offset; i < offset + kWindow; ++i) {
            const float sample = std::isfinite(audio[i]) ? audio[i] : 0.0f;
            power += static_cast<double>(sample) * sample;
        }
        const float rms = static_cast<float>(std::sqrt(power / static_cast<double>(kWindow)));
        if (rms >= kVoicedRms) {
            ++voiced_windows;
            silent_windows = 0;
            continue;
        }
        if (voiced_windows < kMinimumVoicedWindows || rms > kSilentRms) {
            silent_windows = 0;
            continue;
        }
        if (silent_windows == 0) silence_start = offset;
        ++silent_windows;
        if (silent_windows >= kRequiredSilenceWindows) return silence_start;
    }
    return 0;
}
#endif

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_kkwenxue_shanye_tts_LocalTtsNative_nativeCapabilities(JNIEnv * env, jclass) {
#ifdef SHANYE_ZONOS3_AVAILABLE
    const std::string value = std::string("{\"native\":true,\"abi\":\"arm64-v8a\",\"backend\":\"ggml-cpu-kleidiai\",\"sample_rate\":44100,") +
        "\"neon\":" + std::to_string(ggml_cpu_has_neon()) +
        ",\"fp16\":" + std::to_string(ggml_cpu_has_fp16_va()) +
        ",\"dotprod\":" + std::to_string(ggml_cpu_has_dotprod()) +
        ",\"i8mm\":" + std::to_string(ggml_cpu_has_matmul_int8()) +
        ",\"sve\":" + std::to_string(ggml_cpu_has_sve()) + "}";
    return env->NewStringUTF(value.c_str());
#else
    const char * value = "{\"native\":false,\"abi\":\"arm64-v8a\",\"backend\":\"unavailable\",\"reason\":\"ZONOS3 source bundle is not installed\"}";
    return env->NewStringUTF(value);
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kkwenxue_shanye_tts_LocalTtsNative_nativeLoad(JNIEnv * env, jclass, jstring backbone, jstring decoder,
                                                        jint threads) {
#ifndef SHANYE_ZONOS3_AVAILABLE
    (void) env; (void) backbone; (void) decoder; (void) threads;
    return JNI_FALSE;
#else
    std::lock_guard<std::mutex> lock(g_mutex);
    const std::string backbone_path = toString(env, backbone);
    const std::string decoder_path = toString(env, decoder);
    if (backbone_path.empty() || decoder_path.empty()) return JNI_FALSE;
    if (g_runtime && g_runtime->backbone_path == backbone_path && g_runtime->decoder_path == decoder_path) return JNI_TRUE;
    g_cancelled.store(false);
    if (threads > 0) {
        std::string value = std::to_string(static_cast<int>(threads));
        setenv("GGML_NUM_THREADS", value.c_str(), 1);
        setenv("ZAC_THREADS", value.c_str(), 1);
    }
    auto next = std::make_unique<Runtime>();
    if (!zonos3_model_load(next->backbone, backbone_path.c_str(), false)) return JNI_FALSE;
    next->backbone_ready = true;
    if (!zac_load(next->decoder, decoder_path.c_str(), false)) return JNI_FALSE;
    next->decoder_ready = true;
    next->backbone_path = backbone_path;
    next->decoder_path = decoder_path;
    g_runtime = std::move(next);
    return JNI_TRUE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_kkwenxue_shanye_tts_LocalTtsNative_nativeCancel(JNIEnv *, jclass) {
    g_cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kkwenxue_shanye_tts_LocalTtsNative_nativeRelease(JNIEnv *, jclass) {
#ifndef SHANYE_ZONOS3_AVAILABLE
    return;
#else
    std::lock_guard<std::mutex> lock(g_mutex);
    g_runtime.reset();
    g_cancelled.store(false);
#endif
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_kkwenxue_shanye_tts_LocalTtsNative_nativeSynthesize(JNIEnv * env, jclass, jstring text, jstring codes_path,
                                                              jint max_frames, jint seed, jboolean stabilize_short_text) {
#ifndef SHANYE_ZONOS3_AVAILABLE
    (void) env; (void) text; (void) codes_path; (void) max_frames; (void) seed; (void) stabilize_short_text;
    return nullptr;
#else
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_runtime || !g_runtime->backbone_ready || !g_runtime->decoder_ready) return nullptr;
    const std::string requested_text = toString(env, text);
    if (requested_text.empty()) return nullptr;
    std::vector<int32_t> reference;
    int reference_frames = 0;
    if (!load_codes(toString(env, codes_path), reference, reference_frames)) return nullptr;
    const bool stabilize = stabilize_short_text == JNI_TRUE;

    auto generate_audio = [&](const std::string & synthesis_text, const zonos3_sampling & sampling,
                              std::vector<float> & audio) -> bool {
        zonos3_prompt_options options;
        options.reference_codes = reference.data();
        options.reference_frames = std::min(reference_frames, zonos3_generation().reference_max_frames);
        zonos3_prompt prompt;
        std::string error;
        if (!zonos3_build_prompt(g_runtime->backbone, synthesis_text, options, prompt, &error)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "prompt failed: %s", error.c_str());
            return false;
        }
        std::vector<int32_t> generated;
        int eos_frame = -1;
        const int budget = std::max(1, std::min(static_cast<int>(max_frames),
                                                 static_cast<int>(g_runtime->backbone.hp.n_ctx_train) - prompt.n_rows));
        const int frames = zonos3_generate(g_runtime->backbone, prompt, budget, sampling, 1.0f, generated, eos_frame,
                                           true, nullptr, {}, nullptr);
        if (frames <= 0 || g_cancelled.load()) return false;
        std::vector<int32_t> codes = zonos3_prefill_codes(g_runtime->backbone, prompt);
        codes.insert(codes.end(), generated.begin(), generated.end());
        if (!zac_decode(g_runtime->decoder, codes.data(), static_cast<int>(codes.size() / 10), 10, eos_frame, audio)) {
            return false;
        }
        const size_t drop = std::min(audio.size(), static_cast<size_t>(prompt.prefill_frames) *
                                                       zac_samples_per_frame(g_runtime->decoder));
        audio.erase(audio.begin(), audio.begin() + drop);
        const float gain = zac_output_gain(g_runtime->decoder, -1.0);
        for (float & sample : audio) sample *= gain;
        zonos3_soft_limit(audio.data(), audio.size());
        return !audio.empty();
    };

    if (stabilize) {
        // U+E004 is the release registry's 0.5-second pause marker. The
        // Chinese filler provides sentence-shaped context for short titles.
        const std::string short_text = requested_text + "\xEE\x80\x84小鸟飞过了安静的小河。";
        for (int attempt = 0; attempt < 3; ++attempt) {
            zonos3_sampling sampling;
            sampling.seed = static_cast<uint32_t>(seed) + static_cast<uint32_t>(attempt * 1009);
            sampling.greedy = true;
            std::vector<float> audio;
            if (!generate_audio(short_text, sampling, audio)) {
                if (g_cancelled.load()) return nullptr;
                continue;
            }
            const size_t cut = short_text_cut(audio);
            if (cut > 0) {
                audio.resize(cut);
                return pcm16(env, audio);
            }
        }

        // Do not allow the filler to leak into the book if the model pack
        // lacks pause rows or a clean silence boundary was not emitted.
        zonos3_sampling fallback;
        fallback.seed = static_cast<uint32_t>(seed);
        fallback.greedy = true;
        std::vector<float> audio;
        if (generate_audio(requested_text, fallback, audio)) return pcm16(env, audio);
        return nullptr;
    }

    zonos3_sampling sampling;
    sampling.seed = static_cast<uint32_t>(seed);
    std::vector<float> audio;
    if (!generate_audio(requested_text, sampling, audio)) return nullptr;
    return pcm16(env, audio);
#endif
}
