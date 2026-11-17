#include "audio8.hpp"
#include <ATen/Context.h>
#include <cmath>
#include <limits>
#ifdef AUDIO8_CUDA_KERNELS
#include "kernels.hpp"
#endif

namespace audio8 {
using namespace at::indexing;
Tensor rope(int length,int dim,double base,const at::Device& device){
  auto options=at::TensorOptions().dtype(at::kFloat).device(device);
  auto frequencies=1.0/at::pow(base,at::arange(0,dim,2,options)/dim);
  auto phases=at::outer(at::arange(length,options),frequencies);
  auto values=at::polar(at::ones_like(phases),phases);
  return at::stack({at::real(values),at::imag(values)},-1).to(at::kBFloat16);
}
Tensor apply_rope(const Tensor& x,const Tensor& values,bool fused){
#ifdef AUDIO8_CUDA_KERNELS
  if(fused)return fused_rope(x,values);
#endif
  auto v=values.dim()==3?values.unsqueeze(0).unsqueeze(2):values.unsqueeze(2);
  auto shaped=x.to(at::kFloat).reshape({x.size(0),x.size(1),x.size(2),-1,2});
  auto real=shaped.select(-1,0)*v.select(-1,0)-shaped.select(-1,1)*v.select(-1,1);
  auto imag=shaped.select(-1,1)*v.select(-1,0)+shaped.select(-1,0)*v.select(-1,1);
  return at::stack({real,imag},-1).flatten(3).to(x.scalar_type());
}
Tensor norm(const Tensor& x,const Tensor& w,double eps,bool fused){
#ifdef AUDIO8_CUDA_KERNELS
  if(fused&&x.numel()==x.size(-1))return fused_norm(x,w,eps);
#endif
  auto f=x.to(at::kFloat);return (f*at::rsqrt(f.pow(2).mean(-1,true)+eps)).to(x.scalar_type())*w;
}
Tensor linear(const Tensor& x,const Weights& w,const std::string& name){auto b=w.find(name+".bias");return at::linear(x,w.at(name+".weight"),b==w.end()?Tensor():b->second);}
Tensor feed_forward(const Tensor& x,const Weights& w,const std::string& p,bool fused){
#ifdef AUDIO8_CUDA_KERNELS
  if(fused){
    if(x.numel()==896&&w.count(p+".w13.weight")){
      auto projected=linear(x,w,p+".w13");auto parts=projected.chunk(2,-1);
      return linear(fused_silu_multiply(parts[0],parts[1]),w,p+".w2");
    }
    return linear(fused_silu_multiply(linear(x,w,p+".w1"),linear(x,w,p+".w3")),w,p+".w2");
  }
#endif
  return linear(at::silu(linear(x,w,p+".w1"))*linear(x,w,p+".w3"),w,p+".w2");
}

Model::Model(const Path& directory,at::Device device,bool compact_semantics,bool expanded_kv,bool dynamic_kv,bool fp16,bool fused_kernels):device(device),compact_semantics(compact_semantics),expanded_kv(expanded_kv),dynamic_kv(dynamic_kv),fp16(fp16),fused_kernels(fused_kernels){
#ifndef AUDIO8_CUDA_KERNELS
  if(fused_kernels)throw std::runtime_error("Rebuild with AUDIO8_CUDA_KERNELS=ON to use --fused-kernels");
#endif
  config=read_json(directory/"config.json");
  if(config.value("model_type",std::string())!="arktts" || config["num_codebooks"]!=10 || config["dim"]!=896 || config["max_seq_len"]!=2048)throw std::runtime_error("Unsupported Audio8 model configuration");
  weights=load_safetensors(directory/"model.safetensors",device);
  if(fp16)for(auto& item:weights)if(item.second.scalar_type()==at::kBFloat16)item.second=item.second.to(at::kHalf);
  dtype=weights.at("embeddings.weight").scalar_type();
  if(fused_kernels){
    if(dtype!=at::kBFloat16&&dtype!=at::kHalf)throw std::runtime_error("Fused kernels require BF16 or FP16 weights");
    if(dtype==at::kBFloat16){
      for(auto& group:std::vector<std::pair<std::string,int>>{{"layers",config["n_layer"]},{"fast_layers",config["n_fast_layer"]}}){
        for(int i=0;i<group.second;++i){
          auto p=group.first+"."+std::to_string(i)+".feed_forward";
          auto gate=weights.at(p+".w1.weight"),up=weights.at(p+".w3.weight");
          auto packed=at::cat({gate,up},0);weights[p+".w13.weight"]=packed;
          // Prefill retains the original two projection shapes. Views into the
          // packed storage avoid keeping a second copy of these large weights.
          weights[p+".w1.weight"]=packed.slice(0,0,gate.size(0));
          weights[p+".w3.weight"]=packed.slice(0,gate.size(0));
          if(weights.count(p+".w1.bias")||weights.count(p+".w3.bias")){
            auto bias=at::cat({weights.count(p+".w1.bias")?weights.at(p+".w1.bias"):at::zeros({gate.size(0)},gate.options()),
                              weights.count(p+".w3.bias")?weights.at(p+".w3.bias"):at::zeros({up.size(0)},up.options())});
            weights[p+".w13.bias"]=bias;
          }
        }
      }
    }
  }
  // The release precomputes these buffers on CPU then rounds to BF16.
  slow_rope=rope(config["max_seq_len"],config["head_dim"],config["rope_base"],at::kCPU).to(device);
  fast_rope=rope(config["num_codebooks"],config["fast_head_dim"],config["rope_base"],at::kCPU).to(device);
  auto options=at::TensorOptions().device(device).dtype(at::kLong);
  positions=at::arange(config["max_seq_len"].get<int>(),options);fast_positions=at::arange(10,options);vocab_positions=at::arange(config["vocab_size"].get<int>(),options);
  if(compact_semantics){
    int begin=config["semantic_begin_id"],end=config["semantic_end_id"],eos=config["eos_token_id"];
    semantic_ids_=at::cat({at::arange(begin,end+1,options),at::full({1},eos,options)});
    // Only these 4,097 tokens can be sampled during speech generation. Keep
    // their rows contiguous instead of projecting the 155k text vocabulary.
    semantic_weights_=weights.at("embeddings.weight").index_select(0,semantic_ids_);
  }
  int cache_heads=config[expanded_kv?"n_head":"n_local_heads"];
  auto cache_options=options.dtype(expanded_kv?at::kFloat:dtype);
  for(int i=0;i<config["n_layer"].get<int>();++i)slow_cache.push_back({at::zeros({1,cache_heads,2048,64},cache_options),at::zeros({1,cache_heads,2048,64},cache_options)});
  for(int i=0;i<config["n_fast_layer"].get<int>();++i)fast_cache.push_back({at::zeros({1,config[fused_kernels?"fast_n_head":"fast_n_local_heads"].get<int>(),10,64},options.dtype(dtype)),at::zeros({1,config[fused_kernels?"fast_n_head":"fast_n_local_heads"].get<int>(),10,64},options.dtype(dtype))});
}
void Model::reset(){for(auto& c:slow_cache){c.keys.zero_();c.values.zero_();}for(auto& c:fast_cache){c.keys.zero_();c.values.zero_();}}
Tensor Model::attention(const Tensor& x,const std::string& name,const Tensor& values,const Tensor& mask,Cache& cache,const Tensor& position,bool fast){
  int heads=config[fast?"fast_n_head":"n_head"],local=config[fast?"fast_n_local_heads":"n_local_heads"],dim=64;
  bool scaled_keys=!fast&&fused_kernels&&expanded_kv&&dtype==at::kBFloat16;
  double key_scale=scaled_keys?std::sqrt(1.0/std::sqrt(double(dim))):1.0;
  auto packed=linear(x,weights,name+".wqkv");Tensor q,k,v;
#ifdef AUDIO8_CUDA_KERNELS
  if(fused_kernels&&x.size(1)==1&&(fast||expanded_kv)){
    q=fused_qkv_cache(packed,values,cache.keys,cache.values,position,heads,local,float(key_scale));
    k=cache.keys;v=cache.values;
  }else
#endif
  {
    auto qkv=packed.split_with_sizes({heads*dim,local*dim,local*dim},-1);
    q=apply_rope(qkv[0].reshape({1,x.size(1),heads,dim}),values,fused_kernels).transpose(1,2);
    k=apply_rope(qkv[1].reshape({1,x.size(1),local,dim}),values,fused_kernels).transpose(1,2);
    v=qkv[2].reshape({1,x.size(1),local,dim}).transpose(1,2);
    if(!fast&&expanded_kv){
    // The math SDPA path promotes BF16 KV to FP32 on every decode. Store the
    // promoted, expanded heads once, then update only the new positions. Keep
    // the original full attention width and BF16 output rounding unchanged.
    auto stored_keys=k.to(at::kFloat).repeat_interleave(heads/local,1);
    if(scaled_keys)stored_keys=stored_keys*key_scale;
    cache.keys.index_put_({Slice(),Slice(),position},stored_keys);
    cache.values.index_put_({Slice(),Slice(),position},v.to(at::kFloat).repeat_interleave(heads/local,1));
    k=cache.keys;v=cache.values;
    }else{
    cache.keys.index_put_({Slice(),Slice(),position},k);cache.values.index_put_({Slice(),Slice(),position},v);
    k=cache.keys.repeat_interleave(heads/local,1);v=cache.values.repeat_interleave(heads/local,1);
    }
  }
  if(!fast&&dynamic_kv){
    // The mask width is the exact valid KV length. Slicing after the update is
    // mathematically equivalent to masking the unused zero-filled tail.
    k=k.slice(2,0,mask.size(-1));v=v.slice(2,0,mask.size(-1));
  }
  Tensor output;
  if(fast){auto scores=at::matmul(q,k.transpose(-2,-1))/std::sqrt(double(dim));scores=scores.masked_fill(mask.logical_not(),-std::numeric_limits<double>::infinity());output=at::matmul(at::softmax(scores,-1),v);}
  else if(scaled_keys&&x.size(1)==1){
    // Math SDPA scales Q and K separately in FP32. The cached K already has
    // precisely that factor; avoid rescaling the full 2048-position buffer.
    auto scores=at::matmul(q.to(at::kFloat)*key_scale,k.transpose(-2,-1));
    scores.masked_fill_(mask.logical_not(),-std::numeric_limits<double>::infinity());
    output=at::matmul(at::softmax(scores,-1),v).to(q.scalar_type());
  }else if(expanded_kv&&x.size(1)==1)output=at::scaled_dot_product_attention(q.to(at::kFloat),k,v,mask,0.0,false).to(q.scalar_type());
  else{
    // Recover the original BF16 keys for the unchanged prefill SDPA backend.
    if(scaled_keys)k=k/key_scale;
    output=at::scaled_dot_product_attention(q,k.to(q.scalar_type()),v.to(q.scalar_type()),mask,0.0,false);
  }
  return linear(output.transpose(1,2).contiguous().reshape({1,x.size(1),heads*dim}),weights,name+".wo");
}
std::pair<Tensor,Tensor> Model::slow(const Tensor& ids,const Tensor& position,const Tensor& rope_position,const Tensor& attention_mask){
  std::vector<Tensor> embeds;for(int i=0;i<10;++i)embeds.push_back(at::embedding(weights.at("codebook_embeddings.weight"),ids.select(1,i+1)+i*4096));
  auto semantic=ids.select(1,0);auto valid=semantic.ge(config["semantic_begin_id"].get<int>())&semantic.le(config["semantic_end_id"].get<int>());
  auto hidden=at::embedding(weights.at("embeddings.weight"),semantic)+at::where(valid.unsqueeze(-1),at::stack(embeds,1).sum(1),0.0);
  if(!diagnostics.empty()&&ids.size(-1)>1)write_float_raw(diagnostics/"embedding.raw",hidden);
  auto values=slow_rope.index_select(0,rope_position.flatten()).reshape({1,ids.size(-1),32,2});
  auto padded=dynamic_kv?attention_mask:(attention_mask.size(1)==2048?attention_mask:at::constant_pad_nd(attention_mask,{0,2048-attention_mask.size(1)},0));
  auto available_positions=dynamic_kv?positions.slice(0,0,padded.size(1)):positions;
  auto mask=(available_positions.unsqueeze(0)<=position.unsqueeze(1)).unsqueeze(0).unsqueeze(0)&padded.unsqueeze(1).unsqueeze(1).to(at::kBool);
  // Match the release: prefill uses the default SDPA backend; decode uses math.
  auto& context=at::globalContext();bool flash=context.userEnabledFlashSDP(),efficient=context.userEnabledMemEfficientSDP(),cudnn=context.userEnabledCuDNNSDP();
  if(ids.size(-1)==1){context.setSDPUseFlash(false);context.setSDPUseMemEfficient(false);context.setSDPUseCuDNN(false);}
  try{
    for(int i=0;i<config["n_layer"].get<int>();++i){
      auto p="layers."+std::to_string(i);
      auto normalized=norm(hidden,weights.at(p+".attention_norm.weight"),config["norm_eps"],fused_kernels);
      if(i==0&&!diagnostics.empty())write_float_raw(diagnostics/"attention-normalized.raw",normalized);
      auto attn=attention(normalized,p+".attention",values,mask,slow_cache[i],position,false);
      if(i==0&&!diagnostics.empty())write_float_raw(diagnostics/"attention-output.raw",attn);
      hidden=hidden+attn;hidden=hidden+feed_forward(norm(hidden,weights.at(p+".ffn_norm.weight"),config["norm_eps"],fused_kernels),weights,p+".feed_forward",fused_kernels);
      if(!diagnostics.empty()&&ids.size(-1)>1)write_float_raw(diagnostics/("layer-"+std::to_string(i)+".raw"),hidden.select(1,hidden.size(1)-1));
    }
  }catch(...){context.setSDPUseFlash(flash);context.setSDPUseMemEfficient(efficient);context.setSDPUseCuDNN(cudnn);throw;}
  context.setSDPUseFlash(flash);context.setSDPUseMemEfficient(efficient);context.setSDPUseCuDNN(cudnn);
  hidden=hidden.slice(1,hidden.size(1)-1);auto normalized=norm(hidden,weights.at("norm.weight"),config["norm_eps"],fused_kernels);
  auto logits=at::linear(normalized,compact_semantics?semantic_weights_:weights.at("embeddings.weight")).select(1,0);
  if(!diagnostics.empty()&&ids.size(-1)>1){write_float_raw(diagnostics/"prefill-logits.raw",logits);write_float_raw(diagnostics/"prefill-hidden.raw",normalized);write_float_raw(diagnostics/"rope.raw",slow_rope);}
  return {logits,config["norm_fastlayer_input"].get<bool>()?normalized:hidden};
}
Tensor Model::filtered(const Tensor& scores,int k,double p,double temperature){
  auto ordered=at::sort(scores,-1,true);auto sorted=std::get<0>(ordered),indices=std::get<1>(ordered);
  auto cumulative=at::cumsum(at::softmax(sorted,-1),-1);
  auto remove=(cumulative>at::full({},p,scores.options()))|(vocab_positions.slice(0,0,scores.size(-1))>=k);
  remove.select(-1,0).fill_(false);auto mask=at::zeros_like(remove).scatter(1,indices,remove);
  return scores.masked_fill(mask,-std::numeric_limits<double>::infinity())/at::full({},temperature,scores.options()).clamp_min(1e-5);
}
Tensor Model::sample(const Tensor& scores){
  auto probabilities=at::softmax(scores,-1);auto random=at::rand_like(probabilities);
  if(!diagnostics.empty()){auto name=std::to_string(diagnostic_sample++);write_float_raw(diagnostics/("probabilities-"+name+".raw"),probabilities);write_float_raw(diagnostics/("random-"+name+".raw"),random);}
  return (probabilities/(-at::log(random))).argmax(-1);
}
Tensor Model::semantic(const Tensor& logits,const Tensor& previous,const Sampling& options){
  int begin=config["semantic_begin_id"],end=config["semantic_end_id"],eos=config["eos_token_id"];
  auto scores=logits;
  if(!compact_semantics){
    scores=at::full_like(logits,-std::numeric_limits<double>::infinity());
    scores.slice(-1,begin,end+1).copy_(logits.slice(-1,begin,end+1));scores.select(-1,eos).copy_(logits.select(-1,eos));
  }
  auto normal=sample(filtered(scores,options.top_k,options.top_p,options.temperature));
  auto high=sample(filtered(scores,options.top_k,config["ras_top_p"],config["ras_temperature"]));
  if(compact_semantics){normal=semantic_ids_.index_select(0,normal);high=semantic_ids_.index_select(0,high);}
  if(!previous.defined())return normal;
  auto repeated=(previous==normal.unsqueeze(1)).any(1);return at::where(repeated&normal.ge(begin)&normal.le(end),high,normal);
}
Tensor Model::fast(const Tensor& slow_hidden,const Tensor& semantic,const Sampling& options){
  auto step=[&](Tensor hidden,int position){
    auto pos=fast_positions.slice(0,position,position+1);auto values=fast_rope.index_select(0,pos);
    auto mask=(fast_positions.unsqueeze(0)<=pos.unsqueeze(1)).unsqueeze(0).unsqueeze(0);
    for(int i=0;i<config["n_fast_layer"].get<int>();++i){auto p="fast_layers."+std::to_string(i);hidden=hidden+attention(norm(hidden,weights.at(p+".attention_norm.weight"),config["norm_eps"],fused_kernels),p+".attention",values,mask,fast_cache[i],pos,true);hidden=hidden+feed_forward(norm(hidden,weights.at(p+".ffn_norm.weight"),config["norm_eps"],fused_kernels),weights,p+".feed_forward",fused_kernels);}
    return linear(norm(hidden,weights.at("fast_norm.weight"),config["norm_eps"],fused_kernels),weights,"fast_output").select(1,hidden.size(1)-1);
  };
  auto hidden=weights.count("fast_project_in.weight")?linear(slow_hidden,weights,"fast_project_in"):slow_hidden;
  step(hidden,0);auto current=(semantic-config["semantic_begin_id"].get<int>()).clamp(0,4095);std::vector<Tensor> codes{current};
  for(int position=1;position<10;++position){hidden=at::embedding(weights.at("fast_embeddings.weight"),current).unsqueeze(1);auto scores=step(hidden,position);if(!diagnostics.empty())write_float_raw(diagnostics/("fast-logits-"+std::to_string(position)+".raw"),scores);current=sample(filtered(scores,options.top_k,options.top_p,options.temperature));codes.push_back(current);}
  diagnostics.clear();
  return at::stack(codes,1);
}
Tensor Model::prompt(const std::vector<int64_t>& prefix,const std::vector<int64_t>& suffix,const Tensor& reference){
  int64_t total=prefix.size()+suffix.size()+reference.size(1);if(total>=2048)throw std::runtime_error("Reference and text exceed Audio8 context");
  auto out=at::zeros({1,11,total},at::TensorOptions().device(device).dtype(at::kLong));
  auto tokens=out.select(0,0).select(0,0);tokens.slice(0,0,prefix.size()).copy_(at::tensor(prefix).to(device));
  tokens.slice(0,prefix.size(),prefix.size()+reference.size(1)).copy_(reference.select(0,0)+config["semantic_begin_id"].get<int>());
  out.index({0,Slice(1,11),Slice(prefix.size(),prefix.size()+reference.size(1))}).copy_(reference);
  tokens.slice(0,prefix.size()+reference.size(1),total).copy_(at::tensor(suffix).to(device));return out;
}
} // namespace audio8
