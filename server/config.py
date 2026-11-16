from __future__ import annotations

from dataclasses import dataclass, field
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL = "Edge0/Audio8-TTS-Realistic-Air"
REVISION = "6bf7a980759d8022a66922ee975dab99229a2dd9"
ENGINE = "audio8-realistic-air-cpp"
NORMALIZATION = "reader-canonical+OpenCC-t2s-v1"
SEGMENTATION = "reader-sentence-bounded160-v1"


@dataclass
class Settings:
    state: Path = field(
        default_factory=lambda: Path(
            os.environ.get("SHANYE_STATE", "~/.local/share/shanye-tts")
        ).expanduser()
    )
    audio: Path = field(
        default_factory=lambda: Path(
            os.environ.get("SHANYE_AUDIO", "~/.local/share/shanye-tts/audio")
        ).expanduser()
    )
    cosyvoice_repo: Path = field(
        default_factory=lambda: Path(
            os.environ.get("SHANYE_COSYVOICE_REPO", "~/workspace/CosyVoice")
        ).expanduser()
    )
    cosyvoice_model_path: Path = field(
        default_factory=lambda: Path(
            os.environ.get(
                "SHANYE_COSYVOICE_MODEL_PATH",
                "~/workspace/CosyVoice/pretrained_models/Fun-CosyVoice3-0.5B",
            )
        ).expanduser()
    )
    min_free_bytes: int = 5 * 2**30
    lease_seconds: int = 30
    batch_size: int = 4
    bundle_ttl: int = 86400
    cosyvoice_low_memory: bool = field(
        default_factory=lambda: os.environ.get("SHANYE_COSYVOICE_LOW_MEMORY", "0") == "1"
    )
    cosyvoice_decoder_only: bool = field(
        default_factory=lambda: os.environ.get("SHANYE_COSYVOICE_DECODER_ONLY", "1") == "1"
    )
    cosyvoice_cuda_graph: bool = field(
        default_factory=lambda: os.environ.get("SHANYE_COSYVOICE_CUDA_GRAPH", "0") == "1"
    )
    audio8_binary: Path = field(default_factory=lambda: Path(os.environ.get(
        "SHANYE_AUDIO8_BINARY", "~/workspace/audio8-cpp/build/audio8-tts")).expanduser())
    audio8_model_path: Path = field(default_factory=lambda: Path(os.environ.get(
        "SHANYE_AUDIO8_MODEL_PATH", "~/workspace/Audio8-TTS-Realistic-Air")).expanduser())
    audio8_codec_path: Path = field(default_factory=lambda: Path(os.environ.get(
        "SHANYE_AUDIO8_CODEC_PATH", "~/workspace/Audio8-TTS-Realistic-Air/codec-decoder-bf16.safetensors")).expanduser())
    audio8_python: str = field(default_factory=lambda: os.environ.get(
        "SHANYE_AUDIO8_PYTHON", "python3"))
    audio8_cuda_graph: bool = field(default_factory=lambda: os.environ.get(
        "SHANYE_AUDIO8_CUDA_GRAPH", "1") == "1")
    audio8_timeout: int = field(default_factory=lambda: int(os.environ.get(
        "SHANYE_AUDIO8_TIMEOUT", "600")))

    @property
    def audio8_ready(self):
        return (self.audio8_binary.is_file()
                and os.access(self.audio8_binary, os.X_OK)
                and self.audio8_codec_path.is_file()
                and all((self.audio8_model_path / name).is_file() for name in
                        ("config.json", "tokenizer.json", "model.safetensors", "codec.pth",
                         "modeling_arktts_codec.py")))

    @property
    def db(self):
        return self.state / "service.sqlite"

    @property
    def token_file(self):
        return self.state / "api-token"

    @property
    def cosyvoice_ready(self):
        """Whether the complete CosyVoice3 zero-shot runtime is installed."""
        required = (
            "cosyvoice3.yaml",
            "llm.pt",
            "flow.pt",
            "hift.pt",
            "campplus.onnx",
            "speech_tokenizer_v3.onnx",
            "CosyVoice-BlankEN/config.json",
            "CosyVoice-BlankEN/model.safetensors",
        )
        return (
            self.cosyvoice_repo.is_dir()
            and (self.cosyvoice_repo / "cosyvoice/cli/cosyvoice.py").is_file()
            and all((self.cosyvoice_model_path / name).is_file() for name in required)
        )

    @property
    def clone_model_ready(self):
        """Compatibility name used by the existing health/UI payload."""
        return self.audio8_ready

    def prepare(self):
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.audio.mkdir(parents=True, exist_ok=True)
