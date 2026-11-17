#pragma once
#include <ATen/ATen.h>
#include <ATen/cuda/CUDAGraph.h>
#include <nlohmann/json.hpp>
#include <filesystem>
#include <functional>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace audio8 {
using Tensor = at::Tensor;
using Json = nlohmann::json;
using Path = std::filesystem::path;
using Weights = std::unordered_map<std::string, Tensor>;
using Event = std::function<void(const Json&)>;
std::string read_text(const Path& path);
Json read_json(const Path& path);
void write_json(const Path& path, const Json& value);
Weights load_safetensors(const Path& path, const at::Device& device);
void write_wav(const Path& path, const Tensor& waveform, int rate=44100);
void write_npy(const Path& path, const Tensor& tensor);
Tensor read_npy(const Path& path);
void write_float_raw(const Path& path, const Tensor& tensor);
std::string clean_text(const std::string& text);
std::vector<std::string> sentences(const std::string& text);

class Tokenizer {
 public:
  explicit Tokenizer(const Path& path);
  ~Tokenizer();
  std::vector<int64_t> encode(const std::string& text) const;
 private:
  std::unordered_map<std::string, int64_t> vocab_, specials_;
  std::unordered_map<std::string, int> ranks_;
  std::string bytes_[256];
  void* regex_ = nullptr;
  void bpe(const std::string& text, std::vector<int64_t>& ids) const;
};

Tensor rope(int length, int dim, double base, const at::Device& device);
Tensor apply_rope(const Tensor& x, const Tensor& values, bool fused=false);
Tensor norm(const Tensor& x, const Tensor& weight, double eps, bool fused=false);
Tensor linear(const Tensor& x, const Weights& w, const std::string& name);
Tensor feed_forward(const Tensor& x, const Weights& w, const std::string& prefix, bool fused=false);
struct Cache { Tensor keys, values; };
struct Sampling { double temperature=0.7, top_p=0.9; int top_k=50; uint64_t seed=111135; };

class Model {
 public:
  explicit Model(const Path& directory, at::Device device, bool compact_semantics=true, bool expanded_kv=true, bool dynamic_kv=false, bool fp16=false, bool fused_kernels=false);
  std::pair<Tensor, Tensor> slow(const Tensor& ids, const Tensor& position, const Tensor& rope_position, const Tensor& mask);
  Tensor fast(const Tensor& hidden, const Tensor& semantic, const Sampling& options);
  Tensor semantic(const Tensor& logits, const Tensor& previous, const Sampling& options);
  Tensor prompt(const std::vector<int64_t>& prefix, const std::vector<int64_t>& suffix, const Tensor& reference);
  void reset();
  Json config;
  Weights weights;
  at::Device device;
  at::ScalarType dtype;
  std::vector<Cache> slow_cache, fast_cache;
  Tensor slow_rope, fast_rope, positions, fast_positions, vocab_positions;
  Path diagnostics;
  int diagnostic_sample=0;
  bool compact_semantics;
  bool expanded_kv;
  bool dynamic_kv;
  bool fp16;
  bool fused_kernels;
 private:
  Tensor semantic_weights_, semantic_ids_;
  Tensor attention(const Tensor& x, const std::string& name, const Tensor& values, const Tensor& mask, Cache& cache, const Tensor& position, bool fast);
  Tensor filtered(const Tensor& scores, int k, double p, double temperature);
  Tensor sample(const Tensor& scores);
};

class Codec {
 public:
  explicit Codec(const Path& path, at::Device device);
  Tensor decode(const Tensor& codes);
  Weights weights;
 private:
  Tensor conv(const Tensor& x, const std::string& name, int dilation=1, int stride=1, int groups=1);
  Tensor transpose(const Tensor& x, const std::string& name, int stride);
  Tensor snake(const Tensor& x, const std::string& name);
  Tensor residual(const Tensor& x, const std::string& name, int dilation);
  Tensor convnext(const Tensor& x, const std::string& name);
  Tensor transformer(const Tensor& x);
};

class Engine {
 public:
  Engine(const Path& directory, const Path& codec_path, bool graphs, Event event, bool prefix_cache=true, bool compact_semantics=true, bool expanded_kv=true, bool dynamic_kv=false, bool fp16=false, bool fused_kernels=false);
  Json synthesize(const Json& request);
  Json inspect() const;
  Tokenizer tokenizer;
 private:
  Model model_;
  std::unique_ptr<Codec> codec_;
  Path codec_path_;
  Event event_;
  bool use_graphs_;
  bool use_prefix_cache_;
  Path voice_path_, code_path_;
  std::filesystem::file_time_type voice_time_, code_time_;
  uintmax_t voice_size_=0, code_size_=0;
  Json voice_;
  Tensor reference_prompt_;
  std::vector<Cache> reference_cache_;
  bool load_voice(const Path& path);
  Sampling captured_;
  std::unique_ptr<at::cuda::CUDAGraph> slow_graph_, fast_graph_, semantic_graph_;
  std::unordered_map<int,std::unique_ptr<at::cuda::CUDAGraph>> slow_graphs_;
  std::unordered_map<int,std::pair<Tensor,Tensor>> slow_results_;
  Tensor ids_, position_, rope_position_, mask_, hidden_, semantic_, codes_, logits_, slow_hidden_;
  Tensor sample_logits_, sample_previous_, sampled_semantic_;
  void capture(const Sampling& options);
  std::pair<Tensor,Tensor> slow(const Tensor& ids, const Tensor& position, const Tensor& rope_position, const Tensor& mask);
  Tensor fast(const Tensor& hidden, const Tensor& semantic, const Sampling& options);
  Tensor semantic(const Tensor& logits, const Tensor& previous, const Sampling& options);
};
} // namespace audio8
