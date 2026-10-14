#!/usr/bin/env python3
"""Replay already classified public evidence and regenerate only the bounded final notes."""
import argparse
import json
from pathlib import Path

import generate_release_notes as notes
import llm_providers
from release_context import build_context, load_config


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--channel", choices=["alpha", "stable"], required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    if not args.report.is_file():
        raise SystemExit("Cached report missing")
    report = args.report.read_text(encoding="utf-8")
    if f"Release notes · {args.release_tag} ({args.channel})" not in report:
        raise SystemExit("Cached report identity mismatch")
    prefix = "- 原始细分条目（完整复核证据）："
    rows = [json.loads(line[len(prefix):]) for line in report.splitlines() if line.startswith(prefix)]
    if len(rows) != 1:
        raise SystemExit("Cached report must contain exactly one complete original evidence list")
    original = notes.ReleaseNotes([], [notes.NoteItem(row["category"], row["group"], row["text"], row["commits"])
                                       for row in rows[0]], [])
    config = load_config(Path(__file__).resolve().parent)
    context = build_context(args.repo_root, config, channel=args.channel, release_tag=args.release_tag,
                            commit=args.commit, fetch_published=False)
    covered = {sha for item in original.items for sha in item.commits}
    if not covered.issubset(context.commit_shas()):
        raise SystemExit("Cached evidence references commits outside the selected release range")
    original.maintenance = sorted(context.commit_shas() - covered)
    slots, warnings = llm_providers.load_slots()
    log = [f"复用完整原始证据：{len(original.items)} 条；仅重跑归并与概述。"]
    refined = notes.limit_notes(original, context, config, slots, llm_providers.ProviderSession(), log)
    if refined is None:
        raise SystemExit("Final refinement failed; no incomplete announcement written")
    result = notes.GenerationResult(notes.render_changelog(refined, context, args.repository_url),
                                   "".join(refined.summary), "新来源小组归并（完整覆盖）", log, warnings,
                                   context.previous_tag, len(context.commits))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "CHANGELOG.md").write_text(result.changelog, encoding="utf-8")
    (args.output_dir / "SUMMARY.md").write_text(result.summary, encoding="utf-8")
    (args.output_dir / "REPORT.md").write_text(notes.render_report(result, args.channel, args.release_tag), encoding="utf-8")
    print(f"Refined {len(refined.items)} items; all {len(context.commits)} commits retained.")


if __name__ == "__main__":
    main()
