from pathlib import Path
exec(Path(__file__).with_name("benchmark.py").read_text().split("with GPULock():")[0])
from server.cosy_graph import GraphDecoder

report_path = root / "graph-benchmark.json"
outputs.update({i: sf.read(root / f"baseline-{i}.wav", dtype="float32")[0]
                for i in range(len(texts))})
with GPULock():
    generate("graph-model-warmup", 99, "夜色渐深，街道安静下来。")
    internal = engine.model.model
    profile(internal, "llm_job", "llm")
    profile(internal.flow, "inference", "flow")
    profile(internal.hift, "inference", "vocoder")
    internal.llm.llm.forward_one_step = GraphDecoder(internal.llm.llm)
    generate("graph-capture", 0, texts[0])
    for i, text in enumerate(texts):
        generate("cuda-graph", i, text)
    generate("cuda-graph-long", 2,
             "那天傍晚，天空突然下起了雨。他站在窗前，望着对面那座早已废弃的旧楼。"
             "楼里没有灯光，却传来了清晰的脚步声，每一步都像是踩在他的心口上。"
             "他想起了昨夜收到的那封信，上面只有一句话：如果你听见了，千万不要回头。")
