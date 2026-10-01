"""Prepare pinned local vision assets. Python 3.9+ and PyYAML are required.

The Seg weights are extracted from the author's verified v3.5.5 distribution;
its model license is not inferred from the repository's MIT code license.
This script prepares the current self-use/test build, not a redistribution grant.
"""
from pathlib import Path
from urllib.request import urlopen, Request
import argparse
import hashlib
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "core/vision/src/main/assets/vision/models"
CACHE = ROOT / ".scratch/local-models"
HASHES = {
    "seg.tflite": "d030134cb00fb178a6433a8f2cfe5680c0bf34365ec64e0f6e5d0f28ec252c76",
    "det.onnx": "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
    "rec.onnx": "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
    "ko.onnx": "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08",
    "rec.txt": "769e7fa79bb297b5f18d8dbd149e364a45bc61f2b3f574e5ea836f0b261c23a6",
    "ko.txt": "2193f5dd0c62a4f268902b5d96dadfec3908d299afa9a6e5cad2c6a96c772828",
}
REC = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/b8f84f0b80c529de40b4fbb3544b84fa7233a513/"
DET = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62/"
KO = "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/5c6f574b8e2230adf4287b33e736d71b9fabd28e/"
APK = "https://github.com/jedzqer/manga-translator-android/releases/download/v3.5.5/app-release.apk"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(part)
    return value.hexdigest()


def obtain(name, url, expected):
    path = CACHE / name
    if path.is_file() and digest(path) == expected:
        return path
    temporary = path.with_suffix(path.suffix + ".part")
    try:
        print("Downloading", name, flush=True)
        with urlopen(Request(url, headers={"User-Agent": "LM-Reader-model-preparation"}), timeout=120) as response:
            with temporary.open("wb") as stream:
                shutil.copyfileobj(response, stream, 1024 * 1024)
        if digest(temporary) != expected:
            raise ValueError("SHA-256 mismatch: " + name)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return path


def prepare():
    ASSETS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    for target, cached, url in [("det.onnx", "ppocr-det.onnx", DET), ("rec.onnx", "ppocr-rec.onnx", REC), ("ko.onnx", "ppocr-ko.onnx", KO)]:
        shutil.copyfile(obtain(cached, url + "inference.onnx", HASHES[target]), ASSETS / target)
    apk = obtain("upstream-v3.5.5.apk", APK, "bd5cd31ecf78f8bc642e8c5e850551629d98d2efe592e2f7fde8d061d91245be")
    with zipfile.ZipFile(apk) as archive:
        (ASSETS / "seg.tflite").write_bytes(archive.read("assets/models/detection/mixed-dual-s-e5_float16.tflite"))
    try:
        import yaml
    except ImportError as failure:
        raise RuntimeError("Install PyYAML first: python -m pip install PyYAML") from failure
    for name, url, checksum in [
        ("rec", REC, "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1"),
        ("ko", KO, "f757fa1c40e99edcf27e9cce879b93eb2a51fa46f5ef39095689b8c37dd75998")]:
        config = obtain("ppocr-" + name + ".yml", url + "inference.yml", checksum)
        chars = yaml.safe_load(config.read_text(encoding="utf-8"))["PostProcess"]["character_dict"]
        if not all(isinstance(char, str) and char and "\n" not in char for char in chars):
            raise ValueError("Invalid character dictionary")
        # write_bytes 保证 Windows 下也使用 LF；字典按模型顺序保存，不自行排序。
        (ASSETS / (name + ".txt")).write_bytes(("\n".join(chars) + "\n").encode("utf-8"))
    verify()


def verify():
    for name, checksum in HASHES.items():
        path = ASSETS / name
        if not path.is_file() or digest(path) != checksum:
            raise ValueError("Missing or invalid model asset: " + name)
        print(name, path.stat().st_size, checksum)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Check local assets without network access")
    args = parser.parse_args()
    verify() if args.verify else prepare()
