"""Build the isolated research APK with the repository's pinned Gradle wrapper."""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent


def options():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME"))
    return parser.parse_args()


def java_tool(name, jdk):
    suffix = ".exe" if os.name == "nt" else ""
    candidate = str(Path(jdk) / "bin" / (name + suffix)) if jdk else shutil.which(name)
    if not candidate or not Path(candidate).is_file():
        raise SystemExit("A JDK with java and javac is required; pass --jdk.")
    return candidate


if __name__ == "__main__":
    args = options()
    env = os.environ.copy()
    if args.sdk:
        env["ANDROID_HOME"] = str(Path(args.sdk).resolve())
    # Prefab's helper expects clean stderr. These options are scoped to the child build.
    env.pop("JDK_JAVA_OPTIONS", None)
    windows_root = env.get("SystemRoot") or env.get("WINDIR")
    short_temp = Path(windows_root) / "Temp" if os.name == "nt" and windows_root else Path(tempfile.gettempdir())
    if not short_temp.is_dir() or not os.access(short_temp, os.W_OK):
        short_temp = Path(tempfile.gettempdir())
    short_temp = short_temp.resolve()
    env["TEMP"] = env["TMP"] = str(short_temp)
    wrapper = ROOT.parents[1] / "Bilibili_Innocent_Lab/gradle/wrapper/gradle-wrapper.jar"
    subprocess.run([java_tool("java", args.jdk), "-Xmx1536m", "-Dfile.encoding=UTF-8",
                    "-Djava.io.tmpdir=" + str(short_temp), "-classpath", str(wrapper),
                    "org.gradle.wrapper.GradleWrapperMain", "-p", str(ROOT), "--no-daemon",
                    "--console=plain", "assembleDebug", "lintDebug", "--continue"],
                   cwd=ROOT, env=env, check=True)
