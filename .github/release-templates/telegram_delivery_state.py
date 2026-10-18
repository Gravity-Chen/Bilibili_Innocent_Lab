#!/usr/bin/env python3
"""Keep the last confirmed Telegram source separate from Canary build history."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from datetime import datetime
from pathlib import Path

REPOSITORY = "jichuo1/Bilibili_Innocent_Lab"
ENVIRONMENT = "canary-telegram"
TASK = "canary-telegram"
SEND_STEP = "Send the selected Canary APK to Telegram"
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
MAX_PAGES = 20


def github(path: str, payload: dict | None = None, *, raw: bool = False, run_id: int = 0, attempt: int = 0):
    command = ["gh", "api", f"repos/{REPOSITORY}/{path}"]
    if raw:
        match = re.fullmatch(r"actions/jobs/([1-9]\d*)/logs", path)
        if match is None or payload is not None or type(run_id) is not int or run_id <= 0:
            raise ValueError("Unexpected Telegram job log request")
        # gh api 会拒绝输出日志里的 ANSI 控制字符；run view 专门处理 Actions 日志。
        # 显式提供 run ID 才能保留步骤名称；仅 --job 会把步骤标为 UNKNOWN STEP。
        command = ["gh", "run", "view", str(run_id), "--repo", REPOSITORY, "--job", match[1], "--log"]
        if attempt:
            command += ["--attempt", str(attempt)]
    if payload is not None:
        command += ["--method", "POST", "--input", "-"]
    result = subprocess.run(command, input=json.dumps(payload).encode() if payload is not None else None,
                            capture_output=True, timeout=60)
    if result.returncode:
        raise ValueError("Cannot read or record Telegram delivery history: " + path.split("?")[0])
    if len(result.stdout) > (8 * 1024 * 1024 if raw else 1024 * 1024):
        raise ValueError("Telegram delivery history exceeds the inspection budget")
    return result.stdout.decode("utf-8") if raw else json.loads(result.stdout)


def channel_key(channel: str) -> str:
    if not re.fullmatch(r"@[A-Za-z][A-Za-z0-9_]{4,31}|-100\d+", channel):
        raise ValueError("Invalid Telegram channel ID")
    return channel.lower()


def confirmed_receipt(payload: dict) -> bool:
    messages = payload.get("announcement_message_ids")
    return (isinstance(payload.get("source_commit"), str) and SHA.fullmatch(payload["source_commit"]) is not None
            and payload.get("delivery_complete") is True
            and type(payload.get("document_message_id")) is int and payload["document_message_id"] > 0
            and isinstance(messages, list) and bool(messages)
            and all(type(identity) is int and identity > 0 for identity in messages))


def previous_commit(channel: str) -> str:
    return previous_delivery(channel).get("source_commit", "")


def instant(value: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError("Invalid Telegram delivery time")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Telegram delivery time requires an explicit timezone")
    return parsed


def previous_delivery(channel: str, *, strict: bool = False, active_run: dict | None = None) -> dict:
    channel = channel_key(channel)
    checkpoints = []
    for page in range(1, MAX_PAGES + 1):
        deployments = github(f"deployments?environment={ENVIRONMENT}&task={TASK}&per_page=100&page={page}")
        for deployment in sorted(deployments, key=lambda row: row["id"], reverse=True):
            payload = deployment.get("payload", {})
            if isinstance(payload, str):
                payload = json.loads(payload)
            if (deployment.get("environment") != ENVIRONMENT or deployment.get("task") != TASK
                or not isinstance(payload, dict) or payload.get("schema") != 1
                or payload.get("channel") != channel):
                continue
            if payload.get("delivery_complete") is not True:
                continue
            commit = payload.get("source_commit", "")
            if not confirmed_receipt(payload) or deployment.get("sha") != commit:
                raise ValueError("Invalid Telegram delivery checkpoint")
            # 回执只在所有 TG 消息成功后创建；status 写入失败也不能抹掉真实投递。
            # 同时检查较新的运行，恢复 TG 成功但 deployment 创建失败的情况。
            delivery_url = payload.get("delivery_run_url", "")
            match = re.fullmatch(r"https://github\.com/" + re.escape(REPOSITORY) + r"/actions/runs/([1-9]\d*)", delivery_url)
            if match is None:
                raise ValueError("Telegram checkpoint is missing its delivery run")
            receipt = dict(payload, _run_id=int(match[1]), _checkpoint_id=deployment["id"],
                           _created_at=deployment.get("created_at", ""))
            if receipt.get("delivered_at"):
                instant(receipt["delivered_at"])
            if "delivery_run_attempt" in receipt and (type(receipt["delivery_run_attempt"]) is not int
                    or not 1 <= receipt["delivery_run_attempt"] <= 100):
                raise ValueError("Invalid Telegram checkpoint attempt")
            checkpoints.append(receipt)
        if len(deployments) < 100:
            break
    else:
        raise ValueError("Telegram checkpoint history exceeds the inspection budget")
    return delivery_history(channel, checkpoints, strict=strict, active_run=active_run)


def legacy_previous_commit(channel: str, checkpoint_run: int = 0, fallback: str = "") -> str:
    receipt = {"source_commit": fallback} if fallback else {}
    return legacy_previous_delivery(channel, checkpoint_run, receipt).get("source_commit", "")


def legacy_previous_delivery(channel: str, checkpoint_run: int = 0, fallback: dict | None = None) -> dict:
    checkpoints = [dict(fallback or {}, _run_id=checkpoint_run, _legacy_boundary=True)] if checkpoint_run or fallback else []
    return delivery_history(channel_key(channel), checkpoints)


def delivery_history(channel: str, checkpoints: list[dict], *, strict: bool = False,
                     active_run: dict | None = None) -> dict:
    confirmed = list(checkpoints)
    uncertain = []
    known = {(row.get("_run_id"), row.get("delivery_run_attempt", 1)): row for row in checkpoints}
    cutoff = max((instant(row["delivered_at"]) for row in confirmed if row.get("delivered_at")), default=None)

    def inspect(run: dict, *, active: bool = False) -> None:
        nonlocal cutoff
        if (run.get("path") != ".github/workflows/canary-telegram.yml" or run.get("head_branch") != "main"
                or run.get("event") not in {"workflow_dispatch", "schedule"}):
            return
        attempts = run.get("run_attempt", 1)
        if type(attempts) is not int or not 1 <= attempts <= 100:
            raise ValueError("Telegram delivery attempt history exceeds the inspection budget")
        if active and attempts == 1:
            return
        covered = [row for (identity, _), row in known.items() if identity == run["id"]]
        # updated_at is only an upper bound for skipping old runs, never a delivery timestamp.
        if (cutoff and run.get("updated_at") and instant(run["updated_at"]) < cutoff
                and not any(not row.get("delivered_at") for row in covered)):
            return
        if attempts == 1 and covered and (not strict or all(row.get("delivered_at") for row in covered)):
            return
        jobs = github(f"actions/runs/{run['id']}/jobs?filter=all&per_page=100")
        if jobs.get("total_count", len(jobs["jobs"])) > 100:
            raise ValueError("Telegram delivery job history exceeds the inspection budget")
        for stored in covered:
            if stored.get("delivered_at") or not strict:
                continue
            # The complete checkpoint already proves delivery. Recover only its exact send time,
            # not another success from whole-job logs (some gh/platform versions label all UNKNOWN STEP).
            candidates = [(job, step) for job in jobs["jobs"]
                          if job.get("name") == "Verify selected Canary and send its APK"
                          and job.get("run_id") == run["id"]
                          and type(job.get("run_attempt")) is int and 1 <= job["run_attempt"] <= attempts
                          and ("delivery_run_attempt" not in stored or job["run_attempt"] == stored["delivery_run_attempt"])
                          for step in job.get("steps", []) if step.get("name") == SEND_STEP
                          and step.get("conclusion") == "success" and type(step.get("number")) is int
                          and step["number"] > 0 and step.get("started_at") and step.get("completed_at")]
            if len(candidates) != 1:
                raise ValueError("Unconfirmed Telegram delivery: checkpoint send attempt is not unique")
            job, step = candidates[0]
            if (sum(row.get("name") == job["name"] and row.get("run_id") == run["id"]
                    and row.get("run_attempt") == job["run_attempt"] for row in jobs["jobs"]) != 1
                    or sum(row.get("name") == SEND_STEP for row in job.get("steps", [])) != 1):
                raise ValueError("Unconfirmed Telegram delivery: checkpoint target job or step is not unique")
            completed = instant(step["completed_at"])
            if completed < instant(step["started_at"]):
                raise ValueError("Invalid Telegram send step time")
            stored.update(delivered_at=step["completed_at"], delivery_run_attempt=job["run_attempt"])
            known[(run["id"], job["run_attempt"])] = stored
            if cutoff is None or completed > cutoff:
                cutoff = completed
        for job in jobs["jobs"]:
            if job.get("name") != "Verify selected Canary and send its APK":
                continue
            attempt = job.get("run_attempt", 1)
            if type(attempt) is not int or not 1 <= attempt <= attempts or job.get("run_id", run["id"]) != run["id"]:
                raise ValueError("Cannot verify Telegram delivery attempt identity")
            if active and attempt == attempts:
                continue  # This policy check precedes the current attempt's send step.
            checkpoint = known.get((run["id"], attempt))
            if checkpoint and (checkpoint.get("delivered_at") or not strict):
                continue
            steps = [step for step in job.get("steps", []) if step.get("name") == SEND_STEP]
            if len(steps) > 1:
                raise ValueError("Ambiguous Telegram send step")
            step = steps[0] if steps else {}
            if step.get("conclusion") == "skipped" or (steps and not step.get("started_at")):
                continue
            if cutoff and step.get("completed_at") and instant(step["completed_at"]) < cutoff:
                continue
            job_log = github(f"actions/jobs/{job['id']}/logs", raw=True, run_id=run["id"], attempt=attempt)
            log = "\n".join(parts[2] for line in job_log.splitlines()
                            if len(parts := line.split("\t", 2)) == 3 and parts[1] == SEND_STEP)
            if not log:
                if strict:
                    raise ValueError("Unconfirmed Telegram delivery: actual send log unavailable")
                continue
            dry_run = re.search(r"Validated Telegram payload.*dry-run only", log) or re.search(
                r"\bDELIVERY_DRY_RUN: true\s*$", log, re.MULTILINE)
            if dry_run:
                continue
            success = re.search(r"Telegram delivered Canary APK, message ID ([1-9]\d*)", log)
            posted = re.search(r"\bTELEGRAM_POST_ATTEMPT: (?:sendDocument|sendMessage)\s*$", log, re.MULTILINE)
            protocol = re.search(r"\bTELEGRAM_DELIVERY_PROTOCOL: 2\s*$", log, re.MULTILINE)
            legacy_unknown = not protocol and re.search(
                r"Telegram delivery failed:.*(?:network/protocol failure|announcement|did not confirm|rejected sendMessage)", log)
            if not success and (not strict or not (posted or legacy_unknown)):
                continue
            commits = set(re.findall(r"\bSOURCE_COMMIT: ([0-9a-f]{40})\s*$", log, re.MULTILINE))
            channels = set(re.findall(r"\bTELEGRAM_CHAT_ID: (\S+)\s*$", log, re.MULTILINE))
            if len(channels) != 1 or len(commits) != 1:
                raise ValueError("Cannot identify the source of a confirmed or unconfirmed Telegram delivery")
            if channel_key(channels.pop()) != channel:
                continue
            marker = r"Telegram delivered Canary APK, message ID [1-9]\d*" if success else r"TELEGRAM_POST_ATTEMPT: (?:sendDocument|sendMessage)"
            times = re.findall(r"(\d{4}-\d\d-\d\dT\S+Z) " + marker, log)
            completed = times[-1] if times else step.get("completed_at", "")
            if not completed and not success:
                # A failure line is an upper bound within the actual send step, not a confirmed delivery.
                stamps = re.findall(r"\b\d{4}-\d\d-\d\dT[^\s]+Z\b", log)
                completed = max(stamps, key=instant, default="")
            if not completed:
                raise ValueError("Unconfirmed Telegram delivery: actual send time unavailable")
            instant(completed)
            receipt = {"source_commit": commits.pop(), "delivered_at": completed, "_run_id": run["id"],
                       "delivery_run_attempt": attempt}
            if success:
                receipt["document_message_id"] = int(success[1])
                for stored in checkpoints:
                    if (stored.get("_run_id") == run["id"] and stored.get("source_commit") == receipt["source_commit"]
                            and stored.get("document_message_id") == receipt["document_message_id"]):
                        stored.update(delivered_at=completed, delivery_run_attempt=attempt)
            for key, label in [("source_run_url", "SOURCE_RUN_URL"), ("source_artifact_digest", "SOURCE_ARTIFACT_DIGEST")]:
                values = set(re.findall(r"\b" + label + r": (\S+)\s*$", log, re.MULTILINE))
                if len(values) > 1:
                    raise ValueError("Ambiguous legacy Telegram build identity")
                if values:
                    receipt[key] = values.pop()
            (confirmed if success else uncertain).append(receipt)
            if success and (cutoff is None or instant(completed) > cutoff):
                cutoff = instant(completed)

    seen = set()
    if active_run is not None:
        inspect(active_run, active=True)
        seen.add(active_run["id"])
    for page in range(1, MAX_PAGES + 1):
        result = github(f"actions/workflows/canary-telegram.yml/runs?status=completed&per_page=100&page={page}")
        runs = result["workflow_runs"]
        for run in runs:
            if run["id"] not in seen:
                inspect(run)
                seen.add(run["id"])
        if len(runs) < 100:
            break
    else:
        raise ValueError("Legacy Telegram history exceeds the inspection budget")
    dated = [row for row in confirmed if row.get("delivered_at")]
    latest = max(dated, key=lambda row: (instant(row["delivered_at"]), row.get("delivery_run_attempt", 1)), default=None)
    if strict:
        for row in confirmed:
            if not row.get("delivered_at") and (latest is None or not row.get("_created_at")
                    or instant(row["_created_at"]) >= instant(latest["delivered_at"])):
                raise ValueError("Unconfirmed Telegram delivery: checkpoint lacks an actual send time")
        if any(latest is None or instant(row["delivered_at"]) >= instant(latest["delivered_at"]) for row in uncertain):
            raise ValueError("Unconfirmed Telegram delivery: check the channel before automatic retry")
    # Old checkpoints remain usable for explicit manual notes even when old logs have expired.
    undated = [row for row in confirmed if not row.get("delivered_at")]
    fallback = max(undated, key=lambda row: row.get("_checkpoint_id", 0), default=None)
    legacy_boundary = (fallback and fallback.get("_legacy_boundary") and latest
                       and latest.get("_run_id", 0) <= fallback.get("_run_id", 0))
    # An explicit untimed legacy checkpoint cannot be replaced by an older/same-run attempt.
    if not strict and fallback and (latest is None or legacy_boundary or (fallback.get("_created_at")
            and instant(fallback["_created_at"]) >= instant(latest["delivered_at"]))):
        latest = fallback
    return {key: value for key, value in (latest or {}).items() if not key.startswith("_")}


def record_delivery(receipt: dict, delivery_url: str, *, artifact_digest: str = "", attempt: int = 1) -> int:
    commit = receipt.get("source_commit", "")
    if (not confirmed_receipt(receipt)
        or not re.fullmatch(r"https://github\.com/" + re.escape(REPOSITORY) + r"/actions/runs/[1-9]\d*", delivery_url)):
        raise ValueError("Invalid confirmed Telegram delivery receipt")
    payload = dict(receipt, schema=1, channel=channel_key(receipt["channel"]), delivery_run_url=delivery_url)
    if artifact_digest:
        if not DIGEST.fullmatch(artifact_digest):
            raise ValueError("Invalid Telegram source artifact digest")
        payload["source_artifact_digest"] = artifact_digest
    if type(attempt) is not int or not 1 <= attempt <= 100:
        raise ValueError("Invalid Telegram delivery attempt")
    run_id = int(delivery_url.rsplit("/", 1)[1])
    jobs = github(f"actions/runs/{run_id}/jobs?filter=all&per_page=100")
    if jobs.get("total_count", len(jobs["jobs"])) > 100:
        raise ValueError("Telegram delivery job history exceeds the inspection budget")
    steps = [step for job in jobs["jobs"] if job.get("name") == "Verify selected Canary and send its APK"
             and job.get("run_attempt", 1) == attempt and job.get("run_id", run_id) == run_id
             for step in job.get("steps", []) if step.get("name") == SEND_STEP and step.get("conclusion") == "success"]
    if len(steps) != 1 or not steps[0].get("completed_at"):
        raise ValueError("Cannot identify the actual Telegram send completion time")
    instant(steps[0]["completed_at"])
    payload.update(delivered_at=steps[0]["completed_at"], delivery_run_attempt=attempt)
    deployment = github("deployments", {
        "ref": commit, "task": TASK, "environment": ENVIRONMENT,
        "auto_merge": False, "required_contexts": [], "production_environment": False,
        "description": "Confirmed Canary Telegram delivery", "payload": payload,
    })
    if type(deployment.get("id")) is not int or deployment.get("sha") != commit:
        raise ValueError("GitHub did not record the selected Telegram source")
    github(f"deployments/{deployment['id']}/statuses", {
        "state": "success", "auto_inactive": False, "log_url": delivery_url,
        "description": f"Telegram message {receipt['document_message_id']}; complete announcement delivered",
    })
    return deployment["id"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--delivery-url", required=True)
    parser.add_argument("--artifact-digest", default="")
    parser.add_argument("--attempt", type=int, default=int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")))
    args = parser.parse_args()
    identity = record_delivery(json.loads(args.receipt.read_text(encoding="utf-8")), args.delivery_url,
                               artifact_digest=args.artifact_digest, attempt=args.attempt)
    print(f"Recorded confirmed Telegram delivery checkpoint {identity}")


if __name__ == "__main__":
    main()
