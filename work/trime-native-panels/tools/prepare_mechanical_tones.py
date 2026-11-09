"""Extract bounded press/release samples from pinned MIT-licensed Mechvibes packs.

Requires requests, numpy, soundfile. Downloads stay in the supplied scratch folder.
The Android app only receives short mono PCM samples and provenance.
"""
from pathlib import Path
import hashlib
import json
import sys
import requests
import numpy as np
import soundfile as sf

REVISION = "56d7135bcdf6ceb7fe2f4e06f8ca0903277a9e68"
BASE = f"https://raw.githubusercontent.com/hainguyents13/mechvibes/{REVISION}"
PACKS = {
    "mx_blue": "cherrymx-blue-pbt", "mx_brown": "cherrymx-brown-pbt",
    "mx_red": "cherrymx-red-abs", "mx_black": "cherrymx-black-pbt",
    "topre": "topre-purple-hybrid-pbt", "oreo": "eg-oreo",
    "crystal": "eg-crystal-purple",
}
KEYS = {"key": "KeyA", "space": "Space", "delete": "Backspace", "enter": "Enter"}

def main():
    cache = Path(sys.argv[1]).resolve()
    cache.mkdir(parents=True, exist_ok=True)
    app = Path(__file__).resolve().parents[1] / "app"
    target = app / "src/main/assets/feedback-tones"
    target.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    def fetch(url, path):
        if not path.exists():
            response = session.get(url, timeout=90)
            response.raise_for_status()
            path.write_bytes(response.content)
        return path.read_bytes()
    license_text = fetch(BASE + "/LICENSE", cache / "Mechvibes-LICENSE.txt")
    (app / "licenses/Mechvibes-MIT.txt").write_bytes(license_text)
    (target / "LICENSE-Mechvibes.txt").write_bytes(license_text)
    records = []
    for tone, pack in PACKS.items():
        folder = cache / pack
        folder.mkdir(exist_ok=True)
        source = f"{BASE}/soundpacks/keyboard/{pack}"
        config = json.loads(fetch(source + "/config.json", folder / "config.json"))
        audio_name = config["audio_file"]
        original = fetch(source + "/" + audio_name, folder / audio_name)
        samples, rate = sf.read(folder / audio_name, always_2d=True)
        mono = samples.mean(axis=1)
        peak = float(np.max(np.abs(mono)))
        gain = min(2.0, .85 / max(peak, .00001))
        files = []
        for kind, key in KEYS.items():
            for release, timing in enumerate(config["definitions"][key]["timing"]):
                start, end = (round(ms * rate / 1000) for ms in timing)
                clip = mono[start:end].copy() * gain
                assert 0 < len(clip) <= rate, (pack, key, timing)
                fade = min(round(rate * .0015), len(clip) // 4)
                clip[:fade] *= np.linspace(0, 1, fade)
                clip[-fade:] *= np.linspace(1, 0, fade)
                name = f"{tone}-{kind}-{'up' if release else 'down'}.wav"
                sf.write(target / name, clip, rate, subtype="PCM_16")
                files.append({"file": name, "timing_ms": timing,
                              "sha256": hashlib.sha256((target / name).read_bytes()).hexdigest()})
        records.append({"id": tone, "name": config["name"], "author": config.get("author"),
                        "source": source, "source_sha256": hashlib.sha256(original).hexdigest(),
                        "license": "MIT", "changes": "mono, bounded source timings, limited gain, 1.5ms edge fade", "files": files})
        print(tone, len(files), flush=True)
    (target / "recordings-provenance.json").write_text(json.dumps({"revision": REVISION, "packs": records}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
