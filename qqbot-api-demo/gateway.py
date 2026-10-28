"""QQ 机器人 WebSocket 网关监听。

用途：Demo 需要接收人的 openid 才能主动发消息。启动后，用户在 QQ 里给机器人
发一条消息，这里就会捕获 user_openid / group_openid，并在页面上自动填入。

文档：https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html
"""

from __future__ import annotations

import json
import threading
import time
from typing import Callable

import requests
import websocket

from qqbot import API_BASE, QQBotAPI, now_iso

INTENT_GROUP_AND_C2C = 1 << 25


class GatewayListener:
    def __init__(
        self,
        api: QQBotAPI,
        on_message: Callable[[str, dict], None],
        on_status: Callable[[str, str], None],
    ):
        self.api = api
        self.on_message = on_message
        self.on_status = on_status
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._restart = threading.Event()
        self._ws: websocket.WebSocket | None = None
        self._seen: set[str] = set()
        self.status = "disabled"
        self.last_error = ""

    @property
    def running(self) -> bool:
        return bool(self._thread and self._thread.is_alive())

    def start(self) -> None:
        if self.running:
            return
        self._stop.clear()
        self._restart.clear()
        self._thread = threading.Thread(target=self._run, name="qq-gateway", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        ws = self._ws
        if ws is not None:
            try:
                ws.close()
            except Exception:
                pass
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3)
        self._thread = None

    def restart(self) -> None:
        self._restart.set()
        ws = self._ws
        if ws is not None:
            try:
                ws.close()
            except Exception:
                pass
        if not self.running:
            self.start()

    def _set_status(self, status: str, error: str = "") -> None:
        self.status = status
        self.last_error = error
        self.on_status(status, error)

    def _run(self) -> None:
        backoff = 2
        while not self._stop.is_set():
            if not self.api.config.ready:
                self._set_status("disabled", "未配置 AppID / AppSecret")
                self._stop.wait(2)
                continue
            try:
                self._set_status("connecting")
                self._connect_once()
                backoff = 2
            except Exception as exc:  # noqa: BLE001 - 网络线程需要兜底
                if self._stop.is_set():
                    break
                message = self._safe_error(exc)
                self._set_status("reconnecting", message)
                self._stop.wait(backoff)
                backoff = min(backoff * 2, 30)
                continue
            if self._stop.is_set():
                break
            self._set_status("reconnecting", "连接已断开，正在重连")
            self._stop.wait(2)

    @staticmethod
    def _safe_error(exc: Exception) -> str:
        text = str(exc) or exc.__class__.__name__
        # 不把可能出现的长 token 带到前端
        if len(text) > 220:
            text = text[:220] + "…"
        return text

    def _connect_once(self) -> None:
        token = self.api.get_token()
        headers = {"Authorization": f"QQBot {token}"}
        resp = requests.get(
            API_BASE + "/gateway/bot",
            headers=headers,
            timeout=15,
        )
        if resp.status_code != 200:
            raise RuntimeError(f"获取 WebSocket 网关失败：HTTP {resp.status_code}")
        url = (resp.json() or {}).get("url")
        if not url:
            raise RuntimeError("网关响应里没有 url")

        ws = websocket.create_connection(
            url,
            timeout=12,
            header=[f"Authorization: QQBot {token}"],
        )
        self._ws = ws
        ws.settimeout(1.0)
        heartbeat_interval = 40.0
        next_heartbeat = time.time() + heartbeat_interval
        seq: int | None = None
        identified = False

        try:
            while not self._stop.is_set() and not self._restart.is_set():
                now = time.time()
                if now >= next_heartbeat:
                    try:
                        ws.send(json.dumps({"op": 1, "d": seq}))
                    except Exception:
                        break
                    next_heartbeat = now + heartbeat_interval

                try:
                    raw = ws.recv()
                except websocket.WebSocketTimeoutException:
                    continue
                except Exception:
                    break
                if not raw:
                    break
                try:
                    payload = json.loads(raw)
                except ValueError:
                    continue
                op = payload.get("op")
                if op == 10:
                    heartbeat_interval = max(
                        5.0, float((payload.get("d") or {}).get("heartbeat_interval", 40000)) / 1000
                    )
                    next_heartbeat = time.time() + heartbeat_interval
                    identify = {
                        "op": 2,
                        "d": {
                            "token": f"QQBot {token}",
                            "intents": INTENT_GROUP_AND_C2C,
                            "shard": [0, 1],
                            "properties": {
                                "$os": "macOS",
                                "$browser": "local-demo",
                                "$device": "local-demo",
                            },
                        },
                    }
                    ws.send(json.dumps(identify))
                    identified = True
                elif op == 0:
                    seq = payload.get("s", seq)
                    event = payload.get("t") or ""
                    data = payload.get("d") or {}
                    if event == "READY":
                        self._set_status("online")
                    elif event in {"C2C_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE"}:
                        self._handle_event(event, data)
                elif op == 7:
                    self._set_status("reconnecting", "服务端要求重连")
                    break
                elif op == 9:
                    self._set_status("reconnecting", "会话失效，重新鉴权")
                    break
                elif op == 11:
                    pass
        finally:
            try:
                ws.close()
            except Exception:
                pass
            self._ws = None
            if identified and not self._stop.is_set() and not self._restart.is_set():
                self._set_status("reconnecting", "连接已断开")

    def _handle_event(self, event: str, data: dict) -> None:
        event_id = data.get("id") or ""
        if event_id and event_id in self._seen:
            return
        if event_id:
            self._seen.add(event_id)
            if len(self._seen) > 500:
                self._seen = set(list(self._seen)[-300:])
        author = data.get("author") or {}
        content = data.get("content") or ""
        if event == "C2C_MESSAGE_CREATE":
            openid = author.get("user_openid") or author.get("id") or ""
            contact_type = "c2c"
            name = author.get("username") or "QQ 用户"
        else:
            openid = data.get("group_openid") or ""
            contact_type = "group"
            name = data.get("group_name") or "QQ 群"
        if not openid:
            return
        payload = {
            "event": event,
            "type": contact_type,
            "id": openid,
            "name": name,
            "content": content,
            "timestamp": data.get("timestamp") or now_iso(),
            "attachments": data.get("attachments") or [],
            "message_id": data.get("id") or "",
        }
        self.on_message(event, payload)
