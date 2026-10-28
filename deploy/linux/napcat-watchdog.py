#!/usr/bin/env python3
"""
NapCat 掉线看门狗
- 每 5 分钟由 systemd timer 触发
- 机器人离线时：重启 NapCat 触发重新登录 → 抓二维码 → 邮件发送
- 最多连续告警 5 次，之后停止；恢复在线后计数清零

配置来自 /srv/qqbot/deploy/linux/.env：
  ADMIN_PASSWORD        后台密码（用于查 /api/status）
  SMTP_HOST             如 smtp.qq.com
  SMTP_PORT             465（SSL）或 587（STARTTLS）
  SMTP_USER             发件邮箱
  SMTP_PASS             邮箱授权码（不是登录密码）
  ALERT_EMAIL           收件邮箱，默认 100000000@qq.com
"""

import json
import os
import smtplib
import ssl
import subprocess
import sys
import time
import urllib.request
import http.cookiejar
from email.message import EmailMessage

ENV_FILE = "/srv/qqbot/deploy/linux/.env"
COMPOSE_DIR = "/srv/qqbot/deploy/linux"
STATE_FILE = "/var/lib/qqbot-watchdog.json"
QR_IN_CONTAINER = "/app/napcat/cache/qrcode.png"
QR_ON_HOST = "/tmp/napcat-qrcode.png"
API = "http://127.0.0.1:8080"
MAX_ALERTS = 5


def load_env(path=ENV_FILE):
    cfg = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    except FileNotFoundError:
        pass
    return cfg


def read_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"count": 0, "last": 0}


def write_state(state):
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f)


def wait_online(password, seconds=120):
    """重启后等快速登录生效，避免明明已经登录还去抓二维码发邮件"""
    start = time.time()
    while time.time() - start < seconds:
        if bot_online(password):
            return True
        time.sleep(5)
    return False


def fetch_bot(password):
    """登录后台并返回 /api/status 的 bot 段；任何异常返回 {}（视为离线）"""
    try:
        jar = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        req = urllib.request.Request(
            API + "/api/auth/login",
            data=json.dumps({"password": password}).encode(),
            headers={"Content-Type": "application/json"},
        )
        opener.open(req, timeout=8).read()
        st = json.loads(opener.open(API + "/api/status", timeout=8).read())
        return st.get("bot") or {}
    except Exception as e:
        print("status check failed:", e)
        return {}


def bot_online(password):
    bot = fetch_bot(password)
    return bool(bot.get("online")) and bool(bot.get("napcat"))


def restart_napcat():
    print("restarting napcat ...")
    subprocess.run(["docker", "compose", "restart", "napcat"], cwd=COMPOSE_DIR,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)


def fetch_qrcode(wait_seconds=90):
    """重启后等待容器内生成二维码，复制到宿主机"""
    start = time.time()
    while time.time() - start < wait_seconds:
        r = subprocess.run(["docker", "cp", "napcat:" + QR_IN_CONTAINER, QR_ON_HOST],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if r.returncode == 0 and os.path.getsize(QR_ON_HOST) > 0:
            print("qrcode fetched:", os.path.getsize(QR_ON_HOST), "bytes")
            return True
        time.sleep(5)
    return False


def send_mail(cfg, attempt, has_qr):
    host = cfg.get("SMTP_HOST", "smtp.qq.com")
    port = int(cfg.get("SMTP_PORT", "465"))
    user = cfg.get("SMTP_USER", "")
    password = cfg.get("SMTP_PASS", "")
    to_addr = cfg.get("ALERT_EMAIL", "100000000@qq.com")
    if not user or not password:
        print("SMTP 未配置，跳过发信")
        return False

    msg = EmailMessage()
    msg["Subject"] = f"[AI Companion] 机器人掉线，需要重新扫码（第 {attempt}/{MAX_ALERTS} 次）"
    msg["From"] = user
    msg["To"] = to_addr
    body = (
        "机器人当前不在线，已自动重启 NapCat 尝试重新登录。\n\n"
        f"告警次数：{attempt}/{MAX_ALERTS}\n"
        "如果附件里有二维码，请用手机 QQ 扫码登录机器人账号。\n"
        "登录成功后本告警会自动停止。\n\n"
        f"服务器时间：{time.strftime('%Y-%m-%d %H:%M:%S')}\n"
    )
    msg.set_content(body)

    if has_qr and os.path.exists(QR_ON_HOST):
        with open(QR_ON_HOST, "rb") as f:
            msg.add_attachment(f.read(), maintype="image", subtype="png",
                               filename="napcat-qrcode.png")

    try:
        if port == 465:
            ctx = ssl.create_default_context()
            with smtplib.SMTP_SSL(host, port, context=ctx, timeout=30) as s:
                s.login(user, password)
                s.send_message(msg)
        else:
            with smtplib.SMTP(host, port, timeout=30) as s:
                s.starttls(context=ssl.create_default_context())
                s.login(user, password)
                s.send_message(msg)
        print("mail sent to", to_addr)
        return True
    except Exception as e:
        print("send mail failed:", e)
        return False


PAUSE_FLAG = "/srv/qqbot/deploy/linux/data/qqbot/watchdog.pause"

# 重启退避：连续失败时按 5 → 10 → 20 → 40 分钟逐步拉长间隔。
# 固定每 5 分钟硬重启会让"重启后还在扫码"的窗口被反复打断，二维码还没扫完就又被杀一次。
BACKOFF_SECONDS = [300, 600, 1200, 2400]


def backoff_for(attempt: int) -> int:
    """第 attempt 次失败后，隔多久再试（秒）"""
    idx = min(max(attempt, 1), len(BACKOFF_SECONDS)) - 1
    return BACKOFF_SECONDS[idx]


def main():
    if os.path.exists(PAUSE_FLAG):
        print("已手动下线（watchdog.pause 存在），跳过")
        return 0
    cfg = load_env()
    state = read_state()
    password = cfg.get("ADMIN_PASSWORD", "")

    bot = fetch_bot(password)
    if bot.get("online") and bot.get("napcat"):
        if state.get("count") or state.get("next_check"):
            print("bot online, reset alert counter")
            write_state({"count": 0, "last": 0})
        return 0

    # 退避窗口内：不重启、不重复告警
    next_check = int(state.get("next_check", 0) or 0)
    if next_check and time.time() < next_check:
        print(f"退避中，{next_check - int(time.time())} 秒后再检查")
        return 0

    if state.get("count", 0) >= MAX_ALERTS:
        print(f"已达 {MAX_ALERTS} 次告警上限，只重启不再发信")
        send_mail_enabled = False
    else:
        send_mail_enabled = True

    attempt = state.get("count", 0) + 1

    # 关键分支：NapCat 连着但没登录（等扫码）。
    # 这时重启容器只会把当前二维码冲掉、让扫码更不可能完成，所以只取二维码发信。
    if bot.get("napcat") and not bot.get("online"):
        print(f"NapCat 已连接但未登录（等扫码），跳过重启，attempt {attempt}/{MAX_ALERTS}")
        has_qr = fetch_qrcode(wait_seconds=10)
        ok = send_mail(cfg, attempt, has_qr) if send_mail_enabled else False
        delay = backoff_for(attempt)
        write_state({"count": attempt if send_mail_enabled else state.get("count", 0),
                     "last": int(time.time()), "mail_ok": ok,
                     "next_check": int(time.time()) + delay})
        print(f"{delay // 60} 分钟后再检查二维码")
        return 0

    print(f"bot offline, attempt {attempt}/{MAX_ALERTS}")

    last_restart = state.get("last_restart", 0)
    if time.time() - last_restart < 600 and os.path.exists(QR_ON_HOST):
        print("10 分钟内已重启过且已有二维码，保留当前二维码，避免扫码时失效")
        return 0

    restart_napcat()
    if wait_online(password, 120):
        print("重启后快速登录成功，无需扫码")
        write_state({"count": 0, "last": 0, "last_restart": int(time.time())})
        return 0

    has_qr = fetch_qrcode()
    ok = False
    if send_mail_enabled:
        ok = send_mail(cfg, attempt, has_qr)

    delay = backoff_for(attempt)
    print(f"本次仍未恢复，{delay // 60} 分钟后再试")
    write_state({"count": attempt if send_mail_enabled else state.get("count", 0),
                 "last": int(time.time()), "mail_ok": ok,
                 "last_restart": int(time.time()),
                 "next_check": int(time.time()) + delay})
    return 0


if __name__ == "__main__":
    sys.exit(main())
