"""把 CLIP 与 NSFW 模型导出为 ONNX，供 DirectML（GPU）推理使用。

用法： python export_onnx.py
产物： models/clip_vision.onnx / clip_text.onnx / nsfw.onnx
"""

import os
import torch
import torch.nn as nn
from transformers import CLIPModel, CLIPProcessor, AutoModelForImageClassification, AutoImageProcessor

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "models")
os.makedirs(OUT, exist_ok=True)

CLIP_ID = os.environ.get("GUARD_CLIP_MODEL", "openai/clip-vit-base-patch32")
NSFW_ID = os.environ.get("GUARD_NSFW_MODEL", "Falconsai/nsfw_image_detection")


class ClipVision(nn.Module):
    def __init__(self, model: CLIPModel):
        super().__init__()
        self.m = model

    def forward(self, pixel_values):
        feats = self.m.get_image_features(pixel_values=pixel_values)
        if not torch.is_tensor(feats):
            feats = feats.pooler_output
        return feats / feats.norm(dim=-1, keepdim=True)


class ClipText(nn.Module):
    def __init__(self, model: CLIPModel):
        super().__init__()
        self.m = model

    def forward(self, input_ids, attention_mask):
        feats = self.m.get_text_features(input_ids=input_ids, attention_mask=attention_mask)
        if not torch.is_tensor(feats):
            feats = feats.pooler_output
        return feats / feats.norm(dim=-1, keepdim=True)


class Nsfw(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.m = model

    def forward(self, pixel_values):
        return self.m(pixel_values=pixel_values).logits


def main() -> None:
    print("loading CLIP ...")
    clip = CLIPModel.from_pretrained(CLIP_ID).eval()
    proc = CLIPProcessor.from_pretrained(CLIP_ID)
    dummy = proc(text=["hello"], images=torch.zeros(1, 3, 224, 224), return_tensors="pt", padding=True)

    print("exporting clip_vision ...")
    torch.onnx.export(
        ClipVision(clip).eval(),
        (dummy["pixel_values"],),
        os.path.join(OUT, "clip_vision.onnx"),
        input_names=["pixel_values"],
        output_names=["image_embeds"],
        dynamic_axes={"pixel_values": {0: "batch"}, "image_embeds": {0: "batch"}},
        opset_version=17,
        dynamo=False,
    )

    print("exporting clip_text ...")
    torch.onnx.export(
        ClipText(clip).eval(),
        (dummy["input_ids"], dummy["attention_mask"]),
        os.path.join(OUT, "clip_text.onnx"),
        input_names=["input_ids", "attention_mask"],
        output_names=["text_embeds"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "seq"},
            "attention_mask": {0: "batch", 1: "seq"},
            "text_embeds": {0: "batch"},
        },
        opset_version=17,
        dynamo=False,
    )

    print("loading NSFW ...")
    nsfw = AutoModelForImageClassification.from_pretrained(NSFW_ID).eval()
    nproc = AutoImageProcessor.from_pretrained(NSFW_ID)
    npix = nproc(images=torch.zeros(3, 224, 224), return_tensors="pt")["pixel_values"]

    print("exporting nsfw ...")
    torch.onnx.export(
        Nsfw(nsfw).eval(),
        (npix,),
        os.path.join(OUT, "nsfw.onnx"),
        input_names=["pixel_values"],
        output_names=["logits"],
        dynamic_axes={"pixel_values": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=17,
        dynamo=False,
    )

    for name in ("clip_vision.onnx", "clip_text.onnx", "nsfw.onnx"):
        path = os.path.join(OUT, name)
        print(f"  {name}: {os.path.getsize(path)/1024/1024:.1f} MB")


if __name__ == "__main__":
    main()
