from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import refine_release_notes as refine
import release_notes_receipt as receipts
from release_context import CommitInfo, ReleaseContext


class RefineReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / "REPORT.md"
        self.output = self.root / "refined"
        commits = [CommitInfo(value * 40, "2026-10-15", "修复播放异常", "", 1, 1, 0, ()) for value in "ab"]
        self.context = ReleaseContext("alpha", "v1.2.1-alpha.1", "v1.2.0", "b" * 40, commits, repo_root=self.root)
        self.config = {"max_items": 2, "forbidden_terms": []}
        self.classified = {"items": [{"category": "fixed", "group": "", "text": "修复播放偶发异常。", "commits": ["a" * 40]}],
                           "maintenance": ["b" * 40]}
        self.git = mock.patch.object(receipts, "run_git", side_effect=lambda root, command, value:
                                     "d" * 40 if value.startswith("v") else value.split("^", 1)[0])
        self.git.start()
        self.addCleanup(self.git.stop)
        self.receipt = receipts.create(self.context, self.config, Path(refine.__file__).parent, self.classified)
        self.write_report(self.receipt)
        self.arguments = ["refine", "--report", str(self.report), "--repo-root", str(self.root),
                          "--channel", "alpha", "--release-tag", self.context.release_tag, "--commit", self.context.commit,
                          "--repository-url", "https://github.com/example/repository", "--output-dir", str(self.output)]

    def write_report(self, receipt):
        self.report.write_text(f"### Release notes · {self.context.release_tag} (alpha)\n" + receipts.PREFIX +
                               json.dumps(receipt, ensure_ascii=False), encoding="utf-8")

    def run_main(self):
        with mock.patch("sys.argv", self.arguments), mock.patch.object(refine, "load_config", return_value=self.config), \
                mock.patch.object(refine, "build_context", return_value=self.context), \
                mock.patch.object(refine.llm_providers, "load_slots", return_value=([], [])):
            refine.main()

    def test_exact_reuse_still_generates_and_validates_overview(self):
        with mock.patch.object(refine.notes, "_generate_overview", return_value=["本版本优化播放体验。"] ) as overview:
            self.run_main()
        overview.assert_called_once()
        report = (self.output / "REPORT.md").read_text(encoding="utf-8")
        restored, _ = receipts.restore(report, self.context, self.config, Path(refine.__file__).parent)
        self.assertEqual(self.classified, restored)
        self.assertIn("优化播放体验", (self.output / "CHANGELOG.md").read_text(encoding="utf-8"))

    def test_binding_mismatch_rejects_before_any_refinement(self):
        self.context.commit = "e" * 40
        with mock.patch.object(refine.notes, "limit_notes") as limit, self.assertRaisesRegex(SystemExit, "identity mismatch"):
            self.run_main()
        limit.assert_not_called()
        self.assertFalse((self.output / "CHANGELOG.md").exists())

    def test_missing_coverage_cannot_be_inferred_as_maintenance(self):
        self.receipt["classified"]["maintenance"] = []
        self.receipt["sha256"] = receipts.digest({key: value for key, value in self.receipt.items() if key != "sha256"})
        self.write_report(self.receipt)
        with mock.patch.object(refine.notes, "limit_notes") as limit, self.assertRaisesRegex(SystemExit, "exact complete"):
            self.run_main()
        limit.assert_not_called()

    def test_old_unbound_report_and_bad_final_summary_do_not_publish(self):
        self.report.write_text(f"### Release notes · {self.context.release_tag} (alpha)\n", encoding="utf-8")
        with self.assertRaisesRegex(SystemExit, "exactly one"):
            self.run_main()
        self.write_report(self.receipt)
        with mock.patch.object(refine.notes, "_generate_overview", return_value=["bad @mention!"]), \
                self.assertRaisesRegex(SystemExit, "failed validation"):
            self.run_main()
        self.assertFalse((self.output / "CHANGELOG.md").exists())


if __name__ == "__main__":
    unittest.main()
