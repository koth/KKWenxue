"""Small reference/model markers for queue tests using a fake audio engine."""
from contextlib import closing
import time
import wave

from server.db import connect, initialize
from server.voices import DEFAULT_CLONE_ID
from tts.common import file_digest


def install_test_voice(settings):
    settings.cosyvoice_repo = settings.state.parent / "cosy-repo"
    settings.cosyvoice_model_path = settings.state.parent / "cosy-model"
    files = [settings.cosyvoice_repo / "cosyvoice/cli/cosyvoice.py"]
    files += [settings.cosyvoice_model_path / name for name in (
        "cosyvoice3.yaml", "llm.pt", "flow.pt", "hift.pt", "campplus.onnx",
        "speech_tokenizer_v3.onnx", "CosyVoice-BlankEN/config.json",
        "CosyVoice-BlankEN/model.safetensors",
    )]
    for path in files:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
    settings.audio8_binary = settings.state.parent / "audio8-tts"
    settings.audio8_binary.touch()
    settings.audio8_binary.chmod(0o700)
    settings.audio8_model_path = settings.state.parent / "audio8-model"
    settings.audio8_codec_path = settings.audio8_model_path / "codec-decoder-bf16.safetensors"
    for name in ("config.json", "tokenizer.json", "model.safetensors", "codec.pth", "modeling_arktts_codec.py", "codec-decoder-bf16.safetensors"):
        path = settings.audio8_model_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
    initialize(settings)
    relative = "reference-voices/test.wav"
    reference = settings.audio / relative
    reference.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(reference), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(24000)
        wav.writeframes(b"\x00\x10" * 48000)
    with closing(connect(settings)) as db:
        db.execute("INSERT OR IGNORE INTO voice_clone_refs VALUES (?,?,?,?,?,?,?,?)",
                   (DEFAULT_CLONE_ID, relative, "测试参考。", 0, file_digest(reference), 2000, 1, time.time()))
    initialize(settings)
