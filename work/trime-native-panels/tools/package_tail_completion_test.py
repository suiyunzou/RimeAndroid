"""Package the optional tail-completion adapter in a local debug APK.

--abi x86_64 targets the emulator; --abi arm64-v8a targets arm64 devices. The
librime JNI library comes per ABI from the deployed APK in --rime-apk, so the
adapter always matches the engine that is already installed on the target.
"""
from argparse import ArgumentParser
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile
import hashlib
import os
import subprocess
import tempfile

parser = ArgumentParser(description=__doc__)
parser.add_argument("--apk", type=Path, required=True)
parser.add_argument("--abi", choices=("x86_64", "arm64-v8a"), default="x86_64")
parser.add_argument("--rime-apk", "--x86-rime-apk", dest="rime_apk", type=Path, required=True)
parser.add_argument("--sdk", type=Path, required=True)
parser.add_argument("--rime-header", type=Path, required=True)
parser.add_argument("--keystore", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
tools = args.sdk / "build-tools/36.0.0"
host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
clang = args.sdk / "ndk/28.2.13676358/toolchains/llvm/prebuilt" / host / "bin" / ("clang.exe" if os.name == "nt" else "clang")
# Pinned librime public API header from docs/native-source-lock.json.
expected = "cfdd786df06467df16a0318d9d2d7c420e3d0a420317d379313994f30cd266f0"
if hashlib.sha256(args.rime_header.read_bytes()).hexdigest() != expected:
    raise SystemExit("Unexpected rime_api.h; use the pinned librime revision.")
def run(*argv):
    subprocess.run([str(arg) for arg in argv], check=True)
args.output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(dir=args.output.parent) as temporary:
    staging = Path(temporary)
    unaligned, aligned, addon = (staging / name for name in ("unsigned.apk", "aligned.apk", "libtail_completion.so"))
    clang_target = {"x86_64": "x86_64-linux-android21", "arm64-v8a": "aarch64-linux-android21"}[args.abi]
    run(clang, f"--target={clang_target}", "-shared", "-fPIC", "-O2", "-Wall", "-Wextra",
        "-Werror", "-I", args.rime_header.parent,
        root / "app/src/main/jni/tail-completion/tail_completion.c", "-ldl", "-o", addon)
    with ZipFile(args.apk) as src, ZipFile(args.rime_apk) as native, \
         ZipFile(root / "app/libs/sherpa-onnx.aar") as sherpa, ZipFile(unaligned, "w") as dst:
        for item in src.infolist():
            name = item.filename
            if name.startswith("lib/") or (name.startswith("META-INF/") and name.endswith((".MF", ".SF", ".RSA", ".EC", ".DSA"))):
                continue
            dst.writestr(item, src.read(item))
        for library, binary in {
            "librime_jni.so": native.read(f"lib/{args.abi}/librime_jni.so"),
            "libsherpa-onnx-jni.so": sherpa.read(f"jni/{args.abi}/libsherpa-onnx-jni.so"),
            addon.name: addon.read_bytes(),
        }.items():
            dst.writestr(f"lib/{args.abi}/{library}", binary, ZIP_STORED)
    run(tools / ("zipalign.exe" if os.name == "nt" else "zipalign"), "-P", "16", "-f", "4", unaligned, aligned)
    run(tools / ("apksigner.bat" if os.name == "nt" else "apksigner"), "sign",
        "--ks", args.keystore, "--ks-pass", "pass:android", "--out", args.output, aligned)