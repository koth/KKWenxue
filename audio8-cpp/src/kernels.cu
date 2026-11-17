#include "kernels.hpp"
#include <ATen/Dispatch.h>
#include <c10/cuda/CUDAException.h>
#include <c10/cuda/CUDAGuard.h>
#include <c10/cuda/CUDAStream.h>

namespace audio8 {
namespace {
template<typename T>
__global__ void norm_kernel(const T* x,const T* weight,T* out,int width,float eps){
  // Match ATen's vectorized 896-element FP32 mean reduction, including
  // accumulator grouping, shuffle order, and reciprocal multiplication.
  float parts[4]={0,0,0,0};
  for(int base=threadIdx.x*4;base<width;base+=blockDim.x*4){
    for(int i=0;i<4;++i){float value=float(x[base+i]);parts[i]+=value*value;}
  }
  float sum=((parts[0]+parts[1])+parts[2])+parts[3];
  __shared__ float partial[128];partial[threadIdx.x]=sum;
  for(int offset=64;offset>=32;offset/=2){
    __syncthreads();
    if(threadIdx.x<offset){sum+=partial[threadIdx.x+offset];partial[threadIdx.x]=sum;}
  }
  __syncthreads();
  for(int offset=1;offset<32;offset*=2)sum+=__shfl_down_sync(0xffffffff,sum,offset);
  if(threadIdx.x==0)partial[0]=rsqrtf(sum*(1.0f/float(width))+eps);
  __syncthreads();
  float scale=partial[0];
  for(int i=threadIdx.x;i<width;i+=blockDim.x){
    // Preserve the release's low-precision rounding BEFORE multiplying weight.
    T normalized=T(float(x[i])*scale);
    out[i]=T(float(normalized)*float(weight[i]));
  }
}

template<typename T,typename C>
__global__ void qkv_cache_kernel(const T* packed,const c10::BFloat16* rope,T* query,
                                C* keys,C* values,const int64_t* position,
                                int heads,int local,int capacity,float key_scale){
  int index=blockIdx.x*blockDim.x+threadIdx.x;
  if(index>=heads*64)return;
  int head=index/64,component=index%64,pair=component/2;
  float cosine=float(rope[pair*2]),sine=float(rope[pair*2+1]);
  int qbase=head*64+pair*2;
  float qr=float(packed[qbase]),qi=float(packed[qbase+1]);
  query[index]=T(component%2?qi*cosine+qr*sine:qr*cosine-qi*sine);
  int local_head=head/(heads/local),kbase=heads*64+local_head*64+pair*2;
  float kr=float(packed[kbase]),ki=float(packed[kbase+1]);
  T rotated=T(component%2?ki*cosine+kr*sine:kr*cosine-ki*sine);
  int64_t target=(int64_t(head)*capacity+position[0])*64+component;
  keys[target]=C(float(rotated)*key_scale);
  values[target]=C(float(packed[(heads+local)*64+local_head*64+component]));
}

template<typename T>
__global__ void rope_kernel(const T* x,const c10::BFloat16* values,T* out,
                           int tokens,int heads,int dim,int64_t token_stride){
  int index=blockIdx.x*blockDim.x+threadIdx.x;
  int pairs=tokens*heads*(dim/2);
  if(index>=pairs)return;
  int pair=index%(dim/2),head=(index/(dim/2))%heads,token=index/(heads*(dim/2));
  int64_t src=token*token_stride+head*dim+pair*2;
  int angle=(token*(dim/2)+pair)*2;
  float real=float(x[src]),imag=float(x[src+1]);
  float cosine=float(values[angle]),sine=float(values[angle+1]);
  out[index*2]=T(real*cosine-imag*sine);
  out[index*2+1]=T(imag*cosine+real*sine);
}

template<typename T>
__global__ void silu_multiply_kernel(const T* gate,const T* up,T* out,int64_t count){
  int64_t index=int64_t(blockIdx.x)*blockDim.x+threadIdx.x;
  if(index>=count)return;
  float value=float(gate[index]);
  T activated=T(value/(1.0f+expf(-value)));
  out[index]=T(float(activated)*float(up[index]));
}
}

at::Tensor fused_norm(const at::Tensor& x,const at::Tensor& weight,double eps){
  TORCH_CHECK(x.is_cuda()&&x.is_contiguous()&&weight.is_contiguous(),"fused norm requires contiguous CUDA tensors");
  TORCH_CHECK(x.numel()==896&&weight.numel()==896&&x.scalar_type()==weight.scalar_type(),"fused norm requires one 896-element row and matching weights");
  const c10::cuda::CUDAGuard guard(x.device());
  auto out=at::empty_like(x);
  AT_DISPATCH_REDUCED_FLOATING_TYPES(x.scalar_type(),"audio8_norm",[&]{
    norm_kernel<<<1,128,0,c10::cuda::getCurrentCUDAStream()>>>(x.const_data_ptr<scalar_t>(),weight.const_data_ptr<scalar_t>(),out.mutable_data_ptr<scalar_t>(),int(weight.numel()),float(eps));
  });
  C10_CUDA_KERNEL_LAUNCH_CHECK();return out;
}

at::Tensor fused_rope(const at::Tensor& x,const at::Tensor& values){
  TORCH_CHECK(x.is_cuda()&&x.dim()==4&&x.size(0)==1&&x.size(-1)==64&&x.stride(-1)==1&&x.stride(-2)==64,"unsupported fused RoPE shape");
  TORCH_CHECK(values.is_contiguous()&&values.scalar_type()==at::kBFloat16&&values.numel()==x.size(1)*64,"unsupported fused RoPE values");
  const c10::cuda::CUDAGuard guard(x.device());
  auto out=at::empty(x.sizes(),x.options());
  int pairs=int(x.numel()/2);
  AT_DISPATCH_REDUCED_FLOATING_TYPES(x.scalar_type(),"audio8_rope",[&]{
    rope_kernel<<<(pairs+255)/256,256,0,c10::cuda::getCurrentCUDAStream()>>>(x.const_data_ptr<scalar_t>(),values.const_data_ptr<c10::BFloat16>(),out.mutable_data_ptr<scalar_t>(),int(x.size(1)),int(x.size(2)),64,x.stride(1));
  });
  C10_CUDA_KERNEL_LAUNCH_CHECK();return out;
}

at::Tensor fused_silu_multiply(const at::Tensor& gate,const at::Tensor& up){
  TORCH_CHECK(gate.is_cuda()&&gate.is_contiguous()&&up.is_contiguous()&&gate.sizes()==up.sizes()&&gate.scalar_type()==up.scalar_type(),"unsupported fused SwiGLU inputs");
  const c10::cuda::CUDAGuard guard(gate.device());
  auto out=at::empty_like(gate);
  AT_DISPATCH_REDUCED_FLOATING_TYPES(gate.scalar_type(),"audio8_silu_multiply",[&]{
    silu_multiply_kernel<<<(gate.numel()+255)/256,256,0,c10::cuda::getCurrentCUDAStream()>>>(gate.const_data_ptr<scalar_t>(),up.const_data_ptr<scalar_t>(),out.mutable_data_ptr<scalar_t>(),gate.numel());
  });
  C10_CUDA_KERNEL_LAUNCH_CHECK();return out;
}

at::Tensor fused_qkv_cache(const at::Tensor& packed,const at::Tensor& rope,
                          at::Tensor& keys,at::Tensor& values,const at::Tensor& position,
                          int heads,int local,float key_scale){
  TORCH_CHECK(packed.is_cuda()&&packed.is_contiguous()&&packed.numel()==(heads+2*local)*64,"unsupported packed QKV");
  TORCH_CHECK(rope.is_contiguous()&&rope.numel()==64&&rope.scalar_type()==at::kBFloat16,"unsupported cache RoPE");
  TORCH_CHECK(keys.is_contiguous()&&values.is_contiguous()&&keys.size(1)==heads&&keys.sizes()==values.sizes()&&keys.scalar_type()==values.scalar_type(),"unsupported expanded KV cache");
  TORCH_CHECK(position.scalar_type()==at::kLong&&position.numel()==1,"unsupported cache position");
  const c10::cuda::CUDAGuard guard(packed.device());
  auto q=at::empty({1,heads,1,64},packed.options());
  AT_DISPATCH_REDUCED_FLOATING_TYPES(packed.scalar_type(),"audio8_qkv_cache",[&]{
    if(keys.scalar_type()==at::kFloat){
      qkv_cache_kernel<scalar_t,float><<<(heads*64+255)/256,256,0,c10::cuda::getCurrentCUDAStream()>>>(packed.const_data_ptr<scalar_t>(),rope.const_data_ptr<c10::BFloat16>(),q.mutable_data_ptr<scalar_t>(),keys.mutable_data_ptr<float>(),values.mutable_data_ptr<float>(),position.const_data_ptr<int64_t>(),heads,local,int(keys.size(2)),key_scale);
    }else{
      TORCH_CHECK(keys.scalar_type()==packed.scalar_type(),"KV dtype must match QKV or be FP32");
      qkv_cache_kernel<scalar_t,scalar_t><<<(heads*64+255)/256,256,0,c10::cuda::getCurrentCUDAStream()>>>(packed.const_data_ptr<scalar_t>(),rope.const_data_ptr<c10::BFloat16>(),q.mutable_data_ptr<scalar_t>(),keys.mutable_data_ptr<scalar_t>(),values.mutable_data_ptr<scalar_t>(),position.const_data_ptr<int64_t>(),heads,local,int(keys.size(2)),key_scale);
    }
  });
  C10_CUDA_KERNEL_LAUNCH_CHECK();return q;
}
} // namespace audio8
