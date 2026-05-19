#!/usr/bin/env python3
"""List files changed by the `patch` field in a SWE-bench JSONL dataset."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path
from typing import Any


def normalize_diff_path(path: str) -> str | None:
    if path == "/dev/null":
        return None
    if path.startswith("a/") or path.startswith("b/"):
        return path[2:]
    return path


def files_from_patch(patch: str) -> list[str]:
    files: list[str] = []

    def add(path: str | None) -> None:
        if path and path not in files:
            files.append(path)

    for line in patch.splitlines():
        if line.startswith("diff --git "):
            parts = line.split()
            if len(parts) >= 4:
                add(normalize_diff_path(parts[2]))
                add(normalize_diff_path(parts[3]))
        elif line.startswith("--- ") or line.startswith("+++ "):
            parts = line.split(maxsplit=1)
            if len(parts) == 2:
                add(normalize_diff_path(parts[1].split("\t", 1)[0]))

    return files


def load_rows(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as fh:
        for line_number, line in enumerate(fh, start=1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_number}: invalid JSON: {exc}") from exc
    return rows


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Print files changed by the `patch` column in a SWE-bench JSONL dataset.",
    )
    parser.add_argument(
        "dataset",
        nargs="?",
        default="swe-bench-verified-mini.jsonl",
        help="Path to SWE-bench JSONL dataset.",
    )
    parser.add_argument(
        "--format",
        choices=["text", "csv", "jsonl"],
        default="text",
        help="Output format.",
    )
    parser.add_argument(
        "-o",
        "--output",
        help="Write output to this file instead of stdout.",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    dataset_path = Path(args.dataset)

    rows = load_rows(dataset_path)
    records = [
        {
            "instance_id": row.get("instance_id", ""),
            "repo": row.get("repo", ""),
            "files": files_from_patch(row.get("patch", "")),
        }
        for row in rows
    ]

    output_fh = open(args.output, "w", encoding="utf-8", newline="") if args.output else sys.stdout
    try:
        return write_records(records, args.format, output_fh)
    finally:
        if args.output:
            output_fh.close()


def write_records(records: list[dict[str, Any]], output_format: str, output_fh: Any) -> int:
    if output_format == "jsonl":
        for record in records:
            print(json.dumps(record, ensure_ascii=False, sort_keys=True), file=output_fh)
        return 0

    if output_format == "csv":
        writer = csv.DictWriter(output_fh, fieldnames=["instance_id", "repo", "files"])
        writer.writeheader()
        for record in records:
            writer.writerow(
                {
                    "instance_id": record["instance_id"],
                    "repo": record["repo"],
                    "files": ";".join(record["files"]),
                },
            )
        return 0

    for record in records:
        print(f"{record['instance_id']} ({record['repo']})", file=output_fh)
        for path in record["files"]:
            print(f"  {path}", file=output_fh)
        if not record["files"]:
            print("  <no files>", file=output_fh)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
