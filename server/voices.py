"""Published ZONOS3 voice versions shared by Android and the workbench."""

DEFAULT_CLONE_ID = "linchuan-zonos3-v1"
DEFAULT_VOICE_NAME = "林川"
DEFAULT_PRESET_ID = "clone-" + DEFAULT_CLONE_ID
HAORAN_PRESET_ID = 'zonos3-haoran-v1'
BUILTIN_PRESET_IDS: set[str] = {HAORAN_PRESET_ID}

NARRATOR_INSTRUCTION = (
    "You are a helpful assistant. 用标准普通话，以平静自然的语气讲述故事，语速适中。<|endofprompt|>"
)

def available_voices(_settings):
    return [dict(preset_id=HAORAN_PRESET_ID,speaker='zonos:haoran',label='Haoran',
                 description='中文男声 · 官方预置',native_language='中文')]


def is_clone_speaker(speaker: str) -> bool:
    return isinstance(speaker, str) and speaker.startswith("clone:")


def display_instruction(instruct: str) -> str:
    if instruct in {"参考音频克隆", ""}:
        instruct = NARRATOR_INSTRUCTION
    return instruct.removeprefix("You are a helpful assistant.").removesuffix("<|endofprompt|>").strip()
