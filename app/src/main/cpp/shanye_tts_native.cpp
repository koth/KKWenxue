#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#ifdef SHANYE_ZONOS3_AVAILABLE
#include "npy.h"
#include "zac.h"
#include "zonos3.h"
#include "zonos3-generation.h"
#include "zonos3-laci.h"
#include "zonos3-short-text.h"
#include "zonos3-text.h"
#include "ggml-cpu.h"
#include "zonos3-zh-heads-json.h"
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
    if (audio.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()) / sizeof(int16_t)) return nullptr;
    const jsize bytes = static_cast<jsize>(audio.size() * sizeof(int16_t));
    jbyteArray result = env->NewByteArray(bytes);
    if (!result) return nullptr;
    jbyte * out = env->GetByteArrayElements(result, nullptr);
    if (!out) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    for (size_t i = 0; i < audio.size(); ++i) {
        const float value = std::isfinite(audio[i]) ? std::clamp(audio[i], -1.0f, 1.0f) : 0.0f;
        const int16_t sample = static_cast<int16_t>(std::lrint(value * 32767.0f));
        std::memcpy(out + i * sizeof(int16_t), &sample, sizeof(sample));
    }
    env->ReleaseByteArrayElements(result, out, 0);
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
    std::string reference_codes_path;
    std::vector<int32_t> reference_codes;
    int reference_frames = 0;

    ~Runtime() {
        if (decoder_ready) zac_free(decoder);
        if (backbone_ready) zonos3_model_free(backbone);
    }
};
std::unique_ptr<Runtime> g_runtime;

bool load_codes(const std::string & path, std::vector<int32_t> & out, int & frames) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "voice code file open failed: %s", path.c_str());
        return false;
    }
    const auto length = input.tellg();
    if (length <= 0 || length > 64 * 1024 * 1024) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "voice code file has invalid size: %s (%lld)",
                            path.c_str(), static_cast<long long>(length));
        return false;
    }
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

bool ensure_reference_codes(Runtime & runtime, const std::string & path) {
    if (runtime.reference_codes_path == path && !runtime.reference_codes.empty() && runtime.reference_frames > 0) {
        return true;
    }
    std::vector<int32_t> codes;
    int frames = 0;
    if (!load_codes(path, codes, frames)) {
        runtime.reference_codes.clear();
        runtime.reference_codes_path.clear();
        runtime.reference_frames = 0;
        return false;
    }
    runtime.reference_codes = std::move(codes);
    runtime.reference_codes_path = path;
    runtime.reference_frames = frames;
    return true;
}

const zonos3_laci::LaciHeads & chinese_laci_heads() {
    static const zonos3_laci::LaciHeads heads = zonos3_laci::LaciHeads::parse(
        std::string(reinterpret_cast<const char *>(zonos3_zh_heads_json), zonos3_zh_heads_json_len),
        "embedded zonos3_mini_heads_zh.json");
    return heads;
}

// Match the server's `short_text=auto` prompt for short headings. A heading
// generated in isolation is easy for the model to continue with unrelated
// words, while the pause marker and the known filler give the alignment probe
// a deterministic boundary at which the filler starts.
std::optional<int> title_short_text_prompt(const zonos3_model & model, const std::string & text,
                                           std::string & expanded) {
    const auto & registry = zonos3_short::registry();
    if (model.tok.pause_ids.empty() || registry.pause_marker.size() != 3) return std::nullopt;
    const int bucket = static_cast<unsigned char>(registry.pause_marker[2]) - 0x83;
    if (bucket < 0 || bucket >= static_cast<int>(model.tok.pause_ids.size())) return std::nullopt;
    const auto filler = registry.fillers.find("cmn");
    if (filler == registry.fillers.end()) return std::nullopt;

    int words = 0;
    bool in_word = false;
    for (const char32_t cp : utf8_to_u32(text)) {
        if (is_py_space(static_cast<uint32_t>(cp))) {
            in_word = false;
        } else if (!in_word) {
            ++words;
            in_word = true;
        }
    }
    if (words > registry.max_words) return std::nullopt;

    std::vector<int32_t> text_ids;
    std::vector<int32_t> filler_ids;
    std::string error;
    if (!zonos3_payload_ids(model.tok, text, text_ids, error) ||
        !zonos3_payload_ids(model.tok, filler->second, filler_ids, error) ||
        text_ids.empty() ||
        static_cast<int64_t>(text_ids.size()) * registry.max_ratio[1] >
            static_cast<int64_t>(filler_ids.size()) * registry.max_ratio[0]) {
        return std::nullopt;
    }

    expanded = text + registry.pause_marker + filler->second;
    std::vector<int32_t> expanded_ids;
    if (!zonos3_text_ids(model.tok, expanded, expanded_ids, error)) return std::nullopt;
    const int pause_id = model.tok.pause_ids[bucket];
    for (size_t i = 0; i + 1 < expanded_ids.size(); ++i) {
        if (expanded_ids[i] == pause_id) return static_cast<int>(i + 1);
    }
    return std::nullopt;
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
    __android_log_print(ANDROID_LOG_INFO, kTag, "loading model backbone=%s decoder=%s threads=%d",
                        backbone_path.c_str(), decoder_path.c_str(), static_cast<int>(threads));
    if (backbone_path.empty() || decoder_path.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "model path is empty");
        return JNI_FALSE;
    }
    if (g_runtime && g_runtime->backbone_path == backbone_path && g_runtime->decoder_path == decoder_path) return JNI_TRUE;
    // Loading a new pack while the old one is still resident can briefly
    // require both ~1.1 GB model sets. Release the old pack first so model
    // upgrades do not create a transient OOM peak.
    g_runtime.reset();
    g_cancelled.store(false);
    if (threads > 0) {
        std::string value = std::to_string(static_cast<int>(threads));
        setenv("GGML_NUM_THREADS", value.c_str(), 1);
        setenv("ZAC_THREADS", value.c_str(), 1);
    }
    auto next = std::make_unique<Runtime>();
    if (!zonos3_model_load(next->backbone, backbone_path.c_str(), false)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "backbone load failed: %s", backbone_path.c_str());
        return JNI_FALSE;
    }
    next->backbone_ready = true;
    if (!zac_load(next->decoder, decoder_path.c_str(), false)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "ZAC decoder load failed: %s", decoder_path.c_str());
        return JNI_FALSE;
    }
    next->decoder_ready = true;
    next->backbone_path = backbone_path;
    next->decoder_path = decoder_path;
    g_runtime = std::move(next);
    __android_log_print(ANDROID_LOG_INFO, kTag, "model loaded successfully");
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
                                                              jint max_frames, jint seed, jboolean title_synthesis) {
#ifndef SHANYE_ZONOS3_AVAILABLE
    (void) env; (void) text; (void) codes_path; (void) max_frames; (void) seed; (void) title_synthesis;
    return nullptr;
#else
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_runtime || !g_runtime->backbone_ready || !g_runtime->decoder_ready) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "synthesis requested before model load");
        return nullptr;
    }
    const std::string requested_text = toString(env, text);
    const std::string reference_path = toString(env, codes_path);
    if (requested_text.empty() || reference_path.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "synthesis input is empty");
        return nullptr;
    }
    if (!ensure_reference_codes(*g_runtime, reference_path)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "reference voice load failed: %s", reference_path.c_str());
        return nullptr;
    }
    const std::vector<int32_t> & reference = g_runtime->reference_codes;
    const int reference_frames = g_runtime->reference_frames;
    const bool title_mode = title_synthesis == JNI_TRUE;

    auto generate_audio = [&](const std::string & synthesis_text, const zonos3_sampling & sampling,
                              std::vector<float> & audio, bool use_title_alignment,
                              bool allow_short_run_on, bool & short_run_on) -> bool {
        short_run_on = false;
        std::string expanded_text;
        std::optional<int> filler_token;
        bool alignment_available = false;
        if (use_title_alignment) {
            try {
                (void) chinese_laci_heads();
                alignment_available = true;
            } catch (const std::exception & probe_error) {
                __android_log_print(ANDROID_LOG_WARN, kTag, "title alignment heads unavailable: %s",
                                    probe_error.what());
            }
        }
        if (alignment_available) {
            filler_token = title_short_text_prompt(g_runtime->backbone, synthesis_text, expanded_text);
        }
        const std::string effective_text = filler_token ? expanded_text : synthesis_text;
        zonos3_prompt_options options;
        options.reference_codes = reference.data();
        options.reference_frames = std::min(reference_frames, zonos3_generation().reference_max_frames);
        zonos3_prompt prompt;
        std::string error;
        if (!zonos3_build_prompt(g_runtime->backbone, effective_text, options, prompt, &error)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "prompt failed: %s", error.c_str());
            return false;
        }
        std::vector<zonos3_probe_readout> readouts;
        zonos3_generate_features features;
        bool probe_enabled = false;
        if (use_title_alignment) {
            try {
                int text_start = -1;
                int text_length = 0;
                const auto & heads = chinese_laci_heads();
                probe_enabled = zonos3_locate_text_span(
                    g_runtime->backbone, prompt.ids.data(), prompt.n_rows, text_start, text_length);
                if (probe_enabled) {
                    for (const auto & head : heads.heads) {
                        features.probe.heads.push_back({head.layer, head.head});
                    }
                    features.probe.text_start = text_start;
                    features.probe.text_len = text_length;
                    features.readouts = &readouts;
                }
            } catch (const std::exception & probe_error) {
                __android_log_print(ANDROID_LOG_WARN, kTag, "title alignment probe unavailable: %s",
                                    probe_error.what());
                probe_enabled = false;
            }
        }
        if (filler_token && !probe_enabled) {
            // Never decode the server's filler without the probe that tells us
            // where to cut it. Fall back to the ordinary heading prompt.
            filler_token.reset();
            expanded_text.clear();
            if (!zonos3_build_prompt(g_runtime->backbone, synthesis_text, options, prompt, &error)) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "title fallback prompt failed: %s", error.c_str());
                return false;
            }
        }
        std::vector<int32_t> generated;
        int eos_frame = -1;
        const int budget = std::max(1, std::min(static_cast<int>(max_frames),
                                                 static_cast<int>(g_runtime->backbone.hp.n_ctx_train) - prompt.n_rows));
        const int frames = zonos3_generate(g_runtime->backbone, prompt, budget, sampling, 1.0f, generated, eos_frame,
                                           true, nullptr,
                                           [](int, const int32_t *, int) {
                                               return !g_cancelled.load();
                                           },
                                           nullptr,
                                           probe_enabled ? &features : nullptr);
        if (frames <= 0 || g_cancelled.load()) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "generation failed: frames=%d eos=%d text_bytes=%zu",
                                frames, eos_frame, effective_text.size());
            return false;
        }
        int decode_eos_frame = eos_frame;
        const int generated_frames = static_cast<int>(generated.size() /
                                                       static_cast<size_t>(g_runtime->backbone.hp.n_codebooks));
        if (filler_token && probe_enabled && !readouts.empty() && generated_frames > 0) {
            zonos3_short::ShortCut cutter(*filler_token, static_cast<int>(g_runtime->backbone.hp.n_codebooks));
            const int n_codebooks = static_cast<int>(g_runtime->backbone.hp.n_codebooks);
            for (int frame = 0; frame < generated_frames; ++frame) {
                // The first sampled row is produced from the prefill logits.
                // Readout frame 1 therefore belongs to the second generated
                // row, just like the server scheduler's LACI path.
                if (frame > 0 && frame - 1 < static_cast<int>(readouts.size())) {
                    cutter.observe_reading(frame, readouts[frame - 1].argmax);
                }
                std::vector<int32_t> row(
                    generated.begin() + static_cast<size_t>(frame) * n_codebooks,
                    generated.begin() + static_cast<size_t>(frame + 1) * n_codebooks);
                cutter.observe_row(row);
            }

            int keep_frames = generated_frames;
            const auto & decision = cutter.decided();
            const bool run_on = decision && decision->first == "run_on";
            if (run_on && allow_short_run_on) {
                short_run_on = true;
                return false;
            }
            if (decision && decision->first == "cut") {
                keep_frames = decision->second;
            } else if (cutter.enter()) {
                // Exact server fallback after the short-text retry budget is
                // exhausted: do not let the filler reach the decoder.
                keep_frames = std::min(
                    *cutter.enter(), generated_frames - (n_codebooks - 1));
            } else {
                __android_log_print(ANDROID_LOG_WARN, kTag,
                                    "short title alignment never reached the filler boundary");
                short_run_on = allow_short_run_on;
                return false;
            }
            keep_frames = std::clamp(keep_frames, 1, generated_frames);
            if (keep_frames < generated_frames) {
                generated.resize(static_cast<size_t>(keep_frames) * n_codebooks);
                const int cut_eos = prompt.prefill_frames + keep_frames;
                decode_eos_frame = decode_eos_frame >= 0 ? std::min(decode_eos_frame, cut_eos) : cut_eos;
            }
        }
        std::vector<int32_t> codes = zonos3_prefill_codes(g_runtime->backbone, prompt);
        codes.insert(codes.end(), generated.begin(), generated.end());
        if (!zac_decode(g_runtime->decoder, codes.data(), static_cast<int>(codes.size() / 10), 10,
                        decode_eos_frame, audio)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "ZAC decode failed: code_frames=%zu eos=%d",
                                codes.size() / 10, decode_eos_frame);
            return false;
        }
        const size_t drop = std::min(audio.size(), static_cast<size_t>(prompt.prefill_frames) *
                                                       zac_samples_per_frame(g_runtime->decoder));
        audio.erase(audio.begin(), audio.begin() + drop);
        const float gain = zac_output_gain(g_runtime->decoder, -1.0);
        for (float & sample : audio) sample *= gain;
        zonos3_soft_limit(audio.data(), audio.size());
        if (!filler_token && probe_enabled && !readouts.empty() && !generated.empty()) {
            // The server keeps a short tail after the reading position reaches
            // EOS. This preserves the final syllable without letting the model
            // continue into hallucinated title text.
            constexpr int kTitleTailFrames = 15;
            const int target = std::max(0, features.probe.text_len - 2);
            int reached = -1;
            int consecutive = 0;
            for (int i = 0; i < static_cast<int>(readouts.size()); ++i) {
                if (readouts[i].argmax >= target) {
                    if (++consecutive >= 2) {
                        reached = i + 1;
                        break;
                    }
                } else {
                    consecutive = 0;
                }
            }
            if (reached > 0) {
                const int generated_frames = static_cast<int>(generated.size() / 10);
                const int keep_frames = std::min(generated_frames, reached + kTitleTailFrames);
                const size_t samples_per_frame = zac_samples_per_frame(g_runtime->decoder);
                const size_t keep_samples = static_cast<size_t>(keep_frames) * samples_per_frame;
                if (keep_samples < audio.size()) audio.resize(keep_samples);
            }
        }
        if (audio.empty()) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "ZAC produced empty audio");
            return false;
        }
        return true;
    };

    if (title_mode) {
        std::vector<float> audio;
        const int max_short_text_retries = zonos3_short::registry().max_retries;
        for (int attempt = 0; attempt <= max_short_text_retries; ++attempt) {
            zonos3_sampling sampling;
            sampling.seed = static_cast<uint32_t>(seed) + static_cast<uint32_t>(attempt * 1000);
            bool short_run_on = false;
            if (generate_audio(requested_text, sampling, audio, true, attempt < max_short_text_retries,
                               short_run_on)) {
                return pcm16(env, audio);
            }
            if (g_cancelled.load()) return nullptr;
            if (!short_run_on) break;
        }
        return nullptr;
    }

    zonos3_sampling sampling;
    sampling.seed = static_cast<uint32_t>(seed);
    std::vector<float> audio;
    bool short_run_on = false;
    if (!generate_audio(requested_text, sampling, audio, false, false, short_run_on)) return nullptr;
    return pcm16(env, audio);
#endif
}
