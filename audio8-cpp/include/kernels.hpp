#pragma once
#include <ATen/ATen.h>

namespace audio8 {
at::Tensor fused_norm(const at::Tensor& x, const at::Tensor& weight, double eps);
at::Tensor fused_rope(const at::Tensor& x, const at::Tensor& values);
at::Tensor fused_silu_multiply(const at::Tensor& gate, const at::Tensor& up);
at::Tensor fused_qkv_cache(const at::Tensor& packed, const at::Tensor& rope,
                          at::Tensor& keys, at::Tensor& values, const at::Tensor& position,
                          int heads, int local, float key_scale=1.0f);
}
