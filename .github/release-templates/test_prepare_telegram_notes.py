import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import llm_providers
import prepare_telegram_notes as telegram_notes
from prepare_telegram_notes import generate, generate_announcement


CONFIG = {"product_name": "Demo", "product_context": "演示模块", "max_items": 100,
          "source_prefixes": ["src/"], "forbidden_terms": ["CI", "Gradle"]}
SLOT = llm_providers.ProviderSlot(2, "openai", "https://provider.invalid/v1", "fixture", "secret")


def payload(commits, *, items=True):
    return json.dumps({"summary": [], "items": ([{"category": "improved", "group": "评论",
        "text": "优化了评论列表的显示效果。", "commits": [sha[:7] for sha in commits]}] if items else []),
        "maintenance": [] if items else [sha[:7] for sha in commits]}, ensure_ascii=False)


class TelegramNotesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.before = self.commit("feat: previous published change")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, text=True, encoding="utf-8").strip()

    def commit(self, subject):
        self.git("commit", "--allow-empty", "-qm", subject)
        return self.git("rev-parse", "HEAD")

    def test_all_intervening_commits_survive_the_old_twenty_twelve_and_four_limits(self):
        for index in range(25):
            head = self.commit(f"feat: change-{index:02d}")
        notes = generate(self.repo, "v1.2.1-canary.3", head, self.before)
        self.assertEqual(25, sum(line.startswith("- ") for line in notes.splitlines()))
        self.assertNotIn("previous published change (", notes)
        for index in range(25):
            self.assertIn(f"change-{index:02d}", notes)

    def test_equal_subjects_maintenance_and_merge_commits_are_not_lost(self):
        first = self.commit("fix: repeated subject")
        second = self.commit("fix: repeated subject")
        self.commit("chore: build maintenance")
        branch = self.git("branch", "--show-current")
        self.git("checkout", "-qb", "feature")
        self.commit("feat: feature branch")
        self.git("checkout", "-q", branch)
        self.commit("fix: main branch")
        self.git("merge", "--no-ff", "-qm", "Merge feature branch", "feature")
        notes = generate(self.repo, "v1.2.1-canary.3", self.git("rev-parse", "HEAD"), self.before)
        self.assertEqual(2, notes.count("repeated subject"))
        self.assertIn(first[:7], notes)
        self.assertIn(second[:7], notes)
        self.assertIn("build maintenance", notes)
        self.assertIn("Merge feature branch", notes)
        self.assertEqual(6, sum(line.startswith("- ") for line in notes.splitlines()))

    def test_first_delivery_repeat_and_selected_older_build_have_explicit_ranges(self):
        current = self.commit("fix: current")
        first = generate(self.repo, "v1.2.1-canary.3", current)
        self.assertIn("首次发布", first)
        self.assertEqual(2, sum(line.startswith("- ") for line in first.splitlines()))
        for selected in [current, self.before]:
            notes = generate(self.repo, "v1.2.1-canary.3", selected, current)
            self.assertIn("没有新增提交", notes)
            self.assertIn(selected, notes)

    def test_missing_history_fails_instead_of_falling_back_to_one_commit(self):
        with self.assertRaises(subprocess.CalledProcessError):
            generate(self.repo, "v1.2.1-canary.3", self.before, "f" * 40)
        with self.assertRaises(ValueError):
            generate(self.repo, "v1.2.1-canary.3", self.before, "--all")

    def test_ai_uses_the_confirmed_delivery_not_a_more_recent_release_tag(self):
        source = self.repo / "src/feature.txt"
        source.parent.mkdir()
        source.write_text("toggle_on\n", encoding="utf-8")
        self.git("add", "src/feature.txt")
        first = self.commit("feat: comments toggle")
        self.git("tag", "v9.0.0", first)
        source.write_text("toggle_on\nui_fix\n", encoding="utf-8")
        self.git("add", "src/feature.txt")
        current = self.commit("fix: comment display")
        with patch.object(llm_providers, "complete", return_value=payload([first, current])) as complete:
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before,
                                           config=CONFIG, slots=[SLOT])
        self.assertIn("槽位 2", result.source)
        self.assertEqual(self.before, result.previous_tag)
        self.assertEqual(2, result.commit_count)
        self.assertIn("优化了评论列表的显示效果。", result.changelog)
        self.assertIn("完整提交记录", result.changelog)
        self.assertIn("comments toggle", result.changelog)
        self.assertIn("comment display", result.changelog)
        self.assertIn("Canary 开发版", complete.call_args.args[1])
        self.assertIn("Telegram 更新公告", complete.call_args.args[1])
        self.assertIn(self.before, complete.call_args.args[2][0]["content"])
        self.assertIn("+toggle_on", complete.call_args.args[2][0]["content"])
        self.assertIn("+ui_fix", complete.call_args.args[2][0]["content"])

    def test_first_announcement_does_not_reset_to_an_existing_stable_tag(self):
        self.git("tag", "v1.2.0", self.before)
        current = self.commit("fix: comments")
        with patch.object(llm_providers, "complete", return_value=payload([self.before, current])):
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, config=CONFIG, slots=[SLOT])
        self.assertIn("槽位 2", result.source)
        self.assertIsNone(result.previous_tag)
        self.assertIn("首次发布", result.changelog)
        self.assertIn("previous published change", result.changelog)

    def test_failed_slot_uses_the_existing_next_slot(self):
        current = self.commit("fix: comments")
        first = llm_providers.ProviderSlot(1, "anthropic", "https://unavailable.invalid", "fixture", "secret")
        with patch.object(llm_providers, "complete", side_effect=[llm_providers.ProviderError("unavailable"),
                          payload([current])]) as complete:
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before,
                                           config=CONFIG, slots=[first, SLOT])
        self.assertEqual([1, 2], [call.args[0].index for call in complete.call_args_list])
        self.assertIn("槽位 2", result.source)

    def test_missing_coverage_or_failed_models_fall_back_to_the_entire_archive(self):
        first = self.commit("feat: first")
        current = self.commit("fix: second")
        for reply in [payload([current]), llm_providers.ProviderError("unavailable")]:
            with self.subTest(reply=type(reply).__name__), patch.object(llm_providers, "complete",
                    side_effect=reply if isinstance(reply, Exception) else None,
                    return_value=reply if isinstance(reply, str) else None):
                result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before,
                                               config=CONFIG, slots=[SLOT])
            self.assertEqual("规则版", result.source)
            self.assertEqual(generate(self.repo, "v1.2.1-canary.3", current, self.before), result.changelog)
            self.assertIn(first[:7], result.changelog)
            self.assertTrue(result.warnings)

    def test_large_range_reuses_shared_batches_and_keeps_all_commits(self):
        commits = [self.commit(f"chore: maintenance {index}") for index in range(45)]
        replies = [payload(commits[:40], items=False), payload(commits[40:], items=False), payload([], items=False)]
        with patch.object(llm_providers, "complete", side_effect=replies) as complete:
            result = generate_announcement(self.repo, "v1.2.1-canary.3", commits[-1], self.before,
                                           config=CONFIG, slots=[SLOT])
        self.assertIn("2 批，完整覆盖", result.source)
        self.assertEqual(3, complete.call_count)
        self.assertEqual(45, result.commit_count)
        for commit in commits:
            self.assertIn(commit[:7], result.changelog)

    def test_dry_run_empty_range_and_older_build_never_load_or_call_models(self):
        current = self.commit("fix: current")
        cases = [(current, self.before, False), (current, current, True), (self.before, current, True)]
        with patch.object(llm_providers, "load_slots") as slots, patch.object(llm_providers, "complete") as complete:
            for selected, before, enabled in cases:
                result = generate_announcement(self.repo, "v1.2.1-canary.3", selected, before, use_llm=enabled)
                self.assertEqual(generate(self.repo, "v1.2.1-canary.3", selected, before), result.changelog)
        slots.assert_not_called()
        complete.assert_not_called()

    def test_no_configured_model_preserves_the_existing_rules(self):
        current = self.commit("fix: comments")
        with patch.object(llm_providers, "complete") as complete:
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before, slots=[])
        complete.assert_not_called()
        self.assertEqual("规则版", result.source)
        self.assertTrue(result.warnings)

    def test_combined_budget_failure_keeps_the_complete_archive(self):
        current = self.commit("fix: comments")
        rule = generate(self.repo, "v1.2.1-canary.3", current, self.before)
        with patch.object(telegram_notes, "MAX_NOTES_BYTES", len(rule.encode("utf-8")) + 1), \
             patch.object(llm_providers, "complete", return_value=payload([current])):
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before,
                                           config=CONFIG, slots=[SLOT])
        self.assertEqual("规则版", result.source)
        self.assertEqual(rule, result.changelog)
        self.assertIn("预算", result.warnings[-1])

    def test_cli_dry_run_writes_notes_report_and_actions_summary_without_models(self):
        current = self.commit("fix: comments")
        output = self.repo / "output/notes.txt"
        report = self.repo / "output/report.md"
        summary = self.repo / "summary.md"
        arguments = ["prepare_telegram_notes.py", "--repo-root", str(self.repo), "--tag", "v1.2.1-canary.3",
                     "--commit", current, "--channel", "@example", "--output", str(output),
                     "--report-output", str(report), "--summary", str(summary), "--no-llm"]
        with patch.object(sys, "argv", arguments), patch.object(telegram_notes, "previous_commit", return_value=self.before), \
             patch.object(llm_providers, "load_slots") as slots, patch.object(llm_providers, "complete") as complete:
            telegram_notes.main()
        slots.assert_not_called()
        complete.assert_not_called()
        self.assertEqual(generate(self.repo, "v1.2.1-canary.3", current, self.before), output.read_text(encoding="utf-8"))
        self.assertIn("规则版", report.read_text(encoding="utf-8"))
        self.assertEqual(report.read_bytes(), summary.read_bytes())

    def test_only_eligible_telegram_delivery_uses_the_same_provider_configuration(self):
        workflows = Path(__file__).resolve().parents[1] / "workflows"
        telegram = (workflows / "canary-telegram.yml").read_text(encoding="utf-8")
        build = (workflows / "canary-build.yml").read_text(encoding="utf-8")
        begin = telegram.index("      - name: Generate complete notes since the last Telegram delivery")
        end = telegram.index("      - name: Send the selected Canary APK", begin)
        generation = telegram[begin:end]
        self.assertLess(telegram.index("      - name: Recheck daily publication policy"), begin)
        self.assertIn("if: steps.eligibility.outputs.should_publish == 'true'", generation)
        self.assertIn('if [[ "$DELIVERY_DRY_RUN" == "true" ]]', generation)
        self.assertIn("notes_args+=(--no-llm)", generation)
        self.assertIn('"${notes_args[@]}"', generation)
        self.assertIn("TELEGRAM_NOTES_REPORT.md", generation)
        settings = lambda text: {line.strip() for line in text.splitlines() if line.strip().startswith("RELEASE_NOTES_LLM_")}
        expected = settings((workflows / "stable-release.yml").read_text(encoding="utf-8"))
        self.assertEqual(21, len(expected))
        self.assertEqual(expected, settings(generation))
        self.assertEqual(expected, settings((workflows / "alpha-release.yml").read_text(encoding="utf-8")))
        self.assertNotIn("RELEASE_NOTES_LLM_", build)
        self.assertNotIn("prepare_telegram_notes.py", build)
        self.assertIn("Upload Telegram announcement and generation report", telegram)
