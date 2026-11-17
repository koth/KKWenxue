#include "audio8.hpp"
#include <ATen/Context.h>
#include <ATen/cuda/CUDAGeneratorImpl.h>
#include <c10/cuda/CUDAGuard.h>
#include <c10/cuda/CUDACachingAllocator.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cuda_runtime_api.h>

namespace audio8 {
using Clock=std::chrono::steady_clock;
static double seconds(Clock::time_point t){return std::chrono::duration<double>(Clock::now()-t).count();}
static int graph_capacity(int64_t length){return length<=512?512:length<=1024?1024:2048;}
struct FrameProfile {
  std::vector<std::vector<cudaEvent_t>> frames;
  ~FrameProfile(){for(auto& frame:frames)for(auto event:frame)cudaEventDestroy(event);}
  void start(){frames.emplace_back(4);for(auto& event:frames.back())cudaEventCreate(&event);mark(0);}
  void mark(int index){cudaEventRecord(frames.back()[index],c10::cuda::getCurrentCUDAStream().stream());}
  Json report(){
    Json rows=Json::array();
    for(auto& frame:frames){float semantic=0,fast=0,slow=0;cudaEventElapsedTime(&semantic,frame[0],frame[1]);cudaEventElapsedTime(&fast,frame[1],frame[2]);cudaEventElapsedTime(&slow,frame[2],frame[3]);rows.push_back({{"semantic_ms",semantic},{"fast_ms",fast},{"slow_ms",slow}});}
    return rows;
  }
};
Engine::Engine(const Path& directory,const Path& codec_path,bool graphs,Event event,bool prefix_cache,bool compact_semantics,bool expanded_kv,bool dynamic_kv,bool fp16,bool fused_kernels)
 :tokenizer(directory/"tokenizer.json"),model_(directory,at::Device(at::kCUDA,0),compact_semantics,expanded_kv,dynamic_kv,fp16,fused_kernels),codec_path_(codec_path),event_(event),use_graphs_(graphs),use_prefix_cache_(prefix_cache){
  event_({{"stage","loading_codec"}});codec_=std::make_unique<Codec>(codec_path,model_.device);
}
bool Engine::load_voice(const Path& path){
  auto resolved=std::filesystem::canonical(path);
  auto modified=std::filesystem::last_write_time(resolved);auto size=std::filesystem::file_size(resolved);
  if(reference_prompt_.defined()&&voice_path_==resolved&&voice_time_==modified&&voice_size_==size&&
     std::filesystem::last_write_time(code_path_)==code_time_&&std::filesystem::file_size(code_path_)==code_size_)return true;
  // One resident voice bounds memory. A changed recording, transcript, or codes
  // invalidates both the GPU prompt and its KV snapshot before the next use.
  reference_prompt_=Tensor();reference_cache_.clear();
  auto voice=read_json(resolved);auto code_path=std::filesystem::u8path(voice.at("codes").get<std::string>());
  if(code_path.is_relative())code_path=resolved.parent_path()/code_path;
  auto reference=read_npy(code_path);
  if(reference.dim()!=2||reference.size(0)!=10||reference.size(1)<1||reference.min().item<int64_t>()<0||reference.max().item<int64_t>()>4095)throw std::runtime_error("Invalid reference codes");
  auto text=clean_text(voice.at("text").get<std::string>());
  if(text.empty())throw std::runtime_error("Missing matching reference transcript");
  auto tag=text.find("<|speaker:")==std::string::npos?"<|speaker:0|>"+text:text;
  std::vector<int64_t> prefix;
  // Tokenize parts separately, preserving the release's BPE boundaries.
  for(auto& part:std::vector<std::string>{"<|im_start|>system\n","convert the provided text to speech reference to the following:\n\nText:\n",tag,"\n\nSpeech:\n"}){
    auto tokens=tokenizer.encode(part);prefix.insert(prefix.end(),tokens.begin(),tokens.end());
  }
  auto prompt=model_.prompt(prefix,{},reference.to(model_.device));
  voice_=voice;voice_path_=resolved;voice_time_=modified;voice_size_=size;code_path_=code_path;
  code_time_=std::filesystem::last_write_time(code_path);code_size_=std::filesystem::file_size(code_path);
  reference_prompt_=prompt;return false;
}
void Engine::capture(const Sampling& options){
  if(!use_graphs_)return;
  if((model_.dynamic_kv?!slow_graphs_.empty():bool(slow_graph_))&&captured_.top_k==options.top_k&&captured_.top_p==options.top_p&&captured_.temperature==options.temperature)return;
  slow_graph_.reset();slow_graphs_.clear();slow_results_.clear();fast_graph_.reset();semantic_graph_.reset();captured_=options;
  auto began=Clock::now();auto integer=at::TensorOptions().device(model_.device).dtype(at::kLong);
  ids_=at::zeros({1,11,1},integer);ids_.select(1,0).fill_(model_.config["semantic_begin_id"].get<int>());
  position_=at::zeros({1},integer);rope_position_=at::zeros({1,1},integer);mask_=at::zeros({1,2048},integer);mask_.select(1,0).fill_(1);
  hidden_=at::zeros({1,1,896},integer.dtype(model_.dtype));semantic_=at::full({1},model_.config["semantic_begin_id"].get<int>(),integer);
  event_({{"stage","capturing_cuda_graphs"}});
  auto stream=c10::cuda::getStreamFromPool();cudaDeviceSynchronize();
  {
    c10::cuda::CUDAStreamGuard guard(stream);
    if(model_.dynamic_kv){
      for(int capacity:std::vector<int>{512,1024,2048}){
        model_.reset();auto bucket_mask=mask_.slice(1,0,capacity);
        for(int i=0;i<3;++i)model_.slow(ids_,position_,rope_position_,bucket_mask);
        stream.synchronize();
        auto graph=std::make_unique<at::cuda::CUDAGraph>();graph->register_generator_state(at::cuda::detail::getDefaultCUDAGenerator());
        graph->capture_begin();slow_results_[capacity]=model_.slow(ids_,position_,rope_position_,bucket_mask);graph->capture_end();
        slow_graphs_[capacity]=std::move(graph);
      }
    }else{
      for(int i=0;i<3;++i)model_.slow(ids_,position_,rope_position_,mask_);
      stream.synchronize();
      slow_graph_=std::make_unique<at::cuda::CUDAGraph>();slow_graph_->register_generator_state(at::cuda::detail::getDefaultCUDAGenerator());
      slow_graph_->capture_begin();auto result=model_.slow(ids_,position_,rope_position_,mask_);logits_=result.first;slow_hidden_=result.second;slow_graph_->capture_end();
    }
    // Capture fast only after all slow graphs; slow has no random operations.
    for(int i=0;i<3;++i)model_.fast(hidden_,semantic_,options);
    stream.synchronize();
    fast_graph_=std::make_unique<at::cuda::CUDAGraph>();fast_graph_->register_generator_state(at::cuda::detail::getDefaultCUDAGenerator());
    fast_graph_->capture_begin();codes_=model_.fast(hidden_,semantic_,options);fast_graph_->capture_end();stream.synchronize();
    if(model_.fused_kernels){
      int vocab=model_.compact_semantics?model_.config["semantic_end_id"].get<int>()-model_.config["semantic_begin_id"].get<int>()+2:model_.config["vocab_size"].get<int>();
      sample_logits_=at::zeros({1,vocab},hidden_.options());
      sample_previous_=at::zeros({1,model_.config["ras_window_size"].get<int>()},integer);
      for(int i=0;i<3;++i)model_.semantic(sample_logits_,sample_previous_,options);
      stream.synchronize();
      semantic_graph_=std::make_unique<at::cuda::CUDAGraph>();semantic_graph_->register_generator_state(at::cuda::detail::getDefaultCUDAGenerator());
      semantic_graph_->capture_begin();sampled_semantic_=model_.semantic(sample_logits_,sample_previous_,options);semantic_graph_->capture_end();stream.synchronize();
    }
  }
  event_({{"stage","cuda_graphs_ready"},{"seconds",seconds(began)}});
}
std::pair<Tensor,Tensor> Engine::slow(const Tensor& ids,const Tensor& pos,const Tensor& rope_pos,const Tensor& mask){
  if(!use_graphs_||ids.size(-1)!=1)return model_.slow(ids,pos,rope_pos,mask);
  ids_.copy_(ids);position_.copy_(pos);rope_position_.copy_(rope_pos);mask_.zero_();mask_.slice(1,0,mask.size(1)).copy_(mask);
  if(model_.dynamic_kv){
    int capacity=graph_capacity(mask.size(1));slow_graphs_.at(capacity)->replay();return slow_results_.at(capacity);
  }
  slow_graph_->replay();return {logits_,slow_hidden_};
}
Tensor Engine::fast(const Tensor& hidden,const Tensor& semantic,const Sampling& options){
  if(!use_graphs_)return model_.fast(hidden,semantic,options);
  hidden_.copy_(hidden);semantic_.copy_(semantic);fast_graph_->replay();return codes_.clone();
}
Tensor Engine::semantic(const Tensor& logits,const Tensor& previous,const Sampling& options){
  if(!semantic_graph_)return model_.semantic(logits,previous,options);
  sample_logits_.copy_(logits);
  // Speech/EOS ids cannot match zero. This also preserves the first frame's
  // behavior when the release has no repetition window yet.
  if(previous.defined())sample_previous_.copy_(previous);else sample_previous_.zero_();
  semantic_graph_->replay();return sampled_semantic_;
}
Json Engine::inspect()const{
  size_t free=0,total=0;cudaMemGetInfo(&free,&total);cudaDeviceProp device{};cudaGetDeviceProperties(&device,0);
  auto stats=c10::cuda::CUDACachingAllocator::getDeviceStats(0);
  return {{"backend","libtorch_cpp_cuda"},
    {"inference_revision",model_.fused_kernels?"fused-decode-v6":(model_.fp16?"fp16-expanded-kv-v5":(model_.dynamic_kv?"bucket-kv-v4":(model_.expanded_kv?"expanded-kv-v3":"compact-prefix-v2")))},
    {"fused_kernels",model_.fused_kernels},{"scaled_fp32_keys",model_.fused_kernels&&model_.expanded_kv&&model_.dtype==at::kBFloat16},
    {"fp16_model",model_.fp16},{"expanded_fp32_kv",model_.expanded_kv},{"dynamic_kv",model_.dynamic_kv},
    {"attention_graph_buckets",model_.dynamic_kv?Json::array({512,1024,2048}):Json::array()},
    {"compact_semantics",model_.compact_semantics},{"reference_prefix_cache",use_prefix_cache_},
    {"python_runtime",false},{"graphs",use_graphs_},{"dtype",c10::toString(model_.dtype)},
    {"sample_rate",44100},{"context",2048},{"batch_size",1},{"gpu",device.name},
    {"compute_capability",std::to_string(device.major)+"."+std::to_string(device.minor)},
    {"cuda_free_bytes",free},{"cuda_total_bytes",total},{"peak_tensor_bytes",stats.allocated_bytes[0].peak},{"peak_reserved_bytes",stats.reserved_bytes[0].peak}};
}
Json Engine::synthesize(const Json& request){
  auto started=Clock::now();Sampling options;
  options.seed=request.value("seed",uint64_t(111135));options.temperature=request.value("temperature",0.7);options.top_p=request.value("top_p",0.9);options.top_k=request.value("top_k",50);
  if(!std::isfinite(options.temperature)||options.temperature<=0||!std::isfinite(options.top_p)||options.top_p<=0||options.top_p>1||options.top_k<1||options.top_k>4096)throw std::runtime_error("Invalid sampling parameters");
  double pause=request.value("pause",0.18);if(!std::isfinite(pause)||pause<0||pause>3)throw std::runtime_error("Invalid pause");
  auto text=request.at("text").get<std::string>();auto pieces=sentences(text);if(pieces.empty())throw std::runtime_error("Empty synthesis text");
  if(!request.value("split_sentences",true))pieces={text};
  auto directory=std::filesystem::u8path(request.at("output_dir").get<std::string>());
  if(std::filesystem::exists(directory/"report.json"))throw std::runtime_error("Output directory already contains a report; choose a new directory");
  std::filesystem::create_directories(directory);
  auto prepared=Clock::now();bool voice_hit=load_voice(std::filesystem::u8path(request.at("voice").get<std::string>()));
  cudaDeviceSynchronize();double voice_seconds=seconds(prepared);
  auto encode_parts=[&](const std::vector<std::string>& parts){std::vector<int64_t> result;for(auto& part:parts){auto tokens=tokenizer.encode(part);result.insert(result.end(),tokens.begin(),tokens.end());}return result;};
  capture(options);
  bool prefix_hit=use_prefix_cache_&&!reference_cache_.empty();double reference_seconds=0;
  int64_t prefix_width=reference_prompt_.size(-1);
  if(use_prefix_cache_&&!prefix_hit){
    auto begin=Clock::now();model_.reset();
    auto positions=at::arange(prefix_width,reference_prompt_.options());
    model_.slow(reference_prompt_,positions,positions.unsqueeze(0),at::ones({1,prefix_width},reference_prompt_.options()));
    for(auto& cache:model_.slow_cache)reference_cache_.push_back({cache.keys.slice(2,0,prefix_width).clone(),cache.values.slice(2,0,prefix_width).clone()});
    cudaDeviceSynchronize();reference_seconds=seconds(begin);
  }
  Json cache_info={{"voice_hit",voice_hit},{"prefix_hit",prefix_hit},{"voice_prepare_seconds",voice_seconds},{"reference_prefill_seconds",reference_seconds},{"prefix_positions",prefix_width}};
  Json report={{"state","running"},{"engine",inspect()},{"request",request},{"voice",voice_},{"reference_cache",cache_info},{"fragments",Json::array()}};
  write_json(directory/"report.json",report);std::vector<Tensor> audio;double generation_total=reference_seconds,decode_total=0;int64_t saved_samples=0;
  try{
    for(size_t index=0;index<pieces.size();++index){
      auto suffix=encode_parts({"<|im_end|>\n<|im_start|>user\n",clean_text(pieces[index]),"<|im_end|>\n<|im_start|>assistant\n<|voice|>"});
      auto empty_reference=at::empty({10,0},reference_prompt_.options());
      auto prompt=model_.prompt({},suffix,empty_reference);int64_t width=prefix_width+prompt.size(-1),budget=2048-width;
      if(budget<100)throw std::runtime_error("Insufficient generation context; shorten the reference or text");
      at::manual_seed(options.seed+index);auto begin=Clock::now();
      int64_t input_start=0;
      if(use_prefix_cache_){
        for(size_t i=0;i<reference_cache_.size();++i){
          model_.slow_cache[i].keys.slice(2,0,prefix_width).copy_(reference_cache_[i].keys);
          model_.slow_cache[i].values.slice(2,0,prefix_width).copy_(reference_cache_[i].values);
        }
        input_start=prefix_width;
      }else{model_.reset();prompt=at::cat({reference_prompt_,prompt},-1);}
      if(request.value("diagnostics",false)&&!use_graphs_){model_.diagnostics=directory;model_.diagnostic_sample=0;}
      auto mask=at::ones({1,width},prompt.options());auto position=at::arange(input_start,width,prompt.options());auto output=slow(prompt,position,position.unsqueeze(0),mask);
      cudaDeviceSynchronize();double prefill=seconds(begin);auto decoding=Clock::now();
      Tensor previous;std::vector<Tensor> frames;bool eos=false;
      FrameProfile profile;int profiling=std::clamp(request.value("profile_frames",0),0,10);
      event_({{"stage","generating"},{"fragment",index+1},{"total",pieces.size()},{"prompt_positions",width}});
      for(int64_t step=0;step<budget;++step){
        if(request.contains("cancel_file")&&std::filesystem::exists(std::filesystem::u8path(request["cancel_file"].get<std::string>())))throw std::runtime_error("Synthesis cancelled");
        bool measured=step<profiling;if(measured)profile.start();
        auto semantic=this->semantic(output.first,previous,options);if(measured)profile.mark(1);
        auto codes=fast(output.second,semantic,options);if(measured)profile.mark(2);
        if(semantic.item<int64_t>()==model_.config["eos_token_id"].get<int>()){if(measured)profile.mark(3);eos=true;break;}
        frames.push_back(codes);
        if(request.value("diagnostics",false)&&step==0){write_npy(directory/"diagnostic-first-frame.npy",codes);throw std::runtime_error("Diagnostic stop after first frame; no audio published");}
        if(!previous.defined())previous=at::zeros({1,model_.config["ras_window_size"].get<int>()},prompt.options());
        else{previous=at::roll(previous,{-1},{1});previous.select(1,previous.size(1)-1).copy_(semantic);}
        auto column=at::cat({semantic.unsqueeze(1),codes},1).unsqueeze(-1);
        mask=at::cat({mask,at::ones({1,1},prompt.options())},1);position=at::full({1},width+step,prompt.options());
        output=slow(column,position,position.unsqueeze(0),mask);
        if(measured)profile.mark(3);
        if(step%40==0)event_({{"stage","progress"},{"fragment",index+1},{"frames",step+1},{"unsaved_audio_seconds",(step+1)*2048.0/44100}});
      }
      cudaDeviceSynchronize();double autoregressive=seconds(decoding),generation=seconds(begin);generation_total+=generation;
      if(!eos||frames.empty())throw std::runtime_error("No complete EOS before context limit; audio not published");
      auto codes=at::stack(frames,2);auto base=std::to_string(index+1);write_npy(directory/(base+"-codes.npy"),codes);
      begin=Clock::now();auto waveform=codec_->decode(codes);cudaDeviceSynchronize();
      auto cpu=waveform.to(at::kCPU);if(!at::isfinite(cpu).all().item<bool>()||cpu.abs().max().item<float>()<0.001f)throw std::runtime_error("Invalid or silent waveform");
      write_wav(directory/(base+".wav"),cpu);double decode=seconds(begin);decode_total+=decode;
      if(!audio.empty())audio.push_back(at::zeros({int64_t(std::llround(pause*44100))},at::TensorOptions().dtype(at::kFloat)));audio.push_back(cpu);saved_samples+=cpu.numel();
      report["fragments"].push_back({{"index",index+1},{"text",pieces[index]},{"eos",true},{"frames",codes.size(-1)},{"audio_seconds",cpu.numel()/44100.0},{"prefill_seconds",prefill},{"autoregressive_seconds",autoregressive},{"generation_seconds",generation},{"decode_and_save_seconds",decode},{"path",(directory/(base+".wav")).u8string()}});
      if(profiling)report["fragments"].back()["frame_profile"]=profile.report();
      event_({{"stage","fragment_saved"},{"fragment",index+1},{"audio_seconds",cpu.numel()/44100.0},{"generation_seconds",generation}});write_json(directory/"report.json",report);
    }
    auto waveform=at::cat(audio);double duration=waveform.numel()/44100.0;
    if(duration<request.value("minimum_seconds",0.0))throw std::runtime_error("Complete audio is below requested minimum duration");
    auto path=directory/"audio8.wav";write_wav(path,waveform);
    report.update(Json{{"state","completed"},{"engine",inspect()},{"audio_seconds",duration},{"generation_seconds",generation_total},{"decode_and_save_seconds",decode_total},{"request_seconds",seconds(started)},{"wav",path.u8string()},{"text",text}});
    write_json(directory/"report.json",report);event_({{"stage","completed"},{"audio_seconds",duration},{"generation_seconds",generation_total},{"request_seconds",seconds(started)}});return report;
  }catch(const std::exception& e){report.update(Json{{"state","failed"},{"error",e.what()},{"request_seconds",seconds(started)}});write_json(directory/"report.json",report);throw;}
}
} // namespace audio8
