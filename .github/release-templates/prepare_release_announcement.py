#!/usr/bin/env python3
"""Keep complete announcements when cumulative notes exceed the Release body budget."""
from __future__ import annotations

import argparse
import re
import urllib.parse
from pathlib import Path


BODY_BUDGET = 120000
FULL_NOTES_NAME = "RELEASE_NOTES_FULL.md"
COMMIT_ARCHIVE = re.compile(r"<details>\s*<summary>📜 完整提交记录[^<]*</summary>.*?</details>", re.DOTALL)


def prepare_announcement(body: str, repository_url: str, tag: str, *, budget: int = BODY_BUDGET) -> tuple[str, str | None]:
    if len(body) <= budget:
        return body, None
    url = f"{repository_url.rstrip('/')}/releases/download/{urllib.parse.quote(tag, safe='')}/{FULL_NOTES_NAME}"
    notice = f"> 累计更新内容较多，[完整更新说明与全部提交记录]({url}) 保存在随版本发布的附件中，未裁掉更新内容。\n"
    reduced = COMMIT_ARCHIVE.sub(notice, body)
    if reduced == body or len(reduced) > budget:
        # 正文条目本身也超出预算时保留模板的标题/风险提示/下载反馈，正文全部写入附件。
        header = body.split("### ", 1)[0]
        marker = "\n---\n\n### 📦 下载"
        footer = marker + body.split(marker, 1)[1] if marker in body else ""
        reduced = header + "\n" + notice + footer
        if len(reduced) > budget:
            warning = ""
            if "-alpha." in tag:
                original_warning = re.search(r"(?m)^> \[!WARNING\]\n(?:>[^\n]*\n)*", body)
                warning = (original_warning.group(0) if original_warning else
                           "> [!WARNING]\n> 这是预发布测试版本，可能存在未知问题；普通用户请优先使用 Stable。\n")
            reduced = f"## {tag}\n\n" + warning + "\n" + notice
    if len(reduced) > budget:
        raise ValueError("Announcement wrapper exceeds the body budget")
    return reduced, body


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--notes-file", type=Path, required=True)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--release-tag", required=True)
    args = parser.parse_args()
    body = args.notes_file.read_text(encoding="utf-8")
    public, complete = prepare_announcement(body, args.repository_url, args.release_tag)
    if complete is not None:
        args.notes_file.with_name(FULL_NOTES_NAME).write_text(complete, encoding="utf-8", newline="\n")
        args.notes_file.write_text(public, encoding="utf-8", newline="\n")
        print("Complete announcement saved as RELEASE_NOTES_FULL.md; publish this asset with its checksum.")


if __name__ == "__main__":
    main()
