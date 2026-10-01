"""Refresh the offline English catalog for legacy Kotlin UI strings.

Requires the optional argostranslate Python package and its zh -> en model.
Run from the repository root; review the generated translations before shipping.
"""

from __future__ import annotations

import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = (ROOT / "app/src/main/java/com/lmreader", ROOT / "core")
OUTPUT = ROOT / "app/src/main/assets/i18n/zh_en.json"
OVERRIDES = ROOT / "tools/ui_translation_overrides.json"
HAN = re.compile(r"[\u3400-\u9fff]")
INTERPOLATION = re.compile(r"\$\{[^{}]+\}|\$[A-Za-z_][A-Za-z_0-9]*")


def literals(source: str) -> list[str]:
    result: list[str] = []
    position = 0
    while position < len(source):
        if source.startswith("//", position):
            end = source.find("\n", position)
            position = len(source) if end < 0 else end + 1
            continue
        if source.startswith("/*", position):
            position += 2
            depth = 1
            while position < len(source) and depth:
                if source.startswith("/*", position):
                    depth += 1
                    position += 2
                elif source.startswith("*/", position):
                    depth -= 1
                    position += 2
                else:
                    position += 1
            continue
        if source.startswith('"""', position):
            end = source.find('"""', position + 3)
            if end < 0:
                break
            result.append(source[position + 3 : end])
            position = end + 3
            continue
        if source[position] == '"':
            start = position + 1
            position = start
            while position < len(source):
                if source[position] == "\\":
                    position += 2
                elif source[position] == '"':
                    break
                else:
                    position += 1
            result.append(source[start:position])
            position += 1
            continue
        if source[position] == "'":
            position += 1
            while position < len(source):
                if source[position] == "\\":
                    position += 2
                elif source[position] == "'":
                    position += 1
                    break
                else:
                    position += 1
            continue
        position += 1
    return result


def template(value: str) -> tuple[str, str]:
    tokens: list[str] = []

    def replace(match: re.Match[str]) -> str:
        token = f"<{len(tokens)}>"
        tokens.append(token)
        return token

    marked = INTERPOLATION.sub(replace, value)
    source = marked
    for index, token in enumerate(tokens):
        source = source.replace(token, f"__VAR{index}__")
    source = source.replace(r"\n", "\n").replace(r"\t", "\t").replace(r'\"', '"')
    return source, marked


def main() -> None:
    import argostranslate.translate

    current = json.loads(OUTPUT.read_text(encoding="utf-8")) if OUTPUT.exists() else {}
    candidates: dict[str, str] = {}
    files = (file for source_root in SOURCES for file in source_root.rglob("*.kt")
             if source_root.name != "core" or "src/main" in file.as_posix())
    for file in files:
        if file.parts[-2] == "i18n":
            continue
        for value in literals(file.read_text(encoding="utf-8")):
            if HAN.search(value):
                key, marked = template(value)
                if len(key) <= 700 and key.strip():
                    candidates.setdefault(key, marked)

    missing = [(key, marked) for key, marked in candidates.items() if key not in current]
    print(f"Found {len(candidates)} Chinese literals, translating {len(missing)} new entries", flush=True)
    for number, (key, marked) in enumerate(missing, 1):
        try:
            english = argostranslate.translate.translate(marked, "zh", "en").strip()
        except Exception as failure:
            print(f"Could not translate {key!r}: {failure}", flush=True)
            continue
        english = re.sub(r"<\s*(\d+)\s*>", lambda match: f"__VAR{match.group(1)}__", english)
        if "__VAR" in key and not all(token in english for token in re.findall(r"__VAR\d+__", key)):
            chunks = re.split(r"(<\d+>)", marked)
            english = "".join(
                f"__VAR{chunk[1:-1]}__" if re.fullmatch(r"<\d+>", chunk)
                else argostranslate.translate.translate(chunk, "zh", "en")
                for chunk in chunks
            ).strip()
        current[key] = english
        if number % 50 == 0:
            print(f"Translated {number}/{len(missing)}", flush=True)
            OUTPUT.write_text(json.dumps(current, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    current.update(json.loads(OVERRIDES.read_text(encoding="utf-8")))
    OUTPUT.write_text(json.dumps(current, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Catalog entries: {len(current)}", flush=True)


if __name__ == "__main__":
    main()
