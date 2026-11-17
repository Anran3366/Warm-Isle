#!/usr/bin/env python3
"""SnowLuma / QQ 登录窗辅助：把窗口切到扫码页、刷新过期二维码，并裁出二维码图片。

由后端在 snowluma 容器内以 DISPLAY=:1 调用：

    python3 qr_helper.py <out.png>

行为：
  1. 找 680x440 的 QQ 登录窗（登录成功后窗口会变大/消失，此时直接退出）
  2. 若停在「自动登录」页 → 点「其他登录方式」，露出扫码面板
  3. 若弹了「安全提醒 / 身份验证失败 (s26)」→ 点「确定」
  4. 若二维码已过期 → 点「刷新」，换一张新码
  5. 截取窗口右侧二维码区域，放大到 320x320 写到 <out.png>

退出码：0 = 已写出图片；3 = 没有登录窗 / 不在扫码页。
"""
import ctypes
import re
import subprocess
import sys
import time

WIN_W, WIN_H = 680, 440
QR_X, QR_Y, QR_W, QR_H = 462, 97, 154, 156
OUT_SIZE = 320

# 以下坐标都是在 680x440 登录窗内的固定位置
PT_DIALOG_OK = (459, 263)   # 「安全提醒 (s26)」弹窗的「确定」
PT_SPLIT_OK = (200, 336)    # 账密登录页的「登录」按钮（扫码页共存）
PT_SAVED_OK = (339, 351)    # 「自动登录」页的「登录」按钮
PT_OTHER = (307, 390)       # 「其他登录方式」
PT_REFRESH = (539, 193)     # 二维码过期的「刷新」
EXPIRED_TEXT_Y = 160        # 「二维码已过期」这行红字

x11 = ctypes.cdll.LoadLibrary("libX11.so.6")
xtst = ctypes.cdll.LoadLibrary("libXtst.so.6")
x11.XOpenDisplay.restype = ctypes.c_void_p
DPY = ctypes.c_void_p(x11.XOpenDisplay(None))


def click(x, y):
    """在 X 根坐标 (x, y) 上单击。"""
    if not DPY:
        return False
    xtst.XTestFakeMotionEvent(DPY, -1, x, y, 0)
    x11.XFlush(DPY)
    time.sleep(0.12)
    xtst.XTestFakeButtonEvent(DPY, 1, 1, 0)
    x11.XFlush(DPY)
    time.sleep(0.05)
    xtst.XTestFakeButtonEvent(DPY, 1, 0, 0)
    x11.XFlush(DPY)
    time.sleep(0.15)
    return True


def find_window():
    """返回 680x440 登录窗的根坐标 (x, y)，找不到返回 None。"""
    try:
        out = subprocess.run(["xwininfo", "-root", "-tree"], capture_output=True,
                             text=True, timeout=5).stdout
    except Exception:
        return None
    for line in out.splitlines():
        if '"QQ"' not in line:
            continue
        m = re.search(r"(\d+)x(\d+)\+0\+0\s+\+(\d+)\+(\d+)", line)
        if not m:
            continue
        w, h, x, y = (int(v) for v in m.groups())
        if w == WIN_W and h == WIN_H:
            return x, y
    return None


def grab(x, y):
    """抓一帧窗口原始 RGB。"""
    try:
        p = subprocess.run(
            ["ffmpeg", "-loglevel", "error", "-f", "x11grab", "-draw_mouse", "0",
             "-video_size", f"{WIN_W}x{WIN_H}", "-i", f":1+{x},{y}", "-frames:v", "1",
             "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            capture_output=True, timeout=15)
        return p.stdout
    except Exception:
        return b""


def px(buf, x, y):
    i = (y * WIN_W + x) * 3
    return buf[i], buf[i + 1], buf[i + 2]


def is_blue(c):
    r, g, b = c
    return b > 215 and r < 70 and 110 < g < 200


def is_red(c):
    r, g, b = c
    return r > 190 and g < 150 and b < 140


def qr_live(buf):
    """二维码区域黑块占比：有效码约 8%~12%，过期/空白 < 1%。"""
    dark = total = 0
    for yy in range(QR_Y + 6, QR_Y + QR_H - 6, 3):
        for xx in range(QR_X + 6, QR_X + QR_W - 6, 3):
            r, g, b = px(buf, xx, yy)
            total += 1
            if r < 90 and g < 90 and b < 90:
                dark += 1
    return bool(total) and dark / total > 0.035


def expired(buf):
    return any(is_red(px(buf, xx, EXPIRED_TEXT_Y)) for xx in range(472, 606, 4))


def on_qr_page(buf):
    return is_blue(px(buf, *PT_SPLIT_OK))


def main():
    if len(sys.argv) < 2:
        return 2
    out = sys.argv[1]
    pos = find_window()
    if not pos:
        return 3
    x, y = pos

    for _ in range(6):
        buf = grab(x, y)
        if len(buf) < WIN_W * WIN_H * 3:
            time.sleep(0.4)
            continue
        if is_blue(px(buf, *PT_DIALOG_OK)):
            click(x + PT_DIALOG_OK[0], y + PT_DIALOG_OK[1])
            time.sleep(0.9)
            continue
        if on_qr_page(buf):
            if qr_live(buf):
                break
            if expired(buf):
                click(x + PT_REFRESH[0], y + PT_REFRESH[1])
                time.sleep(1.1)
                continue
            # 正在加载 / 已扫码等待手机确认：保持原样，直接截图
            break
        if is_blue(px(buf, *PT_SAVED_OK)):
            click(x + PT_OTHER[0], y + PT_OTHER[1])
            time.sleep(0.9)
            continue
        time.sleep(0.4)

    # 最终确认仍在扫码页，避免把别的界面裁成二维码
    buf = grab(x, y)
    if len(buf) < WIN_W * WIN_H * 3 or not on_qr_page(buf):
        return 3

    p = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-f", "x11grab", "-draw_mouse", "0",
         "-video_size", f"{WIN_W}x{WIN_H}", "-i", f":1+{x},{y}", "-frames:v", "1",
         "-vf", f"crop={QR_W}:{QR_H}:{QR_X}:{QR_Y},"
                f"scale={OUT_SIZE}:{OUT_SIZE}:flags=lanczos",
         "-y", out],
        capture_output=True, timeout=20)
    return 0 if p.returncode == 0 else 3


if __name__ == "__main__":
    sys.exit(main())
