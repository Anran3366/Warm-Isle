"""本地文字转语音。

Demo 优先使用 macOS 自带的 say 合成 WAV，再用 pysilk 编码成 QQ 语音条支持的
Tencent SILK v3。没有 pysilk 时退回 WAV（官方部分场景支持 mp3/wav/ogg）。
"""

from __future__ import annotations

import io
import shutil
import subprocess
import uuid
import wave
from pathlib import Path

from qqbot import DemoError

VOICES = {
    "Tingting": "婷婷 · 普通话",
    "Meijia": "美佳 · 台湾腔",
    "Sinji": "善怡 · 粤语",
    "Flo": "Flo · 普通话",
}

AUDIO_DIR = Path(__file__).resolve().parent / "data" / "audio"


def _silk_encode(wav_path: Path) -> bytes:
    import pysilk  # 可选依赖

    with wave.open(str(wav_path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
            raise RuntimeError("TTS 输出不是 16-bit 单声道 WAV")
        rate = wav.getframerate()
        pcm = wav.readframes(wav.getnframes())
    out = io.BytesIO()
    pysilk.encode(
        io.BytesIO(pcm),
        out,
        rate,
        24000,
        24000,
        tencent=True,
    )
    return out.getvalue()


def synth(
    text: str,
    voice: str = "Tingting",
    *,
    output_dir: Path = AUDIO_DIR,
) -> dict:
    """返回 {silk_path, wav_path, filename, file_type, duration, format}。"""
    text = (text or "").strip()
    if not text:
        raise DemoError("请输入要转成语音的文字")
    if len(text) > 300:
        raise DemoError("语音文字最多 300 字，太长啦")
    if voice not in VOICES:
        raise DemoError("不支持的音色")
    if not shutil.which("say"):
        raise DemoError("本机没有 macOS say 命令，无法做文字转语音")

    output_dir.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex[:12]
    wav_path = output_dir / f"voice_{token}.wav"
    silk_path = output_dir / f"voice_{token}.silk"

    cmd = [
        "say",
        "-v",
        voice,
        "-r",
        "180",
        "-o",
        str(wav_path),
        "--file-format=WAVE",
        "--data-format=LEI16@24000",
        text,
    ]
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=45,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise DemoError("语音合成超时，请缩短文字") from exc
    if result.returncode != 0 or not wav_path.exists():
        detail = (result.stderr or "").strip()[:200]
        raise DemoError(f"语音合成失败：{detail or 'say 命令执行失败'}")

    with wave.open(str(wav_path), "rb") as wav:
        duration = round(wav.getnframes() / float(wav.getframerate()), 1)

    try:
        silk = _silk_encode(wav_path)
        silk_path.write_bytes(silk)
        return {
            "path": silk_path,
            "wav_path": wav_path,
            "filename": f"voice_{token}.silk",
            "file_type": 3,
            "duration": duration,
            "format": "silk",
        }
    except Exception:
        # pysilk 未安装或编码失败时，回退到 WAV。部分官方场景接受 wav。
        return {
            "path": wav_path,
            "wav_path": wav_path,
            "filename": f"voice_{token}.wav",
            "file_type": 3,
            "duration": duration,
            "format": "wav",
        }
