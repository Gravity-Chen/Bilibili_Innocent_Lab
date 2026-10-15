from __future__ import annotations

from dataclasses import replace
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import generate_release_notes as notes
import release_notes_receipt as receipts
from release_context import CommitInfo, ReleaseContext


class ReleaseNotesReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in receipts.TOOLS:
            (self.root / name).write_text(name, encoding="utf-8")
        self.commits = [CommitInfo(character * 40, "2026-10-15", "修复播放", "", 1, 1, 0, ())
                        for character in ("a", "b", "c")]
        self.context = ReleaseContext("alpha", "v1.2.1-alpha.1", "v1.2.0", "c" * 40,
                                      self.commits, repo_root=self.root)
        self.config = {"max_items": 100}
        self.classified = {"items": [{"category": "fixed", "group": "", "text": "修复播放偶发异常。",
                                       "commits": ["a" * 40]}], "maintenance": ["b" * 40, "c" * 40]}
        self.git = mock.patch.object(receipts, "run_git", side_effect=lambda root, command, value:
                                     ("d" * 40 if value.startswith("v") else value.split("^", 1)[0]) + "\n")
        self.git.start()
        self.addCleanup(self.git.stop)

    def report(self):
        self.receipt = receipts.create(self.context, self.config, self.root, self.classified)
        return receipts.PREFIX + json.dumps(self.receipt, ensure_ascii=False)

    def test_exact_receipt_roundtrip_preserves_maintenance(self):
        value, _ = receipts.restore(self.report(), self.context, self.config, self.root)
        self.assertEqual(self.classified, value)

    def test_source_baseline_order_config_or_tool_change_reject(self):
        report = self.report()
        for context in (replace(self.context, commit="e" * 40),
                        replace(self.context, previous_tag="v1.1.0"),
                        replace(self.context, commits=list(reversed(self.commits)))):
            with self.assertRaisesRegex(ValueError, "identity mismatch"):
                receipts.restore(report, context, self.config, self.root)
        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            receipts.restore(report, self.context, {"max_items": 99}, self.root)
        (self.root / receipts.TOOLS[0]).write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            receipts.restore(report, self.context, self.config, self.root)

    def test_no_receipt_partial_duplicate_or_tampered_reject(self):
        report = self.report()
        for value in ("old report with original evidence", report + "\n" + report,
                      report.replace("修复播放偶发异常。", "changed")):
            with self.assertRaises(ValueError):
                receipts.restore(value, self.context, self.config, self.root)
        partial = {**self.classified, "maintenance": []}
        with self.assertRaisesRegex(ValueError, "exact complete"):
            receipts.create(self.context, self.config, self.root, partial)

    def test_same_baseline_tag_moved_to_new_commit_rejects(self):
        report = self.report()
        with mock.patch.object(receipts, "run_git", return_value="f" * 40):
            with self.assertRaisesRegex(ValueError, "identity mismatch"):
                receipts.restore(report, self.context, self.config, self.root)

    def test_partial_batches_never_become_reusable(self):
        context = replace(self.context, commits=[self.commits[0]] * 41)
        classified = {}
        with mock.patch.object(notes, "batch_evidence_sections", return_value=[]), \
                mock.patch.object(notes, "_generate_batch", side_effect=[(notes.ReleaseNotes([], [], ["a" * 40]), "fake"), None]):
            self.assertIsNone(notes.generate_with_llm(context, self.config, [], [], [], classified))
        self.assertEqual({}, classified)

    def test_complete_classification_survives_final_refinement_failure(self):
        context = replace(self.context, commits=[self.commits[0]] * 41)
        item = notes.NoteItem("fixed", "", "修复播放异常。", ["a" * 40])
        batch = (notes.ReleaseNotes([], [item], []), "fake")
        classified = {}
        with mock.patch.object(notes, "_generate_batch", return_value=batch), \
                mock.patch.object(notes, "batch_evidence_sections", return_value=[]), \
                mock.patch.object(notes, "limit_notes", return_value=None), \
                mock.patch.object(notes, "_generate_overview", return_value=[]):
            outcome = notes.generate_with_llm(context, {"max_items": 0}, [], [], [], classified)
        self.assertIsNone(outcome)
        self.assertEqual([vars(item)], classified["items"])


if __name__ == "__main__":
    unittest.main()
