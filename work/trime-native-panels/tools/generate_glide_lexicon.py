"""Extract public ReRime release-50 word tables; never reads user learned data.

Usage: python tools/generate_glide_lexicon.py <directory-with-public-four-files>
The directory must contain jichu.dict.yaml, en.dict.yaml and the two upstream notices.
"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import sys
import unicodedata

source = Path(sys.argv[1]).resolve()
target = Path(__file__).resolve().parents[1] / 'app/src/main/assets/glide'
target.mkdir(parents=True, exist_ok=True)

def rows(name):
    for line in (source/name).read_text(encoding='utf-8').splitlines():
        columns = line.split('\t')
        if len(columns) < 3:
            continue
        text, code, weight = columns[:3]
        try:
            weight = int(weight)
        except ValueError:
            continue
        yield text, code, weight

def normalize(code):
    for character in 'üǖǘǚǜ':
        code = code.replace(character, 'v')
    return ''.join(c for c in unicodedata.normalize('NFD', code.lower()) if 'a' <= c <= 'z')

chinese = {}
for text, code, weight in rows('jichu.dict.yaml'):
    letters = normalize(code)
    if 2 <= len(text) <= 8 and all('\u3400' <= c <= '\u9fff' for c in text) and 3 <= len(letters) <= 32:
        key = (text, letters)
        chinese[key] = max(weight, chinese.get(key, 0))
# A top-weight slice is bounded for per-gesture mobile decoding. No sample phrases
# are injected; rare words and arbitrary sentences remain a documented limitation.
chinese = sorted(chinese.items(), key=lambda item: (-item[1], item[0]))[:80000]
english = {}
for text, code, weight in rows('en.dict.yaml'):
    if re.fullmatch('[a-z]{2,24}', code):
        english[(text, code)] = max(weight, english.get((text, code), 0))
english = sorted(english.items(), key=lambda item: (-item[1], item[0]))[:30000]
for name, data in [('chinese.tsv', chinese), ('english.tsv', english)]:
    (target/name).write_text(''.join(f'{text}\t{code}\t{weight}\n' for (text, code), weight in data), encoding='utf-8', newline='\n')
for name in ['LICENSE-ReRime-Wanxiang.txt', 'NOTICE-ReRime-Wanxiang.md']:
    shutil.copyfile(source/name, target/name)
receipt = {
    'release': 'wanxiang-precompiled-50-880ac18dd08e',
    'source': 'https://github.com/amzxyz/rime-wanxiang',
    'changes': 'Tone-stripped Pinyin (ü→v); top-weight 80000 Chinese and 30000 English public entries; no personal vocabulary.',
    'counts': {'chinese': len(chinese), 'english': len(english)},
    'source_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in source.iterdir() if p.is_file()},
    'asset_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in target.iterdir() if p.is_file() and p.name != 'source-receipt.json'},
}
(target/'source-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'counts':receipt['counts'],'bytes':sum(p.stat().st_size for p in target.iterdir() if p.is_file())}))
