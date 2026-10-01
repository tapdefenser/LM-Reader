"""Download verified public NMT packages for a static HTTP directory and ZIP import.

This exports files locally; it does not upload or start a server. Requires requests.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import zipfile
import requests
import re

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'core/translation/src/main/assets/translation/catalog.json'


def export(pack, catalog, base, source):
    if not re.fullmatch(r'[A-Za-z0-9-]{1,100}', pack['id']) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,99}', pack['version']) or '..' in pack['version']:
        raise ValueError('Unsafe package identifier/version')
    folder = base / pack['id'] / pack['version']
    folder.mkdir(parents=True, exist_ok=True)
    raw = {}
    for asset in pack['files']:
        if not re.fullmatch(r'[A-Za-z0-9._-]{1,150}', asset['name']) or asset['name'] in ('.', '..'):
            raise ValueError('Unsafe model filename')
        path = folder / (asset['name'] + '.gz')
        if not path.exists():
            temp = path.with_suffix('.gz.part')
            address = asset.get('url')
            if not address:
                relative = asset['officialPath'] if source == 'mozilla' else (catalog['mirrorRepository'] + '/resolve/' + catalog['mirrorRevision'] + '/' + asset['mirrorPath'])
                host = catalog['officialBaseUrl'] if source == 'mozilla' else ('https://hf-mirror.com' if source == 'hf-mirror' else 'https://huggingface.co')
                address = host + '/' + relative
            with requests.get(address, stream=True, timeout=(20, 90), headers={'Accept-Encoding': 'identity'}) as response:
                response.raise_for_status()
                total = 0
                with (gzip.open(temp, 'wb') if asset.get('compression') == 'NONE' else temp.open('wb')) as output:
                    for chunk in response.iter_content(65536):
                        total += len(chunk)
                        if total > asset['size'] * 2 + 1048576:
                            raise ValueError('Download exceeds size limit')
                        output.write(chunk)
            temp.replace(path)
        with gzip.open(path, 'rb') as stream:
            data = stream.read(asset['size'] + 1)
        if len(data) != asset['size'] or hashlib.sha256(data).hexdigest() != asset['sha256']:
            path.unlink()
            raise ValueError('Decoded model checksum mismatch: ' + asset['name'])
        raw[asset['name']] = data
        asset['compression'] = 'GZIP'
        asset['downloadPath'] = pack['id'] + '/' + pack['version'] + '/' + asset['name'] + '.gz'
        asset['downloadSize'] = path.stat().st_size
        asset['downloadSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        asset.pop('url', None)
        print(pack['id'], asset['name'], 'verified', flush=True)
    with zipfile.ZipFile(base / (pack['id'] + '.zip'), 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in raw.items():
            archive.writestr(name, data)
    print(pack['id'] + '.zip exported', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packs', nargs='+', required=True, help='e.g. ja-en en-zh-Hans ko-en')
    parser.add_argument('--source', choices=['mozilla', 'huggingface', 'hf-mirror'], default='mozilla')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--catalog', type=Path, default=CATALOG, help='Use a fetched catalog snapshot instead of bundled metadata')
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text(encoding='utf-8'))
    if catalog.get('engine') != 'bergamot-v1' or catalog.get('schema') not in (1, 2):
        parser.error('Unsupported catalog')
    packs = {pack['id']: pack for pack in catalog['packages']}
    if any(name not in packs for name in args.packs):
        parser.error('Unknown package ID')
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copytree(ROOT / 'core/translation/src/main/assets/translation/licenses', args.output / 'licenses', dirs_exist_ok=True)
    for name in dict.fromkeys(args.packs):
        export(packs[name], catalog, args.output, args.source)
    catalog['packages'] = [packs[name] for name in dict.fromkeys(args.packs)]
    catalog['schema'] = 2
    catalog.pop('sourceKey', None)
    catalog.pop('fetchedAt', None)
    manifest = args.output / 'catalog.json.part'
    manifest.write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding='utf-8')
    manifest.replace(args.output / 'catalog.json')
