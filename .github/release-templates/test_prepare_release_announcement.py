import unittest

from prepare_release_announcement import prepare_announcement, FULL_NOTES_NAME


class AnnouncementBudgetTests(unittest.TestCase):
    def test_normal_announcement_is_unchanged(self):
        original = "标题\n- 更新一\n"
        public, full = prepare_announcement(original, "https://github.com/o/r", "v1.2.0")
        self.assertEqual(original, public)
        self.assertIsNone(full)

    def test_large_archive_moves_to_full_asset_without_losing_main_changes(self):
        original = ("## Alpha\n> 风险提示\n\n### ✨ 新增\n- 新功能\n\n"
                    "<details>\n<summary>📜 完整提交记录（1050）</summary>\n"
                    + "- 完整提交\n" * 1050 + "</details>\n\n---\n\n### 📦 下载\n安装包与校验值\n")
        public, full = prepare_announcement(original, "https://github.com/o/r", "v1.2.1-alpha.1", budget=600)
        self.assertEqual(original, full)
        self.assertIn("- 新功能", public)
        self.assertIn("风险提示", public)
        self.assertIn("安装包与校验值", public)
        self.assertIn("releases/download/v1.2.1-alpha.1/" + FULL_NOTES_NAME, public)
        self.assertLessEqual(len(public), 600)

    def test_1050_independent_updates_keep_full_body_as_asset(self):
        original = ("## Stable\n版本概述。\n\n### ✨ 新增\n" +
                    "\n".join(f"- 新增功能 {index}" for index in range(1050)) +
                    "\n---\n\n### 📦 下载\n下载信息\n")
        public, full = prepare_announcement(original, "https://github.com/o/r", "v1.2.1", budget=600)
        self.assertEqual(original, full)
        self.assertIn("新增功能 0", full)
        self.assertIn("新增功能 1049", full)
        self.assertIn("下载信息", public)
        self.assertLessEqual(len(public), 600)

    def test_unstructured_manual_override_is_preserved_in_full_asset(self):
        original = "手写更新。" * 1000
        public, full = prepare_announcement(original, "https://github.com/o/r", "v1.2.1", budget=600)
        self.assertEqual(original, full)
        self.assertIn(FULL_NOTES_NAME, public)
        self.assertLessEqual(len(public), 600)

    def test_large_manual_alpha_keeps_warning_when_wrapper_must_be_rebuilt(self):
        warning = "> [!WARNING]\n> Alpha 风险说明，普通用户优先 Stable。\n"
        original = "## Alpha\n\n" + warning + "\n" + "手写更新。" * 1000
        public, full = prepare_announcement(original, "https://github.com/o/r", "v1.2.1-alpha.1", budget=600)
        self.assertEqual(original, full)
        self.assertIn(warning, public)
        self.assertIn(FULL_NOTES_NAME, public)
