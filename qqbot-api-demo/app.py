"""QQ 官方机器人 API v2 · 本地 Demo

功能：
1. 主动发送文本消息
2. 上传图片 / 表情包并以富媒体消息发送
3. 输入文字，本地 TTS 合成 SILK 语音后发送

运行：python app.py  然后打开 http://127.0.0.1:5178
"""

from __future__ import annotations

import mimetypes
import re
import time
import uuid
from pathlib import Path

from flask import Flask, jsonify, request, send_from_directory

import tts
from gateway import GatewayListener
from qqbot import (
    DATA_DIR,
    DemoError,
    EventStore,
    ConfigStore,
    QQBotAPI,
    QQAPIError,
    load_dotenv,
    now_iso,
)

ROOT = Path(__file__).resolve().parent
UPLOAD_DIR = DATA_DIR / "uploads"
STATIC_DIR = ROOT / "static"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

load_dotenv(ROOT / ".env")
config = ConfigStore()
store = EventStore()
api = QQBotAPI(config, store)

app = Flask(__name__, static_folder=str(STATIC_DIR), static_url_path="/static")
app.config["MAX_CONTENT_LENGTH"] = 6 * 1024 * 1024

_last_gateway_status = ""


def _on_gateway_status(status: str, error: str) -> None:
    global _last_gateway_status
    if status == _last_gateway_status:
        return
    _last_gateway_status = status
    if status in {"online", "reconnecting", "disabled"}:
        store.add_log(
            {
                "kind": "gateway",
                "method": "WS",
                "path": "/gateway/bot",
                "ok": status == "online",
                "status": None,
                "duration_ms": 0,
                "message": {
                    "online": "WebSocket 已连接，等待用户消息",
                    "reconnecting": f"WebSocket 重连中：{error}",
                    "disabled": f"WebSocket 未启用：{error}",
                }.get(status, status),
            }
        )


def _on_gateway_message(event: str, data: dict) -> None:
    contact_type = data["type"]
    contact_id = data["id"]
    contact = store.upsert_contact(
        {
            "type": contact_type,
            "id": contact_id,
            "name": data.get("name") or ("QQ 用户" if contact_type == "c2c" else "QQ 群"),
            "last_message": data.get("content") or "[附件消息]",
        }
    )
    store.add_message(
        {
            "direction": "in",
            "kind": "text",
            "content": data.get("content") or "[收到一条附件消息]",
            "target_type": contact_type,
            "target_id": contact_id,
            "target_name": contact["name"],
            "status": "received",
            "mock": False,
            "ts": now_iso(),
            "api_message_id": data.get("message_id"),
        }
    )
    store.add_log(
        {
            "kind": "gateway",
            "method": "EVENT",
            "path": event,
            "ok": True,
            "status": None,
            "duration_ms": 0,
            "message": f"捕获 {contact_type} openid：{contact_id[:8]}…",
        }
    )
    # 第一次收到消息时自动填入目标，之后不覆盖用户手动选择
    if not config.get("target_id"):
        config.update(
            {
                "target_type": contact_type,
                "target_id": contact_id,
            }
        )


gateway = GatewayListener(
    api,
    lambda event, data: _on_gateway_message(event, data),
    _on_gateway_status,
)


def _start_gateway_if_needed() -> None:
    if config.ready and config.get("auto_capture", True):
        gateway.start()
    else:
        gateway.stop()


def _resolve_target(payload: dict | None = None) -> tuple[str, str]:
    payload = payload or {}
    target_type = payload.get("target_type") or config.get("target_type") or "c2c"
    target_id = str(payload.get("target_id") or config.get("target_id") or "").strip()
    if target_type not in {"c2c", "group"}:
        raise DemoError("目标类型只能是单聊或群聊")
    if config.get("live", True) and not target_id:
        raise DemoError("还没有目标 openid。先在 QQ 里给机器人发一条消息，或手动填写 openid")
    return target_type, target_id


def _remember_target(target_type: str, target_id: str) -> None:
    if target_id and (
        config.get("target_type") != target_type or config.get("target_id") != target_id
    ):
        config.update({"target_type": target_type, "target_id": target_id})


def _base_message(kind: str, target_type: str, target_id: str) -> dict:
    return {
        "direction": "out",
        "kind": kind,
        "target_type": target_type,
        "target_id": target_id,
        "ts": now_iso(),
        "status": "sending",
        "mock": not config.get("live", True),
    }


def _record_failed(message: dict, error: str) -> dict:
    message["status"] = "failed"
    message["error"] = error
    return store.add_message(message)


def _as_bool(value, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).lower() in {"1", "true", "yes", "on"}


def _mock_id(prefix: str = "mock") -> str:
    return f"{prefix}_{uuid.uuid4().hex[:16]}"


# --------------------------------------------------------------------- pages
@app.get("/")
def index():
    return send_from_directory(STATIC_DIR, "index.html")


@app.get("/media/uploads/<path:name>")
def media_upload(name: str):
    return send_from_directory(UPLOAD_DIR, name, max_age=3600)


@app.get("/media/audio/<path:name>")
def media_audio(name: str):
    return send_from_directory(tts.AUDIO_DIR, name, max_age=3600)


# --------------------------------------------------------------------- state
@app.get("/api/state")
def api_state():
    snapshot = store.snapshot()
    bot_profile = None
    if config.ready:
        try:
            profile = api.get_profile()
            bot_profile = {
                "id": profile.get("id"),
                "username": profile.get("username") or "小满",
                "avatar": profile.get("avatar") or "",
                "share_url": profile.get("share_url") or "",
            }
        except DemoError:
            bot_profile = None
    return jsonify(
        {
            "ok": True,
            "config": config.public(),
            "configured": config.ready,
            "bot": bot_profile,
            "gateway": {
                "status": gateway.status,
                "error": gateway.last_error,
                "running": gateway.running,
            },
            "runtime": {
                "tts_available": bool(__import__("shutil").which("say")),
                "tts_silk": _silk_available(),
                "voices": tts.VOICES,
                "server_time": now_iso(),
                "upload_limit_mb": 5,
            },
            **snapshot,
        }
    )


def _silk_available() -> bool:
    try:
        import pysilk  # noqa: F401

        return True
    except Exception:
        return False


@app.post("/api/config")
def api_config():
    body = request.get_json(silent=True) or {}
    values: dict = {}
    if "app_id" in body:
        values["app_id"] = str(body["app_id"]).strip()
    if "app_secret" in body:
        values["app_secret"] = str(body["app_secret"]).strip()
    if "target_type" in body:
        values["target_type"] = str(body["target_type"]).strip()
    if "target_id" in body:
        values["target_id"] = str(body["target_id"]).strip()
    if "live" in body:
        values["live"] = _as_bool(body["live"], True)
    if "auto_capture" in body:
        values["auto_capture"] = _as_bool(body["auto_capture"], True)
    if "tts_voice" in body:
        values["tts_voice"] = str(body["tts_voice"]).strip()

    credential_changed = (
        values.get("app_id", config.get("app_id")) != config.get("app_id")
        or ("app_secret" in values and values["app_secret"])
    )
    old_secret = config.get("app_secret")
    config.update(values)
    if credential_changed or config.get("app_secret") != old_secret:
        api.invalidate_token()
        gateway.stop()
        _start_gateway_if_needed()
    elif config.get("auto_capture", True):
        _start_gateway_if_needed()
    else:
        gateway.stop()
    return jsonify({"ok": True, "config": config.public()})


@app.post("/api/test")
def api_test():
    api.invalidate_token()
    token = api.get_token(force=True)
    profile = api.get_profile(force=True)
    return jsonify(
        {
            "ok": True,
            "token_ok": bool(token),
            "bot": {
                "username": profile.get("username"),
                "avatar": profile.get("avatar"),
                "share_url": profile.get("share_url"),
            },
        }
    )


@app.post("/api/gateway/restart")
def api_gateway_restart():
    if not config.get("auto_capture", True):
        config.update({"auto_capture": True})
    gateway.restart()
    return jsonify({"ok": True, "gateway": {"status": gateway.status}})


# --------------------------------------------------------------------- send text
@app.post("/api/send/text")
def api_send_text():
    body = request.get_json(silent=True) or {}
    text = str(body.get("text") or "").strip()
    if not text:
        raise DemoError("请输入要发送的文字")
    if len(text) > 1000:
        raise DemoError("单条文字最多 1000 字")
    target_type, target_id = _resolve_target(body)
    _remember_target(target_type, target_id)
    message = _base_message("text", target_type, target_id)
    message["content"] = text

    if not config.get("live", True):
        message.update({"status": "sent", "api_message_id": _mock_id()})
        store.add_message(message)
        store.add_log(
            {
                "kind": "mock",
                "method": "POST",
                "path": f"/v2/{'users' if target_type == 'c2c' else 'groups'}/{{openid}}/messages",
                "ok": True,
                "status": 200,
                "duration_ms": 0,
                "message": "模拟发送（未调用官方 API）",
                "request": {"content": text, "msg_type": 0},
            }
        )
        return jsonify({"ok": True, "mock": True, "message": message})

    try:
        result = api.send_text(text, target_type, target_id)
    except DemoError as exc:
        _record_failed(message, exc.message)
        raise
    message.update(
        {
            "status": "sent",
            "api_message_id": result.get("id"),
            "api_timestamp": result.get("timestamp"),
        }
    )
    store.add_message(message)
    return jsonify({"ok": True, "message": message, "api": result})


# --------------------------------------------------------------------- send sticker
IMAGE_SIGNATURES = (
    (b"\x89PNG\r\n\x1a\n", "png", "image/png"),
    (b"\xff\xd8\xff", "jpg", "image/jpeg"),
    (b"GIF87a", "gif", "image/gif"),
    (b"GIF89a", "gif", "image/gif"),
    (b"BM", "bmp", "image/bmp"),
)


def _sniff_image(data: bytes) -> tuple[str, str] | None:
    for sig, ext, mime in IMAGE_SIGNATURES:
        if data.startswith(sig):
            return ext, mime
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp", "image/webp"
    return None


@app.post("/api/send/sticker")
def api_send_sticker():
    upload = request.files.get("file")
    if upload is None or not upload.filename:
        raise DemoError("请先选择一张表情包图片")
    data = upload.read()
    if not data:
        raise DemoError("图片是空文件")
    if len(data) > 5 * 1024 * 1024:
        raise DemoError("图片不能超过 5MB（当前 Demo 使用二进制直传）")
    sniffed = _sniff_image(data)
    if not sniffed:
        raise DemoError("只支持 png / jpg / gif / webp / bmp 图片")
    ext, mime = sniffed
    target_type, target_id = _resolve_target(request.form)
    _remember_target(target_type, target_id)

    stored_name = f"{uuid.uuid4().hex[:16]}.{ext}"
    stored_path = UPLOAD_DIR / stored_name
    stored_path.write_bytes(data)
    original = re.sub(r"[^0-9A-Za-z_.\-\u4e00-\u9fff]", "_", upload.filename)[-40:]
    if not original.lower().endswith("." + ext):
        original = f"sticker.{ext}"

    message = _base_message("sticker", target_type, target_id)
    message.update(
        {
            "content": "[表情包]",
            "media_url": f"/media/uploads/{stored_name}",
            "filename": original,
            "size": len(data),
        }
    )
    if not config.get("live", True):
        message.update({"status": "sent", "api_message_id": _mock_id()})
        store.add_message(message)
        store.add_log(
            {
                "kind": "mock",
                "method": "POST",
                "path": f"/v2/{'users' if target_type == 'c2c' else 'groups'}/{{openid}}/files",
                "ok": True,
                "status": 200,
                "duration_ms": 0,
                "message": "模拟上传表情包（未调用官方 API）",
                "request": {"file_type": 1, "file_name": original, "size": len(data)},
            }
        )
        return jsonify({"ok": True, "mock": True, "message": message})

    try:
        uploaded = api.upload_media(data, original, 1, target_type, target_id)
        sent = api.send_media(uploaded.get("file_info"), target_type, target_id)
    except DemoError as exc:
        _record_failed(message, exc.message)
        raise
    message.update(
        {
            "status": "sent",
            "api_message_id": sent.get("id"),
            "file_uuid": uploaded.get("file_uuid"),
            "ttl": uploaded.get("ttl"),
        }
    )
    store.add_message(message)
    return jsonify({"ok": True, "message": message, "upload": uploaded, "api": sent})


# --------------------------------------------------------------------- send voice
@app.post("/api/send/voice")
def api_send_voice():
    body = request.get_json(silent=True) or {}
    text = str(body.get("text") or "").strip()
    voice = str(body.get("voice") or config.get("tts_voice") or "Tingting")
    target_type, target_id = _resolve_target(body)
    _remember_target(target_type, target_id)

    audio = tts.synth(text, voice)
    message = _base_message("voice", target_type, target_id)
    message.update(
        {
            "content": text,
            "media_url": f"/media/audio/{audio['path'].name}",
            "audio_url": f"/media/audio/{audio['wav_path'].name}",
            "duration": audio["duration"],
            "format": audio["format"],
            "voice": voice,
        }
    )
    if not config.get("live", True):
        message.update({"status": "sent", "api_message_id": _mock_id()})
        store.add_message(message)
        store.add_log(
            {
                "kind": "mock",
                "method": "POST",
                "path": f"/v2/{'users' if target_type == 'c2c' else 'groups'}/{{openid}}/files",
                "ok": True,
                "status": 200,
                "duration_ms": 0,
                "message": f"模拟发送语音（{audio['format']}，{audio['duration']}s）",
                "request": {"file_type": 3, "voice": voice, "text": text},
            }
        )
        return jsonify({"ok": True, "mock": True, "message": message, "audio": _audio_meta(audio)})

    try:
        data = audio["path"].read_bytes()
        uploaded = api.upload_media(data, audio["filename"], 3, target_type, target_id)
        sent = api.send_media(uploaded.get("file_info"), target_type, target_id)
    except DemoError as exc:
        _record_failed(message, exc.message)
        raise
    message.update(
        {
            "status": "sent",
            "api_message_id": sent.get("id"),
            "file_uuid": uploaded.get("file_uuid"),
            "ttl": uploaded.get("ttl"),
        }
    )
    store.add_message(message)
    return jsonify(
        {
            "ok": True,
            "message": message,
            "audio": _audio_meta(audio),
            "upload": uploaded,
            "api": sent,
        }
    )


def _audio_meta(audio: dict) -> dict:
    return {
        "url": f"/media/audio/{audio['wav_path'].name}",
        "format": audio["format"],
        "duration": audio["duration"],
    }


@app.get("/api/health")
def health():
    return jsonify({"ok": True, "time": now_iso()})


@app.errorhandler(DemoError)
def handle_demo_error(exc: DemoError):
    return (
        jsonify({"ok": False, "error": exc.message, "detail": exc.detail}),
        exc.status,
    )


@app.errorhandler(413)
def handle_too_large(_exc):
    return jsonify({"ok": False, "error": "文件太大，当前上限 5MB"}), 413


@app.errorhandler(Exception)
def handle_unexpected(exc: Exception):
    app.logger.exception("unexpected error")
    return jsonify({"ok": False, "error": f"服务异常：{exc.__class__.__name__}"}), 500


def main() -> None:
    _start_gateway_if_needed()
    print("")
    print("  QQ 官方机器人 API v2 · 本地 Demo")
    print("  http://127.0.0.1:5178")
    print("")
    app.run(host="127.0.0.1", port=5178, debug=False, threaded=True, use_reloader=False)


if __name__ == "__main__":
    main()
