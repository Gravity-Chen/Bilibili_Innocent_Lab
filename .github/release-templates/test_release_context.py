#!/usr/bin/env python3
"""Tests for release_context.py (standard library only).

主场景：历史重写（filter-branch 等）后旧发布 tag 仍指向不可达提交，
生成器必须把它识别为孤儿基线——净 diff 仍用它做基，提交记录按日期收敛，
而不是把「无基线」当作首次发布去总结整个历史。
"""

from __future__ import annotations

import subprocess
import tempfile
import unittest
from unittest import mock
from pathlib import Path

from release_context import (
    build_context,
    find_orphaned_tag,
    find_previous_tag,
    tag_commit_timestamp,
    resolve_baseline,
)
import generate_alpha_changelog
import generate_stable_changelog


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    ).stdout


def _commit(repo: Path, name: str, content: str, message: str) -> str:
    (repo / name).write_text(content, encoding="utf-8")
    _git(repo, "add", name)
    _git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", message, "-q")
    return _git(repo, "rev-parse", "HEAD").strip()


class OrphanBaselineTests(unittest.TestCase):
    """git checkout --orphan 重建历史，模拟 filter-branch 后 tag 悬空。"""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        _git(self.repo, "init", "-q")
        _git(self.repo, "-c", "user.name=t", "-c", "user.email=t", "commit",
             "--allow-empty", "-m", "init", "-q")
        # 旧历史：发布基线 tag + 一些提交
        _commit(self.repo, "a.txt", "v1", "feature A")
        self.old_tip = _commit(self.repo, "b.txt", "v1", "feature B")
        _git(self.repo, "tag", "v1.0.0", self.old_tip)
        # 重写历史：orphan 分支上全新提交（与旧历史不可达）
        _git(self.repo, "checkout", "--orphan", "rewritten", "-q")
        _git(self.repo, "rm", "-rf", ".", "-q")
        _commit(self.repo, "a.txt", "v1-same", "feature A")
        _commit(self.repo, "b.txt", "v1-same", "feature B")
        _commit(self.repo, "c.txt", "v2", "feature C (new release delta)")
        self.head = _git(self.repo, "rev-parse", "HEAD").strip()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_merged_lookup_finds_nothing_after_rewrite(self) -> None:
        self.assertIsNone(
            find_previous_tag(self.repo, self.head, "v1.1.0", "stable")
        )

    def test_orphaned_tag_is_found(self) -> None:
        self.assertEqual(
            find_orphaned_tag(self.repo, self.head, "v1.1.0", "stable"),
            "v1.0.0",
        )

    def test_context_uses_orphan_as_diff_base(self) -> None:
        context = build_context(
            self.repo,
            {},
            channel="stable",
            release_tag="v1.1.0",
            commit=self.head,
            fetch_published=False,
        )
        self.assertTrue(context.baseline_orphaned)
        self.assertEqual(context.previous_tag, "v1.0.0")
        # 提交记录按 tag 日期收敛——orphan 历史里的 feature C 必须在，
        # 且整体不得退回「全部历史」语义（diff 基是 tag 树而非空树）。
        subjects = [c.subject for c in context.commits]
        self.assertIn("feature C (new release delta)", subjects)

    def test_normal_case_unaffected(self) -> None:
        # 无重写的正常仓库：tag 可达 → 非孤儿路径。
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            _git(repo, "init", "-q")
            _commit(repo, "a.txt", "1", "base")
            _git(repo, "tag", "v1.0.0")
            head = _commit(repo, "b.txt", "2", "delta")
            context = build_context(
                repo,
                {},
                channel="stable",
                release_tag="v1.1.0",
                commit=head,
                fetch_published=False,
            )
            self.assertFalse(context.baseline_orphaned)
            self.assertEqual(context.previous_tag, "v1.0.0")
            self.assertEqual([c.subject for c in context.commits], ["delta"])

    def test_tag_commit_timestamp_format(self) -> None:
        # ISO 8601 带时分秒：裸日期喂给 --since 会被 approxidate 补成
        # 「今天此刻」，把当天早些时候造的提交全部过滤掉（CI 上曾因此
        # 拿到空提交列表）。
        ts = tag_commit_timestamp(self.repo, "v1.0.0")
        self.assertRegex(ts, r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}")

    def test_newer_orphan_stable_supersedes_older_reachable_alpha(self) -> None:
        _git(self.repo, "tag", "v0.9.0-alpha.1", self.head)
        self.assertEqual(("v1.0.0", True), resolve_baseline(self.repo, self.head, "v1.1.0-alpha.1", "alpha"))


class CrossChannelBaselineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        _git(self.repo, "init", "-q")
        _commit(self.repo, "a.txt", "old", "feat: old alpha feature")
        _git(self.repo, "tag", "v1.1.8-alpha.2")
        _commit(self.repo, "a.txt", "stable", "feat: already stable")
        _git(self.repo, "tag", "v1.2.0")
        self.delta = _commit(self.repo, "b.txt", "new", "feat: current delta")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_alpha_starts_at_newer_stable_in_ai_and_rules(self) -> None:
        context = build_context(self.repo, {}, channel="alpha", release_tag="v1.2.1-alpha.1",
                                commit=self.delta, fetch_published=False)
        self.assertEqual("v1.2.0", context.previous_tag)
        self.assertEqual([self.delta], [info.sha for info in context.commits])
        text = generate_alpha_changelog.build_changelog(self.repo, "https://github.com/o/r", "v1.2.1-alpha.1", self.delta)
        self.assertIn("v1.2.0...", text)
        self.assertNotIn("already stable", text)
        self.assertNotIn("old alpha feature", text)

    def test_stable_and_alpha_start_at_previous_alpha(self) -> None:
        _git(self.repo, "tag", "v1.2.1-alpha.1", self.delta)
        head = _commit(self.repo, "c.txt", "stable delta", "fix: after alpha")
        for channel, tag in (("stable", "v1.2.1"), ("alpha", "v1.2.1-alpha.2")):
            with self.subTest(channel=channel):
                context = build_context(self.repo, {}, channel=channel, release_tag=tag,
                                        commit=head, fetch_published=False)
                self.assertEqual("v1.2.1-alpha.1", context.previous_tag)
                self.assertEqual([head], [info.sha for info in context.commits])
        text = generate_stable_changelog.build_changelog(self.repo, "https://github.com/o/r", "v1.2.1", head)
        self.assertIn("v1.2.1-alpha.1...", text)
        self.assertNotIn("current delta", text)

    def test_current_future_and_unreachable_tags_do_not_enter_reachable_range(self) -> None:
        _git(self.repo, "tag", "v1.2.1-alpha.2", self.delta)
        _git(self.repo, "tag", "v1.2.1", self.delta)
        _git(self.repo, "tag", "v9.0.0", self.delta)
        self.assertEqual("v1.2.0", find_previous_tag(self.repo, self.delta, "v1.2.1-alpha.1", "alpha"))

    def test_many_long_commits_keep_recent_identifiers_in_context(self) -> None:
        from release_context import CommitInfo
        commits = [CommitInfo(f"{index:040x}", "2026-10-14", "长标题" * 80, "长正文" * 200, 1, 1, 1, ())
                   for index in range(1, 333)]
        with mock.patch("release_context.list_commits", return_value=commits):
            context = build_context(self.repo, {}, channel="alpha", release_tag="v1.2.1-alpha.1",
                                    commit=self.delta, fetch_published=False)
        self.assertTrue(context.commit_index_complete)
        index_text = dict(context.sections)["完整提交索引（时间正序；每个 SHA 必须归类）"]
        self.assertEqual(332, len(index_text.splitlines()))


if __name__ == "__main__":
    unittest.main()
