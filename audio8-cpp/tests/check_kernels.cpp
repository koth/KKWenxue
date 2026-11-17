#include "audio8.hpp"
#include "kernels.hpp"
#include <c10/core/InferenceMode.h>
#include <ATen/cuda/CUDAGeneratorImpl.h>
#include <iostream>

int main(){
  using namespace audio8;
  try{
    c10::InferenceMode mode;at::cuda::detail::getDefaultCUDAGenerator();at::manual_seed(42);Json checks=Json::array();
    auto compare=[&](const char* name,const Tensor& actual,const Tensor& expected,double tolerance){
      auto error=(actual.to(at::kFloat)-expected.to(at::kFloat)).abs();
      auto maximum=error.max().item<float>();
      TORCH_CHECK(at::isfinite(actual).all().item<bool>()&&at::allclose(actual,expected,tolerance,tolerance),name," error ",maximum);
      checks.push_back({{"operation",name},{"dtype",c10::toString(actual.scalar_type())},{"values",actual.numel()},{"different_values",actual.ne(expected).sum().item<int64_t>()},{"max_absolute_error",maximum}});
    };
    for(auto dtype:{at::kBFloat16,at::kHalf}){
      auto options=at::TensorOptions().device(at::kCUDA).dtype(dtype);
      for(double scale:{0.001,1.0,100.0}){
        auto x=(at::randn({1,1,896},options)*scale).contiguous();auto w=at::randn({896},options);
        compare("norm",fused_norm(x,w,1e-6),norm(x,w,1e-6),.001);
      }
      int64_t norm_differences=0;
      for(int trial=0;trial<200;++trial){
        auto x=at::randn({1,1,896},options);auto w=at::randn({896},options);
        norm_differences+=fused_norm(x,w,1e-6).ne(norm(x,w,1e-6)).sum().item<int64_t>();
      }
      TORCH_CHECK(norm_differences==0,"RMSNorm reduction differs from ATen: ",norm_differences);
      checks.push_back({{"operation","norm_200_rows"},{"different_values",norm_differences},{"dtype",c10::toString(dtype)}});
      for(int tokens:{1,7}){
        // A view into packed QKV, including a nonzero storage offset.
        auto packed=at::randn({1,tokens,1152},options);
        auto values=rope(tokens,64,1000000,at::kCPU).to(at::kCUDA);
        for(int heads:{14,2}){
          auto x=packed.slice(-1,heads==14?0:896,heads==14?896:1024).reshape({1,tokens,heads,64});
          compare("rope",fused_rope(x,values),apply_rope(x,values),0);
        }
      }
      auto gate=at::randn({1,1,4864},options)*5;auto up=at::randn_like(gate);
      compare("silu_multiply",fused_silu_multiply(gate,up),at::silu(gate)*up,0);
      auto zero=at::zeros({1,1,896},options);auto weight=at::ones({896},options);
      compare("zero_norm",fused_norm(zero,weight,1e-6),norm(zero,weight,1e-6),0);
      for(auto cache_dtype:{dtype,at::kFloat}){
        auto packed=at::randn({1,1,1152},options);
        auto angle=rope(10,64,1000000,at::kCPU).slice(0,6,7).to(at::kCUDA);
        auto position=at::full({1},6,options.dtype(at::kLong));
        auto keys=at::zeros({1,14,10,64},options.dtype(cache_dtype)),values=at::zeros_like(keys);
        auto query=fused_qkv_cache(packed,angle,keys,values,position,14,2);
        auto parts=packed.split_with_sizes({896,128,128},-1);
        auto q=apply_rope(parts[0].reshape({1,1,14,64}),angle).transpose(1,2);
        auto k=apply_rope(parts[1].reshape({1,1,2,64}),angle).transpose(1,2).repeat_interleave(7,1).to(cache_dtype);
        auto v=parts[2].reshape({1,1,2,64}).transpose(1,2).repeat_interleave(7,1).to(cache_dtype);
        auto expected_keys=at::zeros_like(keys),expected_values=at::zeros_like(values);
        expected_keys.slice(2,6,7).copy_(k);expected_values.slice(2,6,7).copy_(v);
        compare("cached_query",query,q,0);compare("cached_keys",keys,expected_keys,0);compare("cached_values",values,expected_values,0);
      }
      for(int position:{0,2047}){
        auto packed=at::randn({1,1,1152},options);
        auto angle=rope(2048,64,1000000,at::kCPU).slice(0,position,position+1).to(at::kCUDA);
        auto pos=at::full({1},position,options.dtype(at::kLong));
        auto keys=at::zeros({1,14,2048,64},options.dtype(at::kFloat)),values=at::zeros_like(keys);
        float scale=std::sqrt(1.0/std::sqrt(64.0));
        auto query=fused_qkv_cache(packed,angle,keys,values,pos,14,2,scale);
        auto parts=packed.split_with_sizes({896,128,128},-1);
        auto k=apply_rope(parts[1].reshape({1,1,2,64}),angle).transpose(1,2).repeat_interleave(7,1).to(at::kFloat)*scale;
        auto v=parts[2].reshape({1,1,2,64}).transpose(1,2).repeat_interleave(7,1).to(at::kFloat);
        auto expected_keys=at::zeros_like(keys),expected_values=at::zeros_like(values);
        expected_keys.slice(2,position,position+1).copy_(k);expected_values.slice(2,position,position+1).copy_(v);
        compare("scaled_cache_keys_boundary",keys,expected_keys,0);
        compare("scaled_cache_values_boundary",values,expected_values,0);
      }
    }
    std::cout<<Json{{"checks",checks},{"passed",true}}.dump(2)<<std::endl;return 0;
  }catch(const std::exception& e){std::cerr<<e.what()<<std::endl;return 1;}
}
