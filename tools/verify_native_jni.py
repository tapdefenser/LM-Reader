"""Check JNI-only constructors in the actual APK, including R8 release builds."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

# These are resolved by native code, which R8 cannot inspect. Missing them
# causes process aborts rather than a catchable Kotlin/Java exception.
REQUIRED = {
    "ai.onnxruntime.NodeInfo": ("java.lang.String", "ai.onnxruntime.ValueInfo"),
    "com.google.ai.edge.litert.LiteRtException": ("int", "java.lang.String"),
    "ca.mpreg.imagedecoder.ImageDecoder": ("long", "int", "int", "boolean", "java.lang.String"),
    "ca.mpreg.imagedecoder.ImageDecoder$DecodeResult": ("long", "java.nio.ByteBuffer", "int", "int", "int", "int", "int", "int", "int"),
    "ca.mpreg.imagedecoder.ImageDecoder$DecodeException": ("java.lang.String",),
}


def verify(apk, dexdump):
    found = set()
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="lmreader-jni-") as folder:
        for name in archive.namelist():
            if not re.fullmatch(r"classes(?:\d+)?\.dex", name):
                continue
            dex = Path(folder) / name
            dex.write_bytes(archive.read(name))
            xml = Path(folder) / "api.xml"
            with xml.open("wb") as output:
                subprocess.run([str(dexdump), "-l", "xml", str(dex)], stdout=output,
                               stderr=subprocess.PIPE, check=True)
            package = ""
            for event, element in ET.iterparse(xml, events=("start", "end")):
                if event == "start" and element.tag == "package":
                    package = element.get("name", "")
                elif event == "end" and element.tag == "class":
                    class_name = package + "." + element.get("name", "")
                    if class_name in REQUIRED:
                        for constructor in element.findall("constructor"):
                            parameters = tuple(p.get("type") for p in constructor.findall("parameter"))
                            if constructor.get("static") == "false" and parameters == REQUIRED[class_name]:
                                found.add(class_name)
                    element.clear()
    missing = sorted(set(REQUIRED) - found)
    if missing:
        raise RuntimeError("APK is missing JNI constructors; check engine R8 rules: " + ", ".join(missing))
    return sorted(found)


if __name__ == "__main__":
    from package_release import build_tools, tool
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    print("Verified engine JNI constructors: " + ", ".join(verify(args.apk, tool(build_tools(), "dexdump"))))
