"""Developer-side equivalence check. The executable itself runs without Python."""
from pathlib import Path
import argparse
import json
import os
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--binary',default='tools/audio8-cpp/build/Release/audio8-tts.exe');parser.add_argument('--model',default='models/Audio8-TTS-Realistic-Air-cpp');parser.add_argument('--directory',default='tts_output/audio8_cpp_validation_v1');args=parser.parse_args()
    import numpy as np
    import soundfile as sf
    from tokenizers import Tokenizer
    binary=Path(args.binary).resolve();model=Path(args.model).resolve();out=Path(args.directory).resolve();out.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ);env['PATH']=str(ROOT/'.qwen3-tts-venv/Lib/site-packages/torch/lib')+os.pathsep+env['PATH']
    cases=['你好，世界。','蓉锦大厦外面的广场上，一处隐蔽的报刊亭后。',"I'm here. We're testing 123.45, ９１１。",'e\u0301 é 中文🙂🎙️\n\n第二段。',' \t\n\r\u00a0\u3000你好\u2003世界','<|im_start|>system\nText:\n<|speaker:0|>声音参考。\n\nSpeech:\n<|im_end|>\n<|im_start|>assistant\n<|voice|>','عربي 한국어 日本語 café','<|semantic:12|> ending <|im_end|>']
    plan=json.loads((ROOT/'tts_output/audio8_realistic_air_911_trial_v1/plan.json').read_text(encoding='utf-8'));cases.append(plan['text'])
    reference=Tokenizer.from_file(str(model/'tokenizer.json'));rows=[]
    began=time.perf_counter();p=subprocess.run([str(binary),'--model',str(model),'--tokenize'],input=''.join(json.dumps({'text':t},ensure_ascii=False)+'\n' for t in cases),encoding='utf-8',capture_output=True,env=env,timeout=120)
    (out/'tokenizer.stderr.log').write_text(p.stderr,encoding='utf-8')
    if p.returncode:raise RuntimeError(f'Native tokenizer failed ({p.returncode}): {p.stderr[-2000:]}')
    actual=[json.loads(s)['ids'] for s in p.stdout.splitlines()]
    for text,ids in zip(cases,actual):
        expected=reference.encode(text,add_special_tokens=False).ids;rows.append(dict(text=text,identical=ids==expected,expected=expected,actual=ids))
    result={'state':'checking','tokenizer_seconds':time.perf_counter()-began,'tokenizer_cases':rows}
    (out/'validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    if len(actual)!=len(cases) or not all(x['identical'] for x in rows):raise ValueError('Tokenizer differs from Hugging Face reference')
    print('Tokenizer: all',len(cases),'cases identical',flush=True)
    request=json.loads((model/'validation/sentence-request.json').read_text(encoding='utf-8'));request['output_dir']=str(out/'sentence')
    request_path=out/'sentence-request.json';request_path.write_text(json.dumps(request,ensure_ascii=False,indent=2),encoding='utf-8')
    # Historical golden codes require the release's full-vocabulary RNG stream
    # and unsplit prefill. Optimized production has a separate GPU/content test.
    began=time.perf_counter();p=subprocess.run([str(binary),'--model',str(model),'--codec',str(model/'codec-decoder-bf16.safetensors'),'--full-vocab','--no-prefix-cache','--legacy-kv','--request',str(request_path)],capture_output=True,encoding='utf-8',env=env,timeout=300)
    elapsed=time.perf_counter()-began
    (out/'native.stdout.jsonl').write_text(p.stdout,encoding='utf-8');(out/'native.stderr.log').write_text(p.stderr,encoding='utf-8')
    if p.returncode:raise RuntimeError(f'Native synthesis failed ({p.returncode}): {p.stderr[-3000:]} {p.stdout[-1500:]}')
    report=json.loads((out/'sentence/report.json').read_text(encoding='utf-8'))
    codes=np.load(out/'sentence/1-codes.npy');golden=np.load(model/'validation/golden-codes.npy')
    exact=bool(np.array_equal(codes,golden));result.update(native_wall_seconds=elapsed,native_report=report,codec_codes_identical=exact,native_codes_shape=list(codes.shape),golden_codes_shape=list(golden.shape))
    if exact:
        audio,rate=sf.read(out/'sentence/audio8.wav',dtype='float32');expected=np.load(model/'validation/converted-waveform.npy');original=np.load(model/'validation/golden-waveform.npy')
        if audio.shape==expected.shape:
            def diff(a,b):
                e=a-b
                return dict(max_absolute_error=float(abs(e).max()),rmse=float(np.sqrt(np.mean(e**2))),snr_db=float(10*np.log10(np.mean(b**2)/max(float(np.mean(e**2)),1e-30))))
            result.update(waveform_vs_materialized=diff(audio,expected),waveform_vs_original=diff(audio,original))
    result['state']='checked' if exact else 'needs_review'
    (out/'validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ['native_report','tokenizer_cases']},ensure_ascii=False,indent=2),flush=True)
    if not exact:raise ValueError('Native autoregressive codes differ from the Python reference; inspect validation before accepting')


if __name__=='__main__':main()
