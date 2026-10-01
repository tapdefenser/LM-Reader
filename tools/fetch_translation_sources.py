"""Prepare the pinned Bergamot CPU source tree. No model weights are bundled.

Source archives, hashes and licenses are fixed in translation_sources.lock.json.
Run this explicitly before building; CMake does not clone or update repositories.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import posixpath
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = Path(__file__).with_name('translation_sources.lock.json')
DEST = ROOT / 'core/translation/src/main/cpp/third_party'


def apply_compatibility_patch(entries):
    # Marian's MIT-licensed archive build assumes .git exists. Replace only the
    # revision-generation block; inference sources are unmodified.
    source = DEST / 'bergamot/3rd_party/marian-dev/src/CMakeLists.txt'
    text = source.read_text(encoding='utf-8')
    if '# LM-Reader pinned archive revision' not in text:
        start = text.index('# Generate git_revision.h')
        end = text.index('# make sure all local dependencies', start)
        revision = next(e['revision'] for e in entries if e['path'] == 'bergamot/3rd_party/marian-dev')
        replacement = ('# LM-Reader pinned archive revision (MIT; see tools/fetch_translation_sources.py)\n'
                       'file(WRITE ${CMAKE_CURRENT_SOURCE_DIR}/common/git_revision.h '
                       '"#define GIT_REVISION \\"' + revision + '\\"\\n")\n')
        source.write_text(text[:start] + replacement + text[end:], encoding='utf-8')
    # Android's filesystem is UTF-8. iconv/glob were introduced only at API 28;
    # this inference-only build retains API 26 and rejects these unused operations.
    pathie = DEST / 'bergamot/3rd_party/marian-dev/src/3rd_party/pathie-cpp/src'
    for name, signature, replacement in [
        ('pathie.cpp', 'std::string Pathie::convert_encodings(',
         '\n#if defined(__ANDROID__)\n'
         '  if (!strcmp(from_encoding, to_encoding)) return string;\n'
         '  throw std::runtime_error("Encoding conversion is unavailable on Android");\n'
         '#else\n'),
        ('path.cpp', 'std::vector<Path> Path::glob(',
         '\n#if defined(__ANDROID__)\n'
         '  throw std::runtime_error("Glob is unavailable in the Android inference build");\n'
         '#else\n'),
    ]:
        source = pathie / name
        text = source.read_text(encoding='utf-8')
        if '// LM-Reader Android API 26 compatibility' in text:
            continue
        start = text.index(signature)
        opening = text.index('{', start)
        depth = 1
        closing = opening + 1
        while depth:
            if text[closing] == '{': depth += 1
            elif text[closing] == '}': depth -= 1
            closing += 1
        text = (text[:opening + 1] + replacement + text[opening + 1:closing - 1]
                + '\n#endif\n' + text[closing - 1:])
        source.write_text('// LM-Reader Android API 26 compatibility (MIT); see the source preparation script.\n'
                          '#include <stdexcept>\n' + text, encoding='utf-8')


def prepare(entry):
    target = DEST / entry['path']
    marker = target / '.lmreader-source.json'
    if marker.exists() and json.loads(marker.read_text()) == entry:
        return entry['path'] + ' ready'
    data = urllib.request.urlopen(entry['url'], timeout=120).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != entry['sha256']:
        raise ValueError('Source archive checksum mismatch: ' + entry['path'])
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
        for member in archive:
            parts = Path(member.name).parts[1:]
            if not parts:
                continue
            destination = target.joinpath(*parts).resolve()
            if target.resolve() not in destination.parents:
                raise ValueError('Archive path escapes source folder')
            if member.isdir():
                destination.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.extractfile(member).read())
            elif member.issym() or member.islnk():
                # Materialize an in-archive file link; never create filesystem links.
                linked = member.linkname if member.islnk() else posixpath.normpath(
                    posixpath.join(posixpath.dirname(member.name), member.linkname))
                if linked.split('/')[0] != member.name.split('/')[0]:
                    raise ValueError('Archive link escapes source folder')
                linked_member = archive.getmember(linked)
                if not linked_member.isfile():
                    raise ValueError('Unsupported archive link target')
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.extractfile(linked_member).read())
            else:
                raise ValueError('Unsupported archive entry: ' + member.name)
    marker.write_text(json.dumps(entry, indent=2), encoding='utf-8')
    return entry['path'] + ' prepared'


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify', action='store_true', help='Check prepared source markers without downloading')
    args = parser.parse_args()
    entries = json.loads(LOCK.read_text(encoding='utf-8'))
    if args.verify:
        for entry in entries:
            marker = DEST / entry['path'] / '.lmreader-source.json'
            if not marker.exists() or json.loads(marker.read_text()) != entry:
                raise SystemExit('Missing source: ' + entry['path'])
        print('All pinned translation sources are prepared')
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            for result in executor.map(prepare, entries):
                print(result, flush=True)
        apply_compatibility_patch(entries)
