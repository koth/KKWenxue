"""Measure an equivalent FP32, expanded KV cache against current math SDPA."""
import json
from pathlib import Path
import torch
import torch.nn.functional as F

torch.set_num_threads(2)
torch.backends.cuda.enable_flash_sdp(False)
torch.backends.cuda.enable_mem_efficient_sdp(False)
torch.backends.cuda.enable_cudnn_sdp(False)
torch.manual_seed(42)
rows=[]
for valid in [368,513,1025,1800]:
    q=torch.randn(1,14,1,64,device='cuda',dtype=torch.bfloat16)
    k=torch.zeros(1,2,2048,64,device='cuda',dtype=torch.bfloat16)
    v=torch.zeros_like(k)
    k[:,:,:valid]=torch.randn(1,2,valid,64,device='cuda',dtype=k.dtype)
    v[:,:,:valid]=torch.randn(1,2,valid,64,device='cuda',dtype=v.dtype)
    mask=(torch.arange(2048,device='cuda')<valid).reshape(1,1,1,-1)
    kf=k.repeat_interleave(7,1).float();vf=v.repeat_interleave(7,1).float()
    def baseline():
        return F.scaled_dot_product_attention(q,k.repeat_interleave(7,1),v.repeat_interleave(7,1),mask)
    def cached():
        return F.scaled_dot_product_attention(q.float(),kf,vf,mask).to(q.dtype)
    a=baseline();b=cached()
    def measure(fn):
        for _ in range(4):fn()
        torch.cuda.synchronize()
        graph=torch.cuda.CUDAGraph()
        with torch.cuda.graph(graph):out=fn()
        start=torch.cuda.Event(enable_timing=True);end=torch.cuda.Event(enable_timing=True)
        start.record()
        for _ in range(50):graph.replay()
        end.record();end.synchronize()
        return start.elapsed_time(end)/50
    rows.append(dict(valid=valid,identical=torch.equal(a,b),max_error=(a-b).abs().max().item(),
                     baseline_ms=measure(baseline),expanded_float_ms=measure(cached)))
result=dict(torch=torch.__version__,allow_tf32=torch.backends.cuda.matmul.allow_tf32,
            allow_reduced_math=torch.backends.cuda.fp16_bf16_reduction_math_sdp_allowed(),rows=rows)
Path('/home/jetson/workspace/audio8-kv-speed-20261116/attention-probe.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2),flush=True)
