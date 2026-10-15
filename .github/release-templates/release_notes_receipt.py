"""Bind reusable, complete release classifications to their exact public inputs."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from release_context import run_git

PREFIX = "- 分类复用收据："
TOOLS = ("generate_release_notes.py", "refine_release_notes.py", "release_notes_receipt.py",
         "release_context.py", "release_note_common.py", "llm_providers.py",
         "generate_alpha_changelog.py", "generate_stable_changelog.py")


def digest(value) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def binding(context, config: dict, tools_dir: Path) -> dict:
    if context.repo_root is None:
        raise ValueError("Reusable evidence needs a repository identity")
    return {"channel": context.channel, "release_tag": context.release_tag,
            "source_commit": run_git(context.repo_root, "rev-parse", f"{context.commit}^{{commit}}").strip(),
            "baseline_tag": context.previous_tag,
            "baseline_commit": (run_git(context.repo_root, "rev-parse", f"{context.previous_tag}^{{commit}}").strip()
                                if context.previous_tag else None),
            "commits": [row.sha for row in context.commits],
            "baseline_orphaned": context.baseline_orphaned,
            "config_sha256": digest(config),
            "tools_sha256": {name: hashlib.sha256((tools_dir / name).read_bytes()).hexdigest() for name in TOOLS}}


def create(context, config: dict, tools_dir: Path, classified: dict) -> dict:
    known = context.commit_shas()
    if set(classified) != {"items", "maintenance"}:
        raise ValueError("Invalid classification receipt shape")
    covered = {sha for row in classified["items"] for sha in row["commits"]} | set(classified["maintenance"])
    if covered != known:
        raise ValueError("Classification receipt must cover the exact complete commit range")
    body = {"schema": 1, "complete": True, "binding": binding(context, config, tools_dir),
            "classified": classified}
    return {**body, "sha256": digest(body)}


def restore(report: str, context, config: dict, tools_dir: Path) -> tuple[dict, dict]:
    if len(report.encode("utf-8")) > 8 * 1024 * 1024:
        raise ValueError("Cached classification report is too large")
    rows = [json.loads(line[len(PREFIX):]) for line in report.splitlines() if line.startswith(PREFIX)]
    if len(rows) != 1:
        raise ValueError("Cached report needs exactly one complete classification receipt")
    receipt = rows[0]
    if not isinstance(receipt, dict) or set(receipt) != {"schema", "complete", "binding", "classified", "sha256"}:
        raise ValueError("Invalid classification receipt")
    if type(receipt["schema"]) is not int or receipt["schema"] != 1 or receipt["complete"] is not True:
        raise ValueError("Incomplete or unsupported classification receipt")
    body = {key: value for key, value in receipt.items() if key != "sha256"}
    if receipt["sha256"] != digest(body):
        raise ValueError("Cached classification receipt digest mismatch")
    if receipt["binding"] != binding(context, config, tools_dir):
        raise ValueError("Cached classification source/baseline/commits/config/tool identity mismatch")
    classified = receipt["classified"]
    # Rebuild also checks complete coverage before any paid refinement is attempted.
    if create(context, config, tools_dir, classified) != receipt:
        raise ValueError("Cached classification receipt changed")
    return classified, receipt
