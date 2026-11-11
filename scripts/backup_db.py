"""SQLite 热备份脚本（WAL 模式下可安全在服务运行时执行）。

用法：
    python scripts/backup_db.py                # 备份数据库
    python scripts/backup_db.py --with-env     # 额外备份 .env（含密钥，注意保管）
    python scripts/backup_db.py --db X --dest Y --keep 7

产物：<dest>/qqbot-YYYYmmdd-HHMMSS.db
"""

from __future__ import annotations

import argparse
import os
import shutil
import sqlite3
import sys
from datetime import datetime, timedelta
from pathlib import Path

DEFAULT_DB = r"E:\qqbot\data\qqbot.db"
DEFAULT_DEST = r"E:\backup\qqbot"
DEFAULT_KEEP_DAYS = 14


def log(msg: str) -> None:
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}")


def backup(db_path: Path, dest_dir: Path) -> Path:
    if not db_path.exists():
        raise FileNotFoundError(f"数据库不存在: {db_path}")

    dest_dir.mkdir(parents=True, exist_ok=True)
    target = dest_dir / f"qqbot-{datetime.now():%Y%m%d-%H%M%S}.db"

    src = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        dst = sqlite3.connect(str(target))
        try:
            src.backup(dst)  # 在线热备份，不需要停服务
        finally:
            dst.close()
    finally:
        src.close()

    # 校验：能打开且完整性检查通过
    check = sqlite3.connect(str(target))
    try:
        result = check.execute("PRAGMA integrity_check").fetchone()[0]
        count = check.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
    finally:
        check.close()
    if result != "ok":
        target.unlink(missing_ok=True)
        raise RuntimeError(f"备份校验失败: {result}")

    log(f"备份完成: {target}（{target.stat().st_size / 1024:.0f} KB，messages={count}）")
    return target


def prune(dest_dir: Path, keep_days: int) -> int:
    cutoff = datetime.now() - timedelta(days=keep_days)
    removed = 0
    for f in sorted(dest_dir.glob("qqbot-*.db")):
        try:
            ts = datetime.strptime(f.stem.replace("qqbot-", ""), "%Y%m%d-%H%M%S")
        except ValueError:
            continue
        if ts < cutoff:
            f.unlink(missing_ok=True)
            removed += 1
    if removed:
        log(f"清理过期备份 {removed} 个（保留 {keep_days} 天）")
    return removed


def backup_env(dest_dir: Path) -> None:
    env = Path(r"E:\qqbot\.env")
    if not env.exists():
        log(".env 不存在，跳过")
        return
    target = dest_dir / f".env-{datetime.now():%Y%m%d-%H%M%S}.bak"
    shutil.copy2(env, target)
    log(f".env 已备份: {target}（含密钥，注意保管）")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--dest", default=DEFAULT_DEST)
    parser.add_argument("--keep", type=int, default=DEFAULT_KEEP_DAYS)
    parser.add_argument("--with-env", action="store_true")
    parser.add_argument("--mirror", default="", help="异地目录，备份完成后复制一份过去")
    args = parser.parse_args()

    db_path = Path(args.db)
    dest_dir = Path(args.dest)
    try:
        latest = backup(db_path, dest_dir)
        if args.with_env:
            backup_env(dest_dir)
        if args.mirror:
            mirror_dir = Path(args.mirror)
            mirror_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(latest, mirror_dir / latest.name)
            log(f"已复制到异地: {mirror_dir / latest.name}")
        prune(dest_dir, args.keep)
        total = sum(f.stat().st_size for f in dest_dir.glob("qqbot-*.db"))
        log(f"当前共有 {len(list(dest_dir.glob('qqbot-*.db')))} 份备份，合计 {total / 1024 / 1024:.1f} MB")
        return 0
    except Exception as e:  # noqa: BLE001
        log(f"备份失败: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
