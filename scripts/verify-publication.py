"""Check the actual Git index before publishing. Uses only the Python standard library.

This is a guardrail, not a proof that arbitrary user content is safe to publish.
Do not print matched secret values. Review screenshots and resource provenance separately.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "work/trime-native-panels/"
ASSETS = ANDROID + "app/src/main/assets/"
GIT = shutil.which("git")
if not GIT:
    raise SystemExit("Git must be available on PATH.")


def git(*args: str, input: bytes | None = None) -> bytes:
    return subprocess.run([GIT, *args], cwd=ROOT, input=input, capture_output=True, check=True).stdout


def staged_files() -> dict[str, bytes]:
    records = []
    for row in git("ls-files", "--stage", "-z").split(b"\0"):
        if not row:
            continue
        meta, name = row.split(b"\t", 1)
        mode, oid, stage = meta.split()
        path = name.decode("utf-8")
        if stage != b"0" or mode not in (b"100644", b"100755"):
            raise ValueError("Unmerged entry, symlink or submodule: " + path)
        records.append((path, oid))
    if not records:
        raise ValueError("The index is empty. Stage the reviewed files first.")
    batch = git("cat-file", "--batch", input=b"\n".join(oid for _, oid in records) + b"\n")
    result = {}
    cursor = 0
    for path, oid in records:
        end = batch.index(b"\n", cursor)
        actual_oid, kind, size = batch[cursor:end].split()
        if actual_oid != oid or kind != b"blob":
            raise ValueError("Unexpected Git object: " + path)
        cursor = end + 1
        result[path] = batch[cursor:cursor + int(size)]
        cursor += int(size) + 1
    return result


TOP_LEVEL = {".gitignore", ".gitattributes", "README.md", "LICENSE", "THIRD_PARTY.md"}
ANDROID_FILES = {
    ".gitignore", ".gitattributes", ".editorconfig", ".clang-format", "AUTHORS", "LICENSE",
    "README.md", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew",
    "gradlew.bat", "prepare-build.ps1", "prebuilt-provenance.json", "app/build.gradle.kts",
    "app/proguard-rules.pro", "codegen/build.gradle.kts", "codegen/.gitignore",
    "build-logic/settings.gradle.kts", "build-logic/convention/build.gradle.kts",
}
ANDROID_TREES = (
    "app/src/", "app/prebuilt/", "app/schemas/", "app/licenses/", "gradle/", "tools/", "patches/",
    "codegen/src/", "build-logic/convention/src/", "build-logic/gradle/",
)
FONT_FILES = {"build_outline_font.py", "letter_hint_glyphs.py", "emoji_nav_icons.py", "settings_height_placement.py"}
IMAGES = {"docs/images/keyboard.png", "docs/images/key-settings.png", "docs/images/digit-variants.png"}
FORBIDDEN_PARTS = {"scratch", "outputs", ".git", ".idea", ".gradle", ".kotlin", "build", "__pycache__", "backups", "shared_prefs"}
FORBIDDEN_NAMES = {"local.properties", "keystore.properties", "installation.yaml", "user.yaml", "custom_phrase.txt", "clean_local_rules.tsv", "clean_email_domains.txt", "clean_height_profile.yaml"}
FORBIDDEN_SUFFIXES = {".apk", ".aab", ".zip", ".log", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".db", ".sqlite", ".sqlite3", ".ldb", ".sst", ".gram"}
PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "GitHub token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b"),
    "AWS access key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "credential URL": re.compile(r"https?://[^\s/:]+:[^\s/@]+@"),
    "local Windows path": re.compile(r"(?i)\b[A-Z]:[\\/]+(?:Users|trae-project|Codex|ProgramFile)[\\/]"),
    "local home path": re.compile(r"/(?:Users|home)/[A-Za-z0-9_.-]+/"),
}


def allowed(path: str) -> bool:
    if path in TOP_LEVEL or path in IMAGES or path == "scripts/verify-publication.py":
        return True
    if path.startswith("docs/") and PurePosixPath(path).suffix in {".md", ".json", ".txt"}:
        return True
    if path.startswith("work/trime-theme/"):
        return path.removeprefix("work/trime-theme/") in FONT_FILES
    if path.startswith(ANDROID):
        rel = path.removeprefix(ANDROID)
        return rel in ANDROID_FILES or rel.startswith(ANDROID_TREES)
    return False


def main() -> int:
    files = staged_files()
    errors = []
    text_count = 0
    for path, data in files.items():
        p = PurePosixPath(path)
        if not allowed(path):
            errors.append((path, "outside publication scope"))
        if set(p.parts) & FORBIDDEN_PARTS or p.name in FORBIDDEN_NAMES or p.suffix.lower() in FORBIDDEN_SUFFIXES or p.name.startswith(".env") or any(".userdb" in part for part in p.parts):
            errors.append((path, "private or generated file"))
        if len(data) > 50 * 1024 * 1024:
            errors.append((path, "file exceeds 50 MiB review limit"))
        if b"\0" not in data:
            try:
                content = data.decode("utf-8-sig")
            except UnicodeDecodeError:
                errors.append((path, "unexpected non-UTF-8 content"))
                continue
            text_count += 1
            for label, pattern in PATTERNS.items():
                for match in pattern.finditer(content):
                    line = content.count("\n", 0, match.start()) + 1
                    errors.append((path + ":" + str(line), label))
        elif p.suffix.lower() not in {".png", ".ttf", ".so", ".jar", ".wav"}:
            errors.append((path, "unexpected binary file"))

    verified_resources = 0

    def verify_hash(path: str, expected: str) -> None:
        nonlocal verified_resources
        if path not in files or hashlib.sha256(files[path]).hexdigest() != expected:
            errors.append((path, "missing or mismatched resource receipt"))
        else:
            verified_resources += 1

    original = json.loads(files[ANDROID + "prebuilt-provenance.json"])
    for item in original["files"]:
        # Additional custom assets have separate receipts. checksums.json is generated.
        if item["path"] != "app/src/main/assets/checksums.json":
            verify_hash(ANDROID + item["path"], item["sha256"])
    glide = json.loads(files[ASSETS + "glide/source-receipt.json"])
    for name, digest in glide["asset_sha256"].items():
        verify_hash(ASSETS + "glide/" + name, digest)
    books = json.loads(files[ASSETS + "book-quotes/SOURCE.json"])
    for name, digest in books["files"].items():
        verify_hash(ASSETS + "book-quotes/" + name, digest)
    tones = json.loads(files[ASSETS + "feedback-tones/manifest.json"])
    for name, info in tones.items():
        verify_hash(ASSETS + "feedback-tones/" + name + ".wav", info["sha256"])

    for path in IMAGES:
        data = files[path]
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            errors.append((path, "invalid PNG header"))
            continue
        pos = 8
        while pos < len(data):
            size = struct.unpack(">I", data[pos:pos + 4])[0]
            kind = data[pos + 4:pos + 8]
            if kind not in {b"IHDR", b"IDAT", b"IEND", b"sRGB", b"sBIT", b"gAMA", b"cHRM", b"pHYs"}:
                errors.append((path, "image metadata needs review: " + kind.decode("ascii", "replace")))
            pos += 12 + size

    result = {"files": len(files), "text_files": text_count, "bytes": sum(map(len, files.values())),
              "resource_hashes_verified": verified_resources, "errors": errors}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return bool(errors)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit("Publication check could not complete: " + str(error))
