"""QQ 机器人开放平台 API v2 的最小封装。

只依赖 requests，覆盖本 Demo 需要的：
- AppAccessToken 获取与缓存
- 机器人资料
- 主动发送单聊 / 群聊文本
- 富媒体上传 + 发送（图片、语音）

文档：https://bot.q.qq.com/wiki/develop/api-v2/
"""

from __future__ import annotations

import base64
import json
import os
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any

import requests

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"
CONFIG_FILE = DATA_DIR / "config.json"
TOKEN_URL = "https://api.bot.qq.com/app/getAppAccessToken"
API_BASE = "https://api.bot.qq.com"
MAX_INLINE_UPLOAD = 5 * 1024 * 1024  # 官方二进制直传上限 5MB


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def load_dotenv(path: Path) -> None:
    """极简 .env 解析，不覆盖已经存在的环境变量。"""
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def _as_bool(value: Any, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return default
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


class DemoError(Exception):
    """可以直接展示给用户的错误。"""

    def __init__(self, message: str, *, status: int = 400, detail: Any = None):
        super().__init__(message)
        self.message = message
        self.status = status
        self.detail = detail


class QQAPIError(DemoError):
    def __init__(self, message: str, *, status: int = 502, detail: Any = None):
        super().__init__(message, status=status, detail=detail)


class ConfigStore:
    """本地配置。密钥只保存在 data/config.json，不返回给前端明文。"""

    def __init__(self, path: Path = CONFIG_FILE):
        self.path = path
        self.lock = threading.RLock()
        self.data = self._load()

    def _load(self) -> dict:
        data = {
            "app_id": os.getenv("QQ_APP_ID", "").strip(),
            "app_secret": os.getenv("QQ_APP_SECRET", "").strip(),
            "target_type": os.getenv("QQ_TARGET_TYPE", "c2c").strip() or "c2c",
            "target_id": os.getenv("QQ_TARGET_ID", "").strip(),
            "live": _as_bool(os.getenv("QQ_LIVE"), True),
            "auto_capture": _as_bool(os.getenv("QQ_AUTO_CAPTURE"), True),
            "tts_voice": os.getenv("QQ_TTS_VOICE", "Tingting").strip() or "Tingting",
        }
        if self.path.exists():
            try:
                stored = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(stored, dict):
                    data.update({k: v for k, v in stored.items() if v is not None})
            except (OSError, ValueError):
                pass
        if data["target_type"] not in {"c2c", "group"}:
            data["target_type"] = "c2c"
        return data

    def get(self, key: str, default: Any = None) -> Any:
        with self.lock:
            return self.data.get(key, default)

    def update(self, values: dict) -> dict:
        with self.lock:
            if values.get("app_secret") == "":
                values.pop("app_secret", None)
            for key in {
                "app_id",
                "app_secret",
                "target_type",
                "target_id",
                "live",
                "auto_capture",
                "tts_voice",
            }:
                if key in values:
                    self.data[key] = values[key]
            if self.data["target_type"] not in {"c2c", "group"}:
                self.data["target_type"] = "c2c"
            self.data["target_id"] = str(self.data.get("target_id", "")).strip()
            self._save()
            return dict(self.data)

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self.path)
        try:
            self.path.chmod(0o600)
        except OSError:
            pass

    def public(self) -> dict:
        with self.lock:
            secret = str(self.data.get("app_secret") or "")
            return {
                "app_id": self.data.get("app_id", ""),
                "secret_configured": bool(secret),
                "secret_tail": secret[-4:] if secret else "",
                "target_type": self.data.get("target_type", "c2c"),
                "target_id": self.data.get("target_id", ""),
                "live": bool(self.data.get("live", True)),
                "auto_capture": bool(self.data.get("auto_capture", True)),
                "tts_voice": self.data.get("tts_voice", "Tingting"),
            }

    @property
    def ready(self) -> bool:
        with self.lock:
            return bool(self.data.get("app_id") and self.data.get("app_secret"))


class EventStore:
    """内存中的消息、联系人与请求日志。"""

    def __init__(self, limit: int = 80):
        self.lock = threading.RLock()
        self.messages: list[dict] = []
        self.contacts: dict[str, dict] = {}
        self.logs: list[dict] = []
        self.limit = limit
        self._message_seq = 0
        self._log_seq = 0

    def next_message_id(self) -> str:
        with self.lock:
            self._message_seq += 1
            return f"local_{int(time.time() * 1000)}_{self._message_seq}"

    def add_message(self, message: dict) -> dict:
        with self.lock:
            message = {**message, "id": message.get("id") or self.next_message_id()}
            self.messages.append(message)
            self.messages = self.messages[-self.limit :]
            return message

    def add_log(self, entry: dict) -> dict:
        with self.lock:
            self._log_seq += 1
            entry = {
                "id": self._log_seq,
                "time": now_iso(),
                **entry,
            }
            self.logs.append(entry)
            self.logs = self.logs[-self.limit :]
            return entry

    def upsert_contact(self, contact: dict) -> dict:
        key = f"{contact['type']}:{contact['id']}"
        with self.lock:
            old = self.contacts.get(key, {})
            merged = {**old, **contact, "key": key, "last_seen": now_iso()}
            self.contacts[key] = merged
            return merged

    def snapshot(self) -> dict:
        with self.lock:
            contacts = sorted(
                self.contacts.values(), key=lambda c: c.get("last_seen", ""), reverse=True
            )
            return {
                "messages": list(self.messages),
                "contacts": contacts,
                "logs": list(reversed(self.logs)),
            }


class QQBotAPI:
    def __init__(self, config: ConfigStore, store: EventStore):
        self.config = config
        self.store = store
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": "qqbot-api-v2-local-demo/1.0"})
        self._token: str | None = None
        self._token_expires_at = 0.0
        self._token_lock = threading.Lock()
        self._profile: dict | None = None
        self._profile_at = 0.0
        self._profile_lock = threading.Lock()

    # ------------------------------------------------------------------ token
    def invalidate_token(self) -> None:
        with self._token_lock:
            self._token = None
            self._token_expires_at = 0.0
            self._profile = None
            self._profile_at = 0.0

    def get_token(self, force: bool = False) -> str:
        with self._token_lock:
            if not force and self._token and time.time() < self._token_expires_at:
                return self._token
            app_id = self.config.get("app_id")
            secret = self.config.get("app_secret")
            if not app_id or not secret:
                raise DemoError("还没有配置 AppID / AppSecret", status=400)
            started = time.time()
            try:
                resp = self.session.post(
                    TOKEN_URL,
                    json={"appId": app_id, "clientSecret": secret},
                    timeout=15,
                )
            except requests.RequestException as exc:
                self.store.add_log(
                    {
                        "kind": "auth",
                        "method": "POST",
                        "path": "/app/getAppAccessToken",
                        "ok": False,
                        "status": None,
                        "duration_ms": int((time.time() - started) * 1000),
                        "message": f"网络错误：{exc.__class__.__name__}",
                    }
                )
                raise QQAPIError("获取 AccessToken 失败：无法连接 QQ 开放平台") from exc
            try:
                body = resp.json()
            except ValueError:
                body = {}
            if resp.status_code != 200 or not body.get("access_token"):
                message = body.get("message") or body.get("msg") or f"HTTP {resp.status_code}"
                self.store.add_log(
                    {
                        "kind": "auth",
                        "method": "POST",
                        "path": "/app/getAppAccessToken",
                        "ok": False,
                        "status": resp.status_code,
                        "duration_ms": int((time.time() - started) * 1000),
                        "message": f"AccessToken 获取失败：{message}",
                    }
                )
                raise QQAPIError(f"AccessToken 获取失败：{message}")
            self._token = body["access_token"]
            expires_in = int(body.get("expires_in") or 7200)
            # 提前 60 秒刷新
            self._token_expires_at = time.time() + max(expires_in - 60, 60)
            self.store.add_log(
                {
                    "kind": "auth",
                    "method": "POST",
                    "path": "/app/getAppAccessToken",
                    "ok": True,
                    "status": resp.status_code,
                    "duration_ms": int((time.time() - started) * 1000),
                    "message": f"AccessToken 已获取，有效期 {expires_in}s",
                }
            )
            return self._token

    # ------------------------------------------------------------------ request
    def _redact(self, value: Any) -> Any:
        if isinstance(value, dict):
            result = {}
            for key, item in value.items():
                if key in {"file_data", "appSecret", "clientSecret", "access_token"}:
                    result[key] = f"[已隐藏 {len(str(item))} 字符]"
                else:
                    result[key] = self._redact(item)
            return result
        if isinstance(value, list):
            return [self._redact(item) for item in value]
        return value

    def request(
        self,
        method: str,
        path: str,
        *,
        payload: dict | None = None,
        timeout: int = 25,
        kind: str = "api",
        retry_auth: bool = True,
    ) -> dict:
        token = self.get_token()
        headers = {"Authorization": f"QQBot {token}", "Content-Type": "application/json"}
        started = time.time()
        try:
            resp = self.session.request(
                method,
                API_BASE + path,
                headers=headers,
                json=payload,
                timeout=timeout,
            )
        except requests.RequestException as exc:
            self.store.add_log(
                {
                    "kind": kind,
                    "method": method,
                    "path": path,
                    "ok": False,
                    "status": None,
                    "duration_ms": int((time.time() - started) * 1000),
                    "message": f"网络错误：{exc.__class__.__name__}",
                    "request": self._redact(payload or {}),
                }
            )
            raise QQAPIError(f"请求 QQ 开放平台失败：{exc.__class__.__name__}") from exc

        duration = int((time.time() - started) * 1000)
        try:
            body = resp.json() if resp.content else {}
        except ValueError:
            body = {"raw": resp.text[:300]}
        ok = 200 <= resp.status_code < 300 and not (
            isinstance(body, dict) and body.get("code") not in (None, 0)
        )
        message = ""
        if isinstance(body, dict):
            message = str(body.get("message") or body.get("msg") or "")
            if not message and body.get("code"):
                message = f"业务错误码 {body['code']}"
        if not message:
            message = "请求成功" if ok else f"HTTP {resp.status_code}"
        self.store.add_log(
            {
                "kind": kind,
                "method": method,
                "path": path,
                "ok": ok,
                "status": resp.status_code,
                "duration_ms": duration,
                "message": message,
                "biz_code": body.get("code") if isinstance(body, dict) else None,
                "request": self._redact(payload or {}),
                "response": self._redact(body if isinstance(body, dict) else {}),
            }
        )
        if not ok:
            hint = self._friendly_error(body, path)
            raise QQAPIError(
                hint or f"接口返回 HTTP {resp.status_code}：{message}",
                status=502,
                detail=body if isinstance(body, dict) else {},
            )
        return body

    @staticmethod
    def _friendly_error(body: dict, path: str) -> str:
        code = body.get("code")
        mapping = {
            40034100: "主动消息超过频控限制，请稍后再试",
            40034105: "主动消息发送失败：机器人没有主动消息权限，或被用户关闭了主动推送",
            40054004: "还没有好友关系，请先在 QQ 里添加机器人",
            40054005: "消息被去重，请重新发送",
            40054013: "用户拒收了这条消息",
            40054016: "机器人已下线",
            304080: "文件信息无效，请重新上传",
            40034004: "富媒体转存失败，请重试",
            850019: "不支持的文件格式",
            850031: "文件超过大小限制",
            40093002: "今天的文件上传容量已达上限",
        }
        if code in mapping:
            return mapping[code]
        message = body.get("message") or body.get("msg")
        if message:
            return f"发送失败：{message}"
        return ""

    def _message_path(self, target_type: str, target_id: str) -> str:
        if target_type not in {"c2c", "group"}:
            raise DemoError("目标类型只能是 c2c 或 group")
        if not target_id:
            raise DemoError("还没有目标 openid，请先在 QQ 里给机器人发一条消息")
        segment = "users" if target_type == "c2c" else "groups"
        return f"/v2/{segment}/{target_id}/messages"

    def _files_path(self, target_type: str, target_id: str) -> str:
        if not target_id:
            raise DemoError("还没有目标 openid，请先在 QQ 里给机器人发一条消息")
        segment = "users" if target_type == "c2c" else "groups"
        return f"/v2/{segment}/{target_id}/files"

    # ------------------------------------------------------------------ profile
    def get_profile(self, force: bool = False) -> dict:
        with self._profile_lock:
            if not force and self._profile and time.time() - self._profile_at < 300:
                return self._profile
            body = self.request("GET", "/users/@me", kind="profile")
            self._profile = body
            self._profile_at = time.time()
            return body

    # ------------------------------------------------------------------ send
    def send_text(self, text: str, target_type: str, target_id: str) -> dict:
        return self.request(
            "POST",
            self._message_path(target_type, target_id),
            payload={"content": text, "msg_type": 0},
            kind="send_text",
        )

    def upload_media(
        self,
        data: bytes,
        filename: str,
        file_type: int,
        target_type: str,
        target_id: str,
    ) -> dict:
        if len(data) > MAX_INLINE_UPLOAD:
            raise DemoError("文件超过 5MB，当前 Demo 只使用二进制直传，请换小一点的文件")
        payload = {
            "file_type": file_type,
            "file_name": filename,
            "file_data": base64.b64encode(data).decode("ascii"),
            "srv_send_msg": False,
        }
        return self.request(
            "POST",
            self._files_path(target_type, target_id),
            payload=payload,
            kind="upload_media",
            timeout=45,
        )

    def send_media(self, file_info: str, target_type: str, target_id: str) -> dict:
        if not file_info:
            raise QQAPIError("上传成功但没有拿到 file_info")
        return self.request(
            "POST",
            self._message_path(target_type, target_id),
            payload={"msg_type": 7, "media": {"file_info": file_info}},
            kind="send_media",
        )
