#include "audio8.hpp"

namespace audio8 {
Codec::Codec(const Path& path,at::Device device):weights(load_safetensors(path,device)){}
Tensor Codec::conv(const Tensor& x,const std::string& name,int dilation,int stride,int groups){
  auto& w=weights.at(name+".weight");int kernel=(w.size(-1)-1)*dilation+1;int left=kernel-stride;
  int frames=(x.size(-1)-kernel+left+stride-1)/stride+1;int right=(frames-1)*stride+kernel-left-x.size(-1);
  auto padded=at::constant_pad_nd(x,{left,right},0);
  return at::conv1d(padded,w,weights.at(name+".bias"),at::IntArrayRef{stride},at::IntArrayRef{0},at::IntArrayRef{dilation},groups).contiguous();
}
Tensor Codec::transpose(const Tensor& x,const std::string& name,int stride){
  auto& w=weights.at(name+".weight");auto y=at::conv_transpose1d(x,w,weights.at(name+".bias"),{stride},{0},{0},1,{1});int crop=w.size(-1)-stride;
  return crop?y.slice(-1,0,y.size(-1)-crop).contiguous():y.contiguous();
}
Tensor Codec::snake(const Tensor& x,const std::string& name){auto alpha=weights.at(name+".alpha");return x+(alpha+1e-9).reciprocal()*at::sin(alpha*x).pow(2);}
Tensor Codec::residual(const Tensor& x,const std::string& name,int dilation){
  auto y=conv(snake(x,name+".block.0"),name+".block.1.conv",dilation);y=conv(snake(y,name+".block.2"),name+".block.3.conv");return x+y;
}
Tensor Codec::convnext(const Tensor& x,const std::string& name){
  auto y=conv(x,name+".dwconv.conv",1,1,x.size(1)).permute({0,2,1});
  y=at::layer_norm(y,{y.size(-1)},weights.at(name+".norm.weight"),weights.at(name+".norm.bias"),1e-6,true);
  y=linear(at::gelu(linear(y,weights,name+".pwconv1")),weights,name+".pwconv2");return x+(weights.at(name+".gamma")*y).permute({0,2,1});
}
Tensor Codec::transformer(const Tensor& input){
  std::string root="quantizer.post_module";auto x=input.transpose(1,2);int64_t length=x.size(1);
  auto positions=at::arange(length,at::TensorOptions().device(x.device()).dtype(at::kLong));
  auto row=positions.unsqueeze(1),column=positions.unsqueeze(0);auto mask=((column<=row)&(column>=(row-127).clamp_min(0))).unsqueeze(0).unsqueeze(0);
  auto values=rope(length,64,10000,x.device());
  for(int i=0;i<8;++i){
    auto p=root+".layers."+std::to_string(i);auto normalized=norm(x,weights.at(p+".attention_norm.weight"),1e-5);
    auto qkv=linear(normalized,weights,p+".attention.wqkv").split_with_sizes({1024,512,512},-1);
    auto q=apply_rope(qkv[0].reshape({1,length,16,64}),values).transpose(1,2);
    auto k=apply_rope(qkv[1].reshape({1,length,8,64}),values).transpose(1,2).repeat_interleave(2,1);
    auto v=qkv[2].reshape({1,length,8,64}).transpose(1,2).repeat_interleave(2,1);
    auto out=at::scaled_dot_product_attention(q,k,v,mask,0.0,false).transpose(1,2).contiguous().reshape({1,length,1024});
    x=x+linear(out,weights,p+".attention.wo")*weights.at(p+".attention_layer_scale.gamma");
    x=x+feed_forward(norm(x,weights.at(p+".ffn_norm.weight"),1e-5),weights,p+".feed_forward")*weights.at(p+".ffn_layer_scale.gamma");
  }return norm(x,weights.at(root+".norm.weight"),1e-5).transpose(1,2);
}
Tensor Codec::decode(const Tensor& codes){
  auto decode_code=[&](const std::string& p,const Tensor& indices){return conv(at::embedding(weights.at(p+".codebook.weight"),indices).transpose(1,2),p+".out_proj");};
  auto semantic=decode_code("quantizer.semantic_quantizer.quantizers.0",codes.select(1,0).clamp(0,4095));Tensor residual_sum;
  for(int i=0;i<9;++i){auto decoded=decode_code("quantizer.quantizer.quantizers."+std::to_string(i),codes.select(1,i+1).clamp(0,1023));residual_sum=i==0?decoded:residual_sum+decoded;}
  auto x=transformer(semantic+residual_sum);
  for(int i=0;i<2;++i){auto p="quantizer.upsample."+std::to_string(i);x=convnext(transpose(x,p+".0.conv",2),p+".1");}
  x=conv(x,"decoder.model.0.conv");int strides[]={8,8,4,2};
  for(int i=0;i<4;++i){auto p="decoder.model."+std::to_string(i+1)+".block";x=transpose(snake(x,p+".0"),p+".1.conv",strides[i]);x=residual(x,p+".2",1);x=residual(x,p+".3",3);x=residual(x,p+".4",9);}
  return at::tanh(conv(snake(x,"decoder.model.5"),"decoder.model.6.conv")).flatten().to(at::kFloat);
}
} // namespace audio8
