#!/usr/bin/env python3
"""Generate the Alpha Release changelog from reachable version tags and commits."""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path

from release_context import list_commits, resolve_baseline, tag_commit_timestamp
from release_note_common import (
    escape_markdown_text,
    is_build_maintenance,
    translate_subject,
)


ALPHA_TAG_PATTERN = re.compile(r"^v(\d+)\.(\d+)\.(\d+)-alpha\.(\d+)$")


def parse_alpha_tag(tag: str) -> tuple[int, int, int, int] | None:
    match = ALPHA_TAG_PATTERN.fullmatch(tag)
    if match is None:
        return None
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def run_git(repo_root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=repo_root,
        check=check,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def find_previous_alpha(repo_root: Path, commit: str, release_tag: str) -> str | None:
    # 保留旧入口名；两个发布通道现在均使用统一基线选择。
    return resolve_baseline(repo_root, commit, release_tag, "alpha")[0]


def build_changelog(
    repo_root: Path,
    repository_url: str,
    release_tag: str,
    commit: str,
) -> str:
    previous_alpha, orphaned = resolve_baseline(repo_root, commit, release_tag, "alpha")
    short_commit = commit[:7]
    repository_url = repository_url.rstrip("/")
    if previous_alpha is None:
        return (
            "- 首个可追溯的 Alpha 构建，包含截至 "
            f"[`{short_commit}`]({repository_url}/commit/{commit}) 的代码。"
        )

    diff_result = run_git(repo_root, "diff", "--quiet", previous_alpha, commit, check=False)
    if diff_result.returncode not in (0, 1):
        raise subprocess.CalledProcessError(
            diff_result.returncode,
            diff_result.args,
            output=diff_result.stdout,
            stderr=diff_result.stderr,
        )
    if diff_result.returncode == 0:
        return (
            f"- 与上一发布（`{previous_alpha}`）使用相同目标代码，"
            "本次主要验证构建与发布链路。"
        )

    commits = list_commits(repo_root, f"{previous_alpha}..{commit}",
                           since=tag_commit_timestamp(repo_root, previous_alpha) if orphaned else None)
    entries: list[str] = []
    seen_subjects: set[str] = set()
    for info in commits:
        commit_sha, subject = info.sha, info.subject
        # 连续文档修订等常产生相同标题；Release 正文只展示一次，完整差异链接仍保留全部提交。
        if subject in seen_subjects:
            continue
        # 剔除纯构建/维护/文档类条目，普通用户无感知；完整差异链接仍保留全部提交。
        seen_subjects.add(subject)
        if is_build_maintenance(subject):
            continue
        # 翻译术语化标题，让测试者一眼看到「改了什么类别」；无法翻译时保留原标题。
        rendered_subject = translate_subject(subject) or subject
        entries.append(
            f"- {escape_markdown_text(rendered_subject)} "
            f"([`{commit_sha[:7]}`]({repository_url}/commit/{commit_sha}))"
        )
    if not entries:
        entries.append("- 未检测到可列出的提交标题，请结合完整差异查看本次代码调整。")
    entries.extend(["", "<details>", f"<summary>📜 完整提交记录（{len(commits)}）</summary>", ""])
    entries.extend(f"- {escape_markdown_text(info.subject)} ([`{info.sha[:7]}`]({repository_url}/commit/{info.sha}))"
                   for info in commits)
    entries.extend(["", "</details>"])
    entries.extend(
        [
            "",
            f"- [查看从 `{previous_alpha}` 到本次提交的完整差异]"
            f"({repository_url}/compare/{previous_alpha}...{commit})",
        ]
    )
    return "\n".join(entries)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    if parse_alpha_tag(args.release_tag) is None:
        raise SystemExit(f"Invalid Alpha tag: {args.release_tag}")

    changelog = build_changelog(
        args.repo_root.resolve(),
        args.repository_url,
        args.release_tag,
        args.commit,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(changelog.rstrip() + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
