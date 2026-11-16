"""Compare the first native decoding frame with the original Python runtime."""
from pathlib import Path
import json,os,subprocess
ROOT=Path(__file__).resolve().parents[2]
os.environ.setdefault('HF_HUB_OFFLINE','1')

def main():
    import numpy as np
    import torch
    from transformers import AutoModel,AutoProcessor,StoppingCriteria,StoppingCriteriaList
    torch.set_num_threads(4)
    package=ROOT/'models/Audio8-TTS-Realistic-Air-cpp';original=ROOT/'models/Audio8-TTS-Realistic-Air';out=ROOT/'tts_output/audio8_cpp_diagnostic_v2';out.mkdir(parents=True,exist_ok=True)
    req=json.loads((package/'validation/sentence-request.json').read_text(encoding='utf-8'));req.update(output_dir=str(out/'native'),diagnostics=True)
    request=out/'request.json';request.write_text(json.dumps(req,ensure_ascii=False),encoding='utf-8')
    env=dict(os.environ);env['PATH']=str(ROOT/'.qwen3-tts-venv/Lib/site-packages/torch/lib')+os.pathsep+env['PATH']
    binary=ROOT/'tools/audio8-cpp/build/Release/audio8-tts.exe'
    p=subprocess.run([str(binary),'--model',str(package),'--codec',str(package/'codec-decoder-bf16.safetensors'),'--no-graphs','--request',str(request)],capture_output=True,encoding='utf-8',env=env,timeout=120)
    print('Native diagnostic exit',p.returncode,p.stderr[-500:],flush=True)
    model=AutoModel.from_pretrained(str(original),trust_remote_code=True,local_files_only=True,dtype=torch.bfloat16).eval().cuda()
    processor=AutoProcessor.from_pretrained(str(original),trust_remote_code=True,local_files_only=True)
    voice=json.loads((package/'voices/approved/voice.json').read_text(encoding='utf-8'));ref=torch.from_numpy(np.load(package/'voices/approved/reference_codes.npy'))
    inputs=processor(text=[req['text']],reference_text=[voice['text']],reference_codes=[ref],return_tensors='pt');inputs={k:v.cuda() for k,v in inputs.items()}
    expected=out/'python';expected.mkdir(exist_ok=True)
    def raw(name,t):t.float().cpu().numpy().tofile(expected/(name+'.raw'))
    original_embed=model._embed
    def embed(x):
        result=original_embed(x)
        if x.shape[-1]>1:raw('embedding',result)
        return result
    model._embed=embed
    for index,layer in enumerate(model.layers):
        layer.register_forward_hook(lambda m,a,o,index=index:raw(f'layer-{index}',o[:,-1]))
    model.layers[0].attention_norm.register_forward_hook(lambda m,a,o:raw('attention-normalized',o))
    model.layers[0].attention.register_forward_hook(lambda m,a,o:raw('attention-output',o))
    orig_slow=model._slow_step;orig_fast=model._fast_step;count=0
    def slow(*a,**kw):
        result=orig_slow(*a,**kw)
        if a[0].shape[-1]>1:raw('prefill-logits',result[0]);raw('prefill-hidden',result[1]);raw('rope',model.freqs_cis)
        return result
    def fast(hidden,position):
        result=orig_fast(hidden,position);raw(f'fast-logits-{position}',result);return result
    def sample(scores,generator=None):
        nonlocal count
        probabilities=torch.softmax(scores,dim=-1);random=torch.rand(probabilities.shape,dtype=probabilities.dtype,device=probabilities.device,generator=generator)
        raw(f'probabilities-{count}',probabilities);raw(f'random-{count}',random);count+=1
        return torch.argmax(probabilities/(-torch.log(random)),dim=-1)
    model._slow_step=slow;model._fast_step=fast;model._sample=sample
    class Stop(StoppingCriteria):
        def __call__(self,*a,**kw):return True
    torch.manual_seed(req['seed'])
    with torch.inference_mode():output=model.generate(**inputs,max_new_tokens=1,temperature=req['temperature'],top_k=req['top_k'],top_p=req['top_p'],do_sample=True,stopping_criteria=StoppingCriteriaList([Stop()]),return_dict_in_generate=True)
    np.save(expected/'first-frame.npy',output.codes.cpu().numpy());print('Python frame',output.codes[0,:,0].tolist(),flush=True)
    rows=[]
    for native in sorted((out/'native').glob('*.raw')):
        reference=expected/native.name
        if not reference.exists():continue
        a=np.fromfile(native,np.float32);b=np.fromfile(reference,np.float32)
        rows.append(dict(name=native.name,shape_equal=a.shape==b.shape,exact=bool(np.array_equal(a,b)),maximum=float(np.max(abs(a-b))) if a.shape==b.shape else None,differences=int(np.sum(a!=b)) if a.shape==b.shape else None))
    (out/'comparison.json').write_text(json.dumps(rows,indent=2),encoding='utf-8');print(json.dumps(rows,indent=2),flush=True)

if __name__=='__main__':main()
