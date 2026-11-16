"""Stable reference-voice IDs shared by Audio8, Android and the workbench."""

DEFAULT_CLONE_ID = "a63c22269e594284805a0ffaadb784a1"
DEFAULT_VOICE_NAME = "cwns.mp3"
DEFAULT_PRESET_ID = "clone-" + DEFAULT_CLONE_ID
BUILTIN_PRESET_IDS: set[str] = set()

NARRATOR_INSTRUCTION = (
    "You are a helpful assistant. 用标准普通话，以平静自然的语气讲述故事，语速适中。<|endofprompt|>"
)

def available_voices(_settings):
    """Voices come from uploaded references, rather than fixed speaker IDs."""
    return []


def is_clone_speaker(speaker: str) -> bool:
    return isinstance(speaker, str) and speaker.startswith("clone:")


def display_instruction(instruct: str) -> str:
    if instruct in {"参考音频克隆", ""}:
        instruct = NARRATOR_INSTRUCTION
    return instruct.removeprefix("You are a helpful assistant.").removesuffix("<|endofprompt|>").strip()
