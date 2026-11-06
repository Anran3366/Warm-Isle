from __future__ import annotations

import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import tts  # noqa: E402
from qqbot import ConfigStore, EventStore, QQBotAPI  # noqa: E402


class ConfigStoreTest(unittest.TestCase):
    def test_public_view_never_returns_secret(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            with patch.dict(
                os.environ,
                {
                    "QQ_APP_ID": "123456",
                    "QQ_APP_SECRET": "super-secret-value",
                    "QQ_TARGET_ID": "abc",
                },
                clear=False,
            ):
                config = ConfigStore(path)
                config.update({"live": True})
                public = config.public()
                self.assertEqual(public["app_id"], "123456")
                self.assertTrue(public["secret_configured"])
                self.assertEqual(public["secret_tail"], "alue")
                self.assertNotIn("super-secret", str(public))
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)


class QQBotAPITest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.config = ConfigStore(Path(self.tmp.name) / "config.json")
        self.config.update(
            {
                "app_id": "app",
                "app_secret": "secret",
                "target_type": "c2c",
                "target_id": "user-openid",
                "live": True,
            }
        )
        self.store = EventStore()
        self.api = QQBotAPI(self.config, self.store)

    def tearDown(self):
        self.tmp.cleanup()

    def test_message_paths(self):
        self.assertEqual(
            self.api._message_path("c2c", "u1"),
            "/v2/users/u1/messages",
        )
        self.assertEqual(
            self.api._files_path("group", "g1"),
            "/v2/groups/g1/files",
        )

    def test_upload_uses_base64_inline_data(self):
        with patch.object(
            self.api,
            "request",
            return_value={"file_info": "FILE_INFO", "file_uuid": "uuid"},
        ) as request:
            result = self.api.upload_media(
                b"hello-image",
                "sticker.png",
                1,
                "c2c",
                "user-openid",
            )
        self.assertEqual(result["file_info"], "FILE_INFO")
        payload = request.call_args.kwargs["payload"]
        self.assertEqual(payload["file_type"], 1)
        self.assertEqual(payload["file_name"], "sticker.png")
        self.assertFalse(payload["srv_send_msg"])
        self.assertEqual(payload["file_data"], "aGVsbG8taW1hZ2U=")

    def test_text_payload(self):
        with patch.object(self.api, "request", return_value={"id": "m1"}) as request:
            self.api.send_text("你好", "c2c", "user-openid")
        self.assertEqual(
            request.call_args.kwargs["payload"],
            {"content": "你好", "msg_type": 0},
        )

    def test_active_message_error_hint(self):
        hint = self.api._friendly_error({"code": 40034105}, "/v2/users/x/messages")
        self.assertIn("主动消息权限", hint)


@unittest.skipUnless(shutil.which("say"), "需要 macOS say")
class TTSTest(unittest.TestCase):
    def test_synth_generates_tencent_silk(self):
        with tempfile.TemporaryDirectory() as tmp:
            try:
                audio = tts.synth("你好，这是一条测试语音。", "Tingting", output_dir=Path(tmp))
            except Exception as exc:  # pysilk 可能没装，允许 WAV 回退
                self.skipTest(f"TTS 不可用：{exc}")
            self.assertGreater(audio["duration"], 0)
            data = audio["path"].read_bytes()
            if audio["format"] == "silk":
                self.assertTrue(data.startswith(b"\x02#!SILK_V3"))
            else:
                self.assertTrue(data.startswith(b"RIFF"))


if __name__ == "__main__":
    unittest.main()
