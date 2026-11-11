# 图片安全预筛服务（image-guard）

在图片送云端视觉模型**之前**，本地先判一次是否敏感（证件、银行卡、私密照）。命中就直接丢弃，原图不出本机。

## 组成

| 层 | 模型 | 作用 |
|---|---|---|
| 1 | `Falconsai/nsfw_image_detection` | 裸露/色情，阈值 0.85 |
| 2 | `openai/clip-vit-base-patch32` | zero-shot：身份证 / 银行卡 / 护照 / 私密照，阈值 0.90 |
| 3 | `rapidocr-onnxruntime`（PP-OCR） | 识别「身份证 / 银行卡 / 卡号 / 有效期」等关键词，外加卡片长宽比启发式 |

任一命中即 `sensitive: true`。

## 运行

```bat
E:\tools\python311\python.exe -m uvicorn app:app --host 127.0.0.1 --port 8081
```

首次请求会加载模型（约 10-20 秒），之后常驻内存。

## 接口

```
GET  /health   → {"ok":true,"models":{"nsfw":true,"clip":true,"ocr":true}}
POST /check    → multipart file 或 JSON {"image_base64":"..."}
               → {"sensitive":false,"reason":"clean","score":0.0,"detail":{...}}
```

模型不可用时返回 **503**，Java 端按 `fail-closed` 处理（不解析图片）。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `GUARD_MODEL_DIR` | `E:\tools\models\hf` | 模型缓存目录 |
| `GUARD_NSFW_THRESHOLD` | 0.85 | NSFW 阈值 |
| `GUARD_CLIP_THRESHOLD` | 0.90 | CLIP 文档阈值 |
| `GUARD_OCR_ENABLED` | 1 | 是否启用 OCR 层 |
| `HF_ENDPOINT` | hf-mirror.com | 模型下载镜像 |
