#!/usr/bin/env python3
"""Reuse an exact, executable SDK installation; never install or trust an APK."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re


def properties(path: Path) -> dict[str, str]:
    try:
        if not path.is_file() or path.stat().st_size > 65536:
            return {}
        return {key.strip(): value.strip() for line in path.read_text(encoding="utf-8").splitlines()
                if "=" in line and not line.lstrip().startswith("#") for key, value in [line.split("=", 1)]}
    except (OSError, UnicodeError):
        return {}


def probe(root: Path, build_tools: str, platform: str | None = None) -> bool:
    """Only inspect the selected SDK's exact packages, never another version on PATH."""
    tools = root / "build-tools" / build_tools
    if properties(tools / "source.properties").get("Pkg.Revision", "").strip() != build_tools:
        return False
    suffix = ".exe" if os.name == "nt" else ""
    executables = [root / "platform-tools" / ("adb" + suffix),
                   tools / ("aapt" + suffix), tools / ("zipalign" + suffix),
                   tools / ("apksigner.bat" if os.name == "nt" else "apksigner")]
    if not all(path.is_file() and os.access(path, os.X_OK) for path in executables):
        return False
    if platform is not None:
        directory = root / "platforms" / platform
        api = platform.removeprefix("android-")
        jar = directory / "android.jar"
        if not jar.is_file() or jar.stat().st_size == 0:
            return False
        if properties(directory / "source.properties").get("AndroidVersion.ApiLevel", "").strip() != api:
            return False
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-tools", required=True)
    parser.add_argument("--platform", help="Exact SDK platform directory, e.g. android-37.0")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--github-env", type=Path)
    parser.add_argument("--require-ready", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.build_tools):
        parser.error("build-tools must be an exact numeric revision")
    if args.platform and not re.fullmatch(r"android-[0-9]+(?:\.[0-9]+)?", args.platform):
        parser.error("platform must be an exact android SDK directory")
    root = None
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name, "")
        if value and "\n" not in value and "\r" not in value and probe(Path(value), args.build_tools, args.platform):
            root = Path(value).resolve()
            break
    ready = root is not None
    output = f"ready={str(ready).lower()}\n" + (f"sdk_root={root}\n" if ready else "")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as stream:
            stream.write(output)
    if ready and args.github_env:
        with args.github_env.open("a", encoding="utf-8") as stream:
            stream.write(f"ANDROID_SDK_ROOT={root}\nANDROID_HOME={root}\n")
    print(f"Exact Android SDK packages ready: {str(ready).lower()}")
    if args.require_ready and not ready:
        raise SystemExit("Required Android SDK packages are missing, wrong-version or not executable")


if __name__ == "__main__":
    main()
