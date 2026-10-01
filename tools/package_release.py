"""Verify and prepare local release files. Unsigned/dirty candidates require --allow-unsigned.

Does not sign, commit, tag, push, or publish anything. Python 3.9+ and Android build tools.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def properties(path):
    return dict(line.strip().split("=", 1) for line in path.read_text(encoding="utf-8-sig").splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def digest(stream):
    sha = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        sha.update(block)
    return sha.hexdigest()


def run(command, check=True):
    result = subprocess.run([str(part) for part in command], cwd=ROOT, text=True,
                            encoding="utf-8", errors="replace", capture_output=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result


def build_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and (ROOT / "local.properties").is_file():
        sdk = properties(ROOT / "local.properties").get("sdk.dir", "").replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        raise RuntimeError("Set ANDROID_HOME or sdk.dir in local.properties.")
    folders = sorted((Path(sdk) / "build-tools").glob("*"),
                     key=lambda path: tuple(int(value) for value in re.findall(r"\d+", path.name)), reverse=True)
    for folder in folders:
        if (folder / ("aapt2.exe" if os.name == "nt" else "aapt2")).is_file():
            return folder
    raise RuntimeError("Android SDK build tools are missing.")


def tool(folder, name):
    suffix = ".bat" if os.name == "nt" and name == "apksigner" else ".exe" if os.name == "nt" else ""
    return folder / (name + suffix)


def native_alignment(data, name):
    if data[:5] != b"\x7fELF\x02" or data[5] != 1:
        raise RuntimeError("Expected a little-endian 64-bit ELF: " + name)
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    alignments = [struct.unpack_from("<Q", data, offset + index * entry_size + 48)[0]
                  for index in range(count)
                  if struct.unpack_from("<I", data, offset + index * entry_size)[0] == 1]
    if not alignments or min(alignments) < 16_384:
        raise RuntimeError("Native LOAD segments are not 16 KB aligned: " + name)
    return min(alignments)


def copy_review_document(source, destination):
    """Keep copied notices readable without pretending the source tree is in the bundle."""
    retained = {
        "core/vision/src/main/assets/vision/notices": "licenses/vision",
        "core/translation/src/main/assets/translation/licenses": "licenses/translation",
    }

    def link(match):
        label, target = match.groups()
        if re.match(r"[a-z]+://|#", target):
            return match.group(0)
        path = (source.parent / target).resolve().relative_to(ROOT).as_posix()
        for prefix, folder in retained.items():
            if path == prefix or path.startswith(prefix + "/"):
                return "[{}]({})".format(label, folder + path[len(prefix):])
        return "{} (`{}` in the source repository)".format(label, path)

    document = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, source.read_text(encoding="utf-8-sig"))
    destination.write_text(document, encoding="utf-8")


def prepare(apk, output, allow_unsigned):
    version = properties(ROOT / "gradle/release.properties")
    name = version["versionName"]
    tools = build_tools()
    badging = run([tool(tools, "aapt2"), "dump", "badging", apk]).stdout
    expected = "package: name='com.lmreader' versionCode='{}' versionName='{}'".format(version["versionCode"], name)
    minimum_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'26'$", badging, re.MULTILINE)
    if expected not in badging or minimum_sdk is None or "targetSdkVersion:'36'" not in badging:
        raise RuntimeError("APK package/version/SDK does not match the release configuration.")
    signature = run([tool(tools, "apksigner"), "verify", "--verbose", "--print-certs", apk], check=False)
    signed = signature.returncode == 0
    signer = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signature.stdout)
    dirty = bool(run(["git", "status", "--porcelain"]).stdout.strip())
    if not signed and not allow_unsigned:
        raise RuntimeError("APK is unsigned. For a local candidate only, pass --allow-unsigned.")
    if dirty and not allow_unsigned:
        raise RuntimeError("Commit the complete release source before preparing publishable files.")
    run([tool(tools, "zipalign"), "-c", "-P", "16", "4", apk])
    from fetch_vision_models import HASHES
    alignments = {}
    dsp_libraries = []
    with zipfile.ZipFile(apk) as archive:
        broken = archive.testzip()
        if broken:
            raise RuntimeError("APK CRC failed: " + broken)
        abis = sorted({entry.split("/")[1] for entry in archive.namelist() if entry.startswith("lib/") and entry.endswith(".so")})
        if abis != ["arm64-v8a", "x86_64"]:
            raise RuntimeError("Unexpected APK ABIs: " + repr(abis))
        for abi in abis:
            for library in ("liblmreader_translation.so", "liblmreader_vision.so"):
                if "lib/{}/{}".format(abi, library) not in archive.namelist():
                    raise RuntimeError("Missing native engine: " + abi + "/" + library)
        for entry in archive.namelist():
            if entry.startswith("lib/") and entry.endswith(".so"):
                data = archive.read(entry)
                # QNN skeletons run on Hexagon DSP, not in Android's ARM64 linker.
                # Their ELF machine is 164 (Hexagon). Android stubs
                # and all other host libraries still require 64-bit/16 KB LOADs.
                if (re.fullmatch(r"lib/arm64-v8a/libQnn(?:Dsp|Htp)V\d+Skel\.so", entry)
                        and data[:6] == b"\x7fELF\x01\x01"
                        and struct.unpack_from("<H", data, 18)[0] == 164):
                    dsp_libraries.append(entry)
                else:
                    alignments[entry] = native_alignment(data, entry)
        for filename, expected_hash in HASHES.items():
            with archive.open("assets/vision/models/" + filename) as stream:
                if digest(stream) != expected_hash:
                    raise RuntimeError("APK model hash mismatch: " + filename)
        with archive.open("assets/i18n/zh_en.json") as stream:
            json.load(stream)
    output.mkdir(parents=True, exist_ok=True)
    destination = output / ("LM-Reader-v{}-64bit{}.apk".format(name, "" if signed else "-unsigned"))
    if apk.resolve() != destination.resolve():
        shutil.copyfile(apk, destination)
    with destination.open("rb") as stream:
        checksum = digest(stream)
    copy_review_document(ROOT / "docs/releases" / ("v" + name + ".md"), output / "RELEASE_NOTES.md")
    copy_review_document(ROOT / "THIRD_PARTY_NOTICES.md", output / "THIRD_PARTY_NOTICES.md")
    for source, target in [("core/vision/src/main/assets/vision/notices", "licenses/vision"),
                           ("core/translation/src/main/assets/translation/licenses", "licenses/translation")]:
        shutil.copytree(ROOT / source, output / target, dirs_exist_ok=True)
    metadata = {
        "versionName": name, "versionCode": int(version["versionCode"]), "tag": "v" + name,
        "applicationId": "com.lmreader", "minSdk": 26, "targetSdk": 36, "abis": abis,
        "apk": destination.name, "bytes": destination.stat().st_size, "sha256": checksum,
        "signed": signed, "signerCertificateSha256": signer.group(1) if signer else None,
        "sourceRevision": run(["git", "rev-parse", "HEAD"]).stdout.strip(),
        "sourceDirty": dirty, "nativeLoadAlignment": alignments, "hexagonDspLibraries": dsp_libraries,
        "publicDistributionReady": False,
        "pending": ["Seg weights redistribution evidence", "project license selection",
                    "install/upgrade and ARM validation" if signed else "signing, install/upgrade and ARM validation"],
    }
    (output / "release-metadata.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    entries = [destination, output / "RELEASE_NOTES.md", output / "THIRD_PARTY_NOTICES.md", output / "release-metadata.json"]
    entries += sorted(path for path in (output / "licenses").rglob("*") if path.is_file())
    lines = []
    for path in entries:
        with path.open("rb") as stream:
            lines.append(digest(stream) + "  " + path.relative_to(output).as_posix())
    (output / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"folder": str(output), "apk": destination.name, "signed": signed,
                      "sourceDirty": dirty, "bytes": destination.stat().st_size, "sha256": checksum}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--allow-unsigned", action="store_true", help="Allow local unsigned/dirty candidates; never publishes")
    args = parser.parse_args()
    release = properties(ROOT / "gradle/release.properties")["versionName"]
    try:
        prepare(args.apk.resolve(), (args.output or ROOT / ".scratch" / ("release-v" + release)).resolve(), args.allow_unsigned)
    except (OSError, ValueError, RuntimeError, KeyError, zipfile.BadZipFile) as failure:
        parser.exit(1, "Release preparation failed: " + str(failure) + "\n")
