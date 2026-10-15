from __future__ import annotations

import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import android_sdk_probe as sdk


class AndroidSdkProbeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.tools = self.root / "build-tools" / "37.0.0"
        self.tools.mkdir(parents=True)
        (self.tools / "source.properties").write_text("Pkg.Revision=37.0.0\n", encoding="utf-8")
        for path in [self.root / "platform-tools" / name for name in ("adb", "adb.exe")] + \
                    [self.tools / name for name in ("aapt", "aapt.exe", "apksigner", "apksigner.bat", "zipalign", "zipalign.exe")]:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("executable", encoding="utf-8")
        self.platform = self.root / "platforms" / "android-37.0"
        self.platform.mkdir(parents=True)
        (self.platform / "android.jar").write_bytes(b"jar")
        (self.platform / "source.properties").write_text("AndroidVersion.ApiLevel=37.0\n", encoding="utf-8")

    def probe(self, platform=None):
        with mock.patch.object(sdk.os, "access", return_value=True):
            return sdk.probe(self.root, "37.0.0", platform)

    def test_exact_packages_and_tools(self):
        self.assertTrue(self.probe("android-37.0"))
        self.assertTrue(self.probe())
        (self.tools / "source.properties").write_text("Pkg.Revision=36.0.0\n", encoding="utf-8")
        self.assertFalse(self.probe())

    def test_integer_and_decimal_platforms_require_exact_api_values(self):
        integer = self.root / "platforms" / "android-37"
        integer.mkdir()
        (integer / "android.jar").write_bytes(b"jar")
        (integer / "source.properties").write_text("AndroidVersion.ApiLevel=37\n", encoding="utf-8")
        self.assertTrue(self.probe("android-37"))
        self.assertTrue(self.probe("android-37.0"))
        for wrong in ("37", "37.1", "36.0"):
            (self.platform / "source.properties").write_text("AndroidVersion.ApiLevel=" + wrong + "\n", encoding="utf-8")
            self.assertFalse(self.probe("android-37.0"))

    def test_missing_nonexecutable_and_wrong_platform_reject(self):
        with mock.patch.object(sdk.os, "access", return_value=False):
            self.assertFalse(sdk.probe(self.root, "37.0.0"))
        (self.platform / "android.jar").write_bytes(b"")
        self.assertFalse(self.probe("android-37.0"))
        self.assertFalse(self.probe("android-36"))

    def test_missing_or_corrupt_exact_metadata_and_tool_reject(self):
        (self.tools / "source.properties").write_bytes(b"\xff\xfe")
        self.assertFalse(self.probe())
        (self.tools / "source.properties").write_text("Pkg.Revision = 37.0.0\n", encoding="utf-8")
        (self.tools / ("aapt.exe" if os.name == "nt" else "aapt")).unlink()
        self.assertFalse(self.probe())

    def test_cli_miss_and_required_gate(self):
        output = self.root / "output"
        with mock.patch.dict(os.environ, {"ANDROID_SDK_ROOT": "", "ANDROID_HOME": ""}), \
                mock.patch("sys.argv", ["probe", "--build-tools", "37.0.0", "--github-output", str(output)]):
            sdk.main()
        self.assertEqual("ready=false\n", output.read_text(encoding="utf-8"))
        with mock.patch.dict(os.environ, {"ANDROID_SDK_ROOT": "", "ANDROID_HOME": ""}), \
                mock.patch("sys.argv", ["probe", "--build-tools", "37.0.0", "--require-ready"]):
            with self.assertRaises(SystemExit):
                sdk.main()

    def test_cli_hit_exports_both_roots_without_install(self):
        output, environment = self.root / "output", self.root / "env"
        with mock.patch.dict(os.environ, {"ANDROID_SDK_ROOT": str(self.root), "ANDROID_HOME": ""}), \
                mock.patch.object(sdk, "probe", return_value=True), \
                mock.patch("sys.argv", ["probe", "--build-tools", "37.0.0", "--github-output", str(output), "--github-env", str(environment)]):
            sdk.main()
        self.assertIn("ready=true", output.read_text(encoding="utf-8"))
        self.assertIn("ANDROID_HOME=", environment.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
