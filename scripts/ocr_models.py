"""Fetches the official PaddlePaddle PP-OCRv5 mobile models (text detection + East Slavic
recognition), converts them to ONNX, checks them on a rendered Ukrainian test image and writes
SHA256SUMS. Runs in GitHub Actions (ocr-models.yml); the result is reviewed before it is committed.
"""
import hashlib, json, os, pathlib, subprocess, sys, yaml

OUT = pathlib.Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
from huggingface_hub import HfApi, snapshot_download

api = HfApi()
models = {"det": "PaddlePaddle/PP-OCRv5_mobile_det", "rec": "PaddlePaddle/eslav_PP-OCRv5_mobile_rec"}
info = {}
for name, repo in models.items():
    rev = api.model_info(repo).sha
    path = snapshot_download(repo, revision=rev)
    info[name] = {"repo": repo, "revision": rev, "files": sorted(os.listdir(path))}
    onnx = OUT / f"{name}.onnx"
    subprocess.run(["paddle2onnx", "--model_dir", path, "--model_filename", "inference.json",
                    "--params_filename", "inference.pdiparams", "--save_file", str(onnx),
                    "--opset_version", "14"], check=True)
    cfg = yaml.safe_load(open(os.path.join(path, "inference.yml")))
    info[name]["preprocess"] = cfg.get("PreProcess")
    if name == "rec":
        chars = cfg["PostProcess"]["character_dict"]
        (OUT / "rec_dict.txt").write_text("\n".join(chars) + "\n", encoding="utf-8")
        info[name]["dict_size"] = len(chars)

# A quick end-to-end check with onnxruntime on a rendered line of Ukrainian text
import numpy as np, onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 40)
text = "Привіт, світе! Їжак 2026"
img = Image.new("RGB", (900, 80), "white"); ImageDraw.Draw(img).text((10, 15), text, fill="black", font=font)
h = 48; w = int(img.width * h / img.height)
x = np.asarray(img.resize((w, h)), dtype=np.float32) / 255.0
x = ((x - 0.5) / 0.5).transpose(2, 0, 1)[None]
sess = ort.InferenceSession(str(OUT / "rec.onnx"))
probs = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]
chars = (OUT / "rec_dict.txt").read_text(encoding="utf-8").split("\n")[:-1]
labels = [" "] if False else []
idx = probs.argmax(axis=1); out = []; prev = -1
for i in idx:
    if i != prev and i != 0:
        out.append(chars[i - 1] if i - 1 < len(chars) else " ")
    prev = i
info["check"] = {"input": text, "recognized": "".join(out),
                 "det_inputs": [str(i.shape) for i in ort.InferenceSession(str(OUT / "det.onnx")).get_inputs()],
                 "rec_input": str(sess.get_inputs()[0].shape), "rec_output": str(probs.shape)}
sums = []
for f in sorted(OUT.iterdir()):
    if f.is_file():
        sums.append(f"{hashlib.sha256(f.read_bytes()).hexdigest()}  {f.name}  {f.stat().st_size}")
(OUT / "SHA256SUMS").write_text("\n".join(sums) + "\n")
(OUT / "info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1, default=str))
print(json.dumps(info["check"], ensure_ascii=False))
