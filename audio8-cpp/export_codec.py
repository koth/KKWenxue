"""One-time conversion only: materialize Audio8 codec weight norm for C++.

The deployed C++ program does not import or invoke this script or Python.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import time
import os

os.environ.setdefault('HF_HUB_OFFLINE','1')
ROOT=Path(__file__).resolve().parents[2]


def digest(p):
    h=hashlib.sha256()
    with Path(p).open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='models/Audio8-TTS-Realistic-Air-cpp');args=parser.parse_args()
    out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
    if (out/'manifest.json').exists():raise FileExistsError('Choose a new package directory')
    print('Importing conversion runtime',flush=True)
    import torch
    import numpy as np
    import soundfile as sf
    from transformers import AutoModel
    from safetensors.torch import save_file
    torch.set_num_threads(4)
    source=ROOT/'models/Audio8-TTS-Realistic-Air'
    model=AutoModel.from_pretrained(str(source),trust_remote_code=True,local_files_only=True,dtype=torch.bfloat16).eval().cuda()
    codec=model.load_codec(device='cuda',dtype=torch.bfloat16)
    golden=ROOT/'tts_output/audio8_speed_benchmark_v2/eager-1-codes.npy'
    codes=torch.from_numpy(np.load(golden)).cuda()
    with torch.inference_mode():baseline=model.decode_audio(codes)[0][0].float().cpu()
    print('Materializing weight normalization in BF16 on CUDA',flush=True)
    def selected(name):
        return name.startswith(('decoder.','quantizer.upsample.','quantizer.post_module.')) or (name.startswith(('quantizer.semantic_quantizer.quantizers.','quantizer.quantizer.quantizers.')) and ('.out_proj.' in name or name.endswith('.codebook.weight')))
    weights_error=0.0
    for name,module in list(codec.named_modules()):
        if not selected(name+'.weight'):continue
        if hasattr(module,'parametrizations') and 'weight' in module.parametrizations:
            before=module.weight.detach().clone()
            torch.nn.utils.parametrize.remove_parametrizations(module,'weight',leave_parametrized=True)
            weights_error=max(weights_error,float((before-module.weight).abs().max()))
        if hasattr(module,'weight_g') and hasattr(module,'weight_v'):
            before=module.weight.detach().clone()
            torch.nn.utils.remove_weight_norm(module)
            weights_error=max(weights_error,float((before-module.weight).abs().max()))
    with torch.inference_mode():converted=model.decode_audio(codes)[0][0].float().cpu()
    error=float((baseline-converted).abs().max())
    rmse=float((baseline-converted).square().mean().sqrt())
    snr=float(10*torch.log10(baseline.square().mean()/(baseline-converted).square().mean().clamp_min(1e-30)))
    print(json.dumps(dict(weight_error=weights_error,wave_max_error=error,wave_rmse=rmse,wave_snr_db=snr)),flush=True)
    if weights_error!=0:raise ValueError('Materialized normalized weights differ from original decoder weights')
    state={k:v.detach().cpu().contiguous() for k,v in codec.state_dict().items() if selected(k)}
    target=out/'codec-decoder-bf16.safetensors';save_file(state,str(target),metadata={'source':'Edge0/Audio8-TTS-Realistic-Air','conversion':'BF16 CUDA weight-norm materialization; decoder only','format_version':'1'})
    for name in ['model.safetensors','config.json','tokenizer.json','LICENSE','NOTICE']:
        print('Packaging',name,flush=True);shutil.copy2(source/name,out/name)
    voice_plan=json.loads((ROOT/'tts_output/audio8_realistic_air_911_trial_v1/plan.json').read_text(encoding='utf-8'))
    voice_dir=out/'voices/approved';voice_dir.mkdir(parents=True,exist_ok=True)
    shutil.copy2(ROOT/'tts_output/audio8_realistic_air_911_trial_v1/reference_codes.npy',voice_dir/'reference_codes.npy')
    voice={'codes':'reference_codes.npy','text':voice_plan['voice']['reference_text'],'reference_sha256':digest(voice_plan['voice']['reference']),'source':'Approved warm full male voice; same reference as Python trial','model_revision':'4d02073010389bdcc86e2f0bc52137b3e2ab7580'}
    (voice_dir/'voice.json').write_text(json.dumps(voice,ensure_ascii=False,indent=2),encoding='utf-8')
    fixtures=out/'validation';fixtures.mkdir(exist_ok=True)
    shutil.copy2(golden,fixtures/'golden-codes.npy');np.save(fixtures/'golden-waveform.npy',baseline.numpy());np.save(fixtures/'converted-waveform.npy',converted.numpy());sf.write(fixtures/'golden.wav',baseline.numpy(),44100,subtype='PCM_16')
    single={'text':'虽然没看到杀掉舒霜的那只鬼，但从舒霜惨死的位置以及死亡方式判断，就是杀死吴卓的那家伙。','voice':str(voice_dir/'voice.json'),'output_dir':str(ROOT/'tts_output/audio8_cpp_sentence_v1'),'seed':111138,'temperature':0.7,'top_p':0.9,'top_k':50}
    (fixtures/'sentence-request.json').write_text(json.dumps(single,ensure_ascii=False,indent=2),encoding='utf-8')
    full=dict(single,text=voice_plan['text'],seed=111135,minimum_seconds=60,output_dir=str(ROOT/'tts_output/audio8_cpp_911_v1'))
    (fixtures/'full-request.json').write_text(json.dumps(full,ensure_ascii=False,indent=2),encoding='utf-8')
    manifest={'format_version':1,'source_revision':voice['model_revision'],'dtype':'BF16','sample_rate':44100,'normalized_weights_max_error':weights_error,'codec_waveform_max_error':error,'codec_waveform_rmse':rmse,'codec_waveform_snr_db':snr,'decoder_tensors':len(state),'decoder_bytes':target.stat().st_size,'runtime_python':False,'voice_encoding':'Offline precomputed reference codes; on-device reference encoding is not part of this first runtime','files':{p.relative_to(out).as_posix():{'sha256':digest(p),'bytes':p.stat().st_size} for p in out.rglob('*') if p.is_file()}}
    (out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'package':str(out),'decoder_bytes':manifest['decoder_bytes'],'conversion_error':error},ensure_ascii=False),flush=True)


if __name__=='__main__':main()
