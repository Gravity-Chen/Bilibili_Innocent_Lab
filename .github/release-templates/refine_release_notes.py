#!/usr/bin/env python3
"""Replay already classified public evidence and regenerate only the bounded final notes."""
import argparse
from pathlib import Path

import generate_release_notes as notes
import llm_providers
import release_notes_receipt
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
    if not args.report.is_file() or args.report.stat().st_size > 8 * 1024 * 1024:
        raise SystemExit("Cached report missing or oversized")
    report = args.report.read_text(encoding="utf-8")
    if f"Release notes · {args.release_tag} ({args.channel})" not in report:
        raise SystemExit("Cached report identity mismatch")
    config = load_config(Path(__file__).resolve().parent)
    context = build_context(args.repo_root, config, channel=args.channel, release_tag=args.release_tag,
                            commit=args.commit, fetch_published=False)
    try:
        classified, receipt = release_notes_receipt.restore(report, context, config, Path(__file__).resolve().parent)
    except (ValueError, KeyError, TypeError) as error:
        raise SystemExit(str(error)) from error
    original, errors, _ = notes.validate_notes(
        {"summary": [], **classified}, notes.replace(context, channel="alpha"),
        {**config, "max_items": max(len(classified["items"]), int(config.get("max_items", 100)))})
    if original is None:
        raise SystemExit("Cached classifications failed validation: " + "; ".join(errors))
    slots, warnings = llm_providers.load_slots()
    log = [f"复用完整原始证据：{len(original.items)} 条；仅重跑归并与概述。"]
    refined = notes.limit_notes(original, context, config, slots, llm_providers.ProviderSession(), log)
    if refined is None:
        args.output_dir.mkdir(parents=True, exist_ok=True)
        (args.output_dir / "REPORT.md").write_text("\n".join(log), encoding="utf-8")
        for entry in log:
            if not entry.startswith("原始细分条目"):
                print(entry)
        raise SystemExit("Final refinement failed; no incomplete announcement written")
    if len(original.items) <= int(config.get("max_items", 100)):
        refined.summary = notes._generate_overview(refined, context, config, slots, llm_providers.ProviderSession(), log)
    refined, errors, _ = notes.validate_notes(
        {"summary": refined.summary, "items": [vars(item) for item in refined.items],
         "maintenance": refined.maintenance}, context, config)
    if refined is None:
        raise SystemExit("Refined announcement failed validation: " + "; ".join(errors))
    result = notes.GenerationResult(notes.render_changelog(refined, context, args.repository_url),
                                   "".join(refined.summary), "新来源小组归并（完整覆盖）", log, warnings,
                                   context.previous_tag, len(context.commits), receipt)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "CHANGELOG.md").write_text(result.changelog, encoding="utf-8")
    (args.output_dir / "SUMMARY.md").write_text(result.summary, encoding="utf-8")
    (args.output_dir / "REPORT.md").write_text(notes.render_report(result, args.channel, args.release_tag), encoding="utf-8")
    print(f"Refined {len(refined.items)} items; all {len(context.commits)} commits retained.")


if __name__ == "__main__":
    main()
