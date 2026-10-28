"""
图片安全预筛服务（本地 sidecar，GPU 版）。

推理走 ONNX Runtime + DirectML（RTX 5070 通过 D3D12 使用 GPU，不需要 CUDA）。
OCR 仍用 rapidocr（CPU，模型很小，几十毫秒）。

启动： uvicorn app:app --host 127.0.0.1 --port 8081
接口：
  GET  /health     -> 模型与 provider 状态
  POST /check      -> multipart file
  POST /check_json -> {"image_base64": "..."}
"""

import base64
import io
import os
import platform
import time
from typing import Any

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, File, UploadFile
from fastapi.responses import JSONResponse
from PIL import Image
from pydantic import BaseModel

_WIN_HF_DEFAULT = r"E:\tools\models\hf"
_GUARD_HF_LOCAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hf")
MODEL_DIR = os.environ.get("GUARD_MODEL_DIR") or (
    _WIN_HF_DEFAULT if os.path.isdir(_WIN_HF_DEFAULT) else _GUARD_HF_LOCAL
)
ONNX_DIR = os.environ.get("GUARD_ONNX_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "models"))
os.environ.setdefault("HF_HOME", MODEL_DIR)
os.environ.setdefault("HF_HUB_OFFLINE", "1")

NSFW_MODEL = os.environ.get("GUARD_NSFW_MODEL", "Falconsai/nsfw_image_detection")
CLIP_MODEL = os.environ.get("GUARD_CLIP_MODEL", "openai/clip-vit-base-patch32")

NSFW_THRESHOLD = float(os.environ.get("GUARD_NSFW_THRESHOLD", "0.85"))
CLIP_THRESHOLD = float(os.environ.get("GUARD_CLIP_THRESHOLD", "0.90"))
OCR_ENABLED = os.environ.get("GUARD_OCR_ENABLED", "1") == "1"
USE_GPU = os.environ.get("GUARD_USE_GPU", "1") == "1"

def _resolve_providers() -> list:
    """按平台选择推理后端：Windows 用 DirectML，Linux 用 CUDA（有则用）或 CPU。"""
    if not USE_GPU:
        return ["CPUExecutionProvider"]
    available = set(ort.get_available_providers())
    candidates = []
    if platform.system() == "Windows":
        candidates.append("DmlExecutionProvider")
    candidates.extend(["CUDAExecutionProvider", "CPUExecutionProvider"])
    chosen = [p for p in candidates if p in available]
    return chosen or ["CPUExecutionProvider"]


PROVIDERS = _resolve_providers()

DOC_KEYWORDS = [
    "身份证", "居民身份证", "公民身份号码", "银行卡", "信用卡", "储蓄卡", "有效期",
    "卡号", "护照", "驾驶证", "行驶证", "社会保障卡", "医保卡", "证件号码",
]
SENSITIVE_PROMPTS = [
    "a photo of an ID card",
    "a photo of a bank card or credit card",
    "a photo of a passport",
    "a photo of a driver license",
    "a photo of a naked person",
    "a photo of a sexual or explicit scene",
]
BENIGN_PROMPTS = [
    "a photo of a cat or dog",
    "a photo of food",
    "a photo of a landscape",
    "a screenshot of a chat conversation",
    "a photo of a person wearing clothes",
    "a photo of an everyday object",
]

app = FastAPI(title="image-guard")
_state: dict[str, Any] = {"ready": False, "errors": [], "providers": {}, "ocr": None, "nsfw_labels": {}}


def _session(path: str, name: str):
    if not os.path.exists(path):
        raise FileNotFoundError(f"{name} 模型不存在: {path}（先跑 export_onnx.py）")
    sess = ort.InferenceSession(path, providers=PROVIDERS)
    _state["providers"][name] = sess.get_providers()[0]
    return sess


def _softmax(x: np.ndarray) -> np.ndarray:
    e = np.exp(x - np.max(x))
    return e / e.sum()


def _lazy_load() -> None:
    if _state["ready"]:
        return
    try:
        from transformers import AutoConfig, AutoImageProcessor, CLIPProcessor

        _state["clip_proc"] = CLIPProcessor.from_pretrained(CLIP_MODEL)
        _state["nsfw_proc"] = AutoImageProcessor.from_pretrained(NSFW_MODEL)
        cfg = AutoConfig.from_pretrained(NSFW_MODEL)
        _state["nsfw_labels"] = {int(k): v for k, v in cfg.id2label.items()}

        _state["clip_vision"] = _session(os.path.join(ONNX_DIR, "clip_vision.onnx"), "clip_vision")
        _state["clip_text"] = _session(os.path.join(ONNX_DIR, "clip_text.onnx"), "clip_text")
        _state["nsfw"] = _session(os.path.join(ONNX_DIR, "nsfw.onnx"), "nsfw")

        # 文本提示词只算一次
        _state["clip_text_features"] = _text_features(SENSITIVE_PROMPTS + BENIGN_PROMPTS)
        _state["prompts"] = SENSITIVE_PROMPTS + BENIGN_PROMPTS

        if OCR_ENABLED:
            try:
                from rapidocr_onnxruntime import RapidOCR

                _state["ocr"] = RapidOCR()
            except Exception as e:  # noqa: BLE001
                _state["errors"].append(f"ocr: {e}")

        _state["ready"] = True
    except Exception as e:  # noqa: BLE001
        _state["errors"].append(str(e))


def _text_features(prompts: list[str]) -> np.ndarray:
    inputs = _state["clip_proc"](text=prompts, return_tensors="np", padding=True)
    out = _state["clip_text"].run(
        ["text_embeds"],
        {
            "input_ids": inputs["input_ids"].astype(np.int64),
            "attention_mask": inputs["attention_mask"].astype(np.int64),
        },
    )[0]
    return out / np.linalg.norm(out, axis=-1, keepdims=True)


def _decode(raw: bytes) -> Image.Image:
    img = Image.open(io.BytesIO(raw))
    img.load()
    return img.convert("RGB")


def _check_nsfw(img: Image.Image):
    if not _state.get("ready"):
        return False, 0.0, {}
    t0 = time.time()
    pix = _state["nsfw_proc"](images=img, return_tensors="np")["pixel_values"].astype(np.float32)
    logits = _state["nsfw"].run(["logits"], {"pixel_values": pix})[0][0]
    probs = _softmax(logits)
    labels = _state["nsfw_labels"]
    scores = {labels.get(i, str(i)): float(probs[i]) for i in range(len(probs))}
    nsfw = max([v for k, v in scores.items() if k.lower() in ("nsfw", "porn", "explicit")] or [0.0])
    return nsfw >= NSFW_THRESHOLD, nsfw, {**scores, "ms": round((time.time() - t0) * 1000)}


def _check_clip(img: Image.Image):
    if not _state.get("ready"):
        return False, 0.0, {}
    t0 = time.time()
    pix = _state["clip_proc"](images=img, return_tensors="np")["pixel_values"].astype(np.float32)
    emb = _state["clip_vision"].run(["image_embeds"], {"pixel_values": pix})[0][0]
    emb = emb / np.linalg.norm(emb)
    sims = _state["clip_text_features"] @ emb
    probs = _softmax(sims * 100.0)
    prompts = _state["prompts"]
    sens = {prompts[i]: float(probs[i]) for i in range(len(SENSITIVE_PROMPTS))}
    top = max(sens, key=sens.get)
    return sens[top] >= CLIP_THRESHOLD, sens[top], {"top_prompt": top, "ms": round((time.time() - t0) * 1000)}


def _check_ocr(img: Image.Image):
    if _state.get("ocr") is None:
        return False, 0.0, {"skipped": "ocr unavailable"}
    t0 = time.time()
    arr = np.array(img)
    result, _ = _state["ocr"](arr)
    texts = [line[1] for line in result] if result else []
    joined = " ".join(texts)
    hits = [k for k in DOC_KEYWORDS if k in joined]
    w, h = img.size
    ratio = w / h if h else 0
    card_ratio = 1.35 < ratio < 1.75 or 0.57 < ratio < 0.75
    detail = {"text_count": len(texts), "ms": round((time.time() - t0) * 1000)}
    if hits:
        detail["text_hits"] = hits
        detail["text"] = joined[:200]
        return True, 1.0, detail
    if card_ratio and len(texts) >= 3:
        detail["card_ratio"] = round(ratio, 3)
        return True, 0.6, detail
    return False, 0.0, detail


@app.get("/health")
def health() -> JSONResponse:
    _lazy_load()
    models = {k: k in _state["providers"] for k in ("clip_vision", "clip_text", "nsfw")}
    models["ocr"] = _state["ocr"] is not None
    return JSONResponse(
        {
            "ok": _state["ready"],
            "models": models,
            "providers": _state["providers"],
            "gpu": any(p == "DmlExecutionProvider" for p in _state["providers"].values()),
            "thresholds": {"nsfw": NSFW_THRESHOLD, "clip": CLIP_THRESHOLD},
            "errors": _state["errors"],
        },
        status_code=200 if _state["ready"] else 503,
    )


def _run(raw: bytes) -> JSONResponse:
    _lazy_load()
    if not _state["ready"]:
        return JSONResponse({"sensitive": True, "reason": "models_unavailable", "score": 1.0}, status_code=503)

    img = _decode(raw)
    detail: dict[str, Any] = {"size": img.size}

    nsfw_hit, nsfw_score, nsfw_detail = _check_nsfw(img)
    detail["nsfw"] = {"score": round(nsfw_score, 4), **nsfw_detail}
    if nsfw_hit:
        return JSONResponse({"sensitive": True, "reason": "nsfw", "score": nsfw_score, "detail": detail})

    clip_hit, clip_score, clip_detail = _check_clip(img)
    detail["clip"] = {"score": round(clip_score, 4), **clip_detail}
    if clip_hit:
        return JSONResponse({"sensitive": True, "reason": "clip_document", "score": clip_score, "detail": detail})

    ocr_hit, ocr_score, ocr_detail = _check_ocr(img)
    detail["ocr"] = ocr_detail
    if ocr_hit:
        return JSONResponse({"sensitive": True, "reason": "ocr_document", "score": ocr_score, "detail": detail})

    return JSONResponse({"sensitive": False, "reason": "clean", "score": 0.0, "detail": detail})


class _ImagePayload(BaseModel):
    image_base64: str


@app.post("/check_json")
def check_json(payload: _ImagePayload) -> JSONResponse:
    raw = base64.b64decode(payload.image_base64.split(",")[-1])
    return _run(raw)


@app.post("/check")
async def check(file: UploadFile | None = File(default=None)) -> JSONResponse:
    if file is None:
        return JSONResponse({"error": "no file"}, status_code=400)
    return _run(await file.read())
