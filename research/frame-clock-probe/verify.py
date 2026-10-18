"""JVM and static APK/ELF checks only; never installs, loads JNI or operates a device."""
from pathlib import Path, PurePosixPath
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from build import ROOT, java_tool, options


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(command):
    return subprocess.check_output(list(map(str, command)), cwd=ROOT, timeout=90,
                                   text=True, encoding="utf-8", errors="strict")


if __name__ == "__main__":
    args = options()
    if not args.sdk:
        raise SystemExit("Pass --sdk or set ANDROID_HOME.")
    sdk = Path(args.sdk).resolve()
    android_jars = [p for p in (sdk / "platforms/android-37/android.jar",
                                sdk / "platforms/android-37.0/android.jar") if p.is_file()]
    if len(android_jars) != 1:
        raise SystemExit("Exactly one installed Android 37 platform is required.")
    api, shadow = [Path(p) for p in (ROOT / "app/build/probe-dependencies/debug.txt").read_text().splitlines()]
    output = ROOT / "build/verification"
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(api) as archive:
        api_bytes = archive.read("classes.jar")
    assert sha(api_bytes) == "a515dd7a53cd7a47c05e101dff77d61acb3091a97b20a885b9ea3494412db985"
    assert sha(shadow.read_bytes()) == "2cad01ff4d59542958775e19f281981a7e84b39670b5fae75905b8511e10c7cd"
    api_jar = output / "api-classes.jar"
    api_jar.write_bytes(api_bytes)
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    main_classes = ROOT / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
    classpath = os.pathsep.join(map(str, (main_classes, android_jars[0], api_jar)))
    run([java_tool("javac", args.jdk), "-cp", classpath, "-d", classes,
         *sorted((ROOT / "self-test").glob("*.java"))])
    tests = []
    for name in ("ProbeLifecycleSelfTest", "ProbeRootNormalizerSelfTest", "ProbeOutputDiagnosticSelfTest",
                 "NativeEntrySelfTest", "ClockProjectionSelfTest"):
        line = run([java_tool("java", args.jdk), "-cp", str(classes) + os.pathsep + classpath,
                    "research.innocent.frame.observer." + name]).strip()
        assert "PASS" in line
        tests.append(line)
    suffix = ".exe" if os.name == "nt" else ""
    llvm = sdk / "ndk/29.0.14206865/toolchains/llvm/prebuilt"
    hosts = [p for p in llvm.glob("*") if (p / ("bin/llvm-readelf" + suffix)).is_file()]
    assert len(hosts) == 1
    readelf = hosts[0] / ("bin/llvm-readelf" + suffix)
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    expected_exports = {"native_init", "JNI_OnLoad"} | {
        "Java_research_innocent_frame_observer_OwnedNativeBridge_" + name
        for name in ("queryFlags", "initialize", "beginObservation", "tryInstall", "endObservation", "statistics", "drain")}
    libraries = {}
    with zipfile.ZipFile(apk) as archive, zipfile.ZipFile(shadow) as dependency:
        names = archive.namelist()
        assert len(names) == len(set(names))
        assert all(not PurePosixPath(n).is_absolute() and ".." not in PurePosixPath(n).parts and "\\" not in n for n in names)
        assert archive.read("META-INF/xposed/native_init.list").strip() == b"libinnocent_shadowhook_master_probe.so"
        assert archive.read("META-INF/xposed/scope.list").strip() == b"tv.danmaku.bili"
        assert len([n for n in names if n.startswith("lib/")]) == 6
        assert not any(n.endswith((".onnx", ".pth", ".param", ".bin")) for n in names)
        for abi in ("arm64-v8a", "armeabi-v7a"):
            hashes = {}
            for name in ("libinnocent_shadowhook_master_probe.so", "libshadowhook.so", "libshadowhook_nothing.so"):
                data = archive.read(f"lib/{abi}/{name}")
                if name.startswith("libshadowhook"):
                    assert data == dependency.read(f"jni/{abi}/{name}")
                else:
                    copy = ROOT / f"app/build/intermediates/native-entry-copy/debug/{abi}/{name}"
                    assert data == copy.read_bytes()
                    details = run([readelf, "--wide", "--dyn-syms", "--program-headers", "--dynamic", copy])
                    exports = set(re.findall(r"\bGLOBAL\s+DEFAULT\s+(?!UND\b)\S+\s+(\S+)\s*$", details, re.M))
                    assert exports == expected_exports
                    needed = re.findall(r"Shared library: \[(.*?)\]", details)
                    assert set(needed) <= {"libc.so", "libm.so", "libdl.so", "libshadowhook.so"}
                    assert "libshadowhook.so" in needed
                    aligns = [int(v, 16) for v in re.findall(r"^\s*LOAD\s+.*\s+(0x[0-9a-f]+)\s*$", details, re.M)]
                    assert aligns and min(aligns) >= 16384
                hashes[name] = sha(data)
            libraries[abi] = hashes
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    assert not any(i.get("severity") in ("Error", "Fatal") for i in lint.findall("issue"))
    report = {"apkSha256": sha(apk.read_bytes()), "libraries": libraries, "jvm": tests,
              "jvmChecks": sum(int(re.search(r"checks=(\d+)", s)[1]) for s in tests),
              "lintErrors": 0, "deviceExecuted": False}
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
