#!/usr/bin/env python3
"""Repair comment insertions from existing repos-explained artifacts."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from explain_django_tasks import insert_high_detail_comments, save_json


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8", errors="replace"))


def file_entries_by_path(summary: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        str(entry.get("path", "")): entry
        for entry in summary.get("files", [])
        if isinstance(entry, dict) and entry.get("path")
    }


def repair_instance(instance_dir: Path, only_zero: bool) -> tuple[int, int]:
    artifacts_dir = instance_dir / "_explanations"
    result_path = artifacts_dir / "result.json"
    summary_path = artifacts_dir / "summary_with_mappings.json"
    repo_dir = instance_dir / "repo"

    if not result_path.exists() or not summary_path.exists() or not repo_dir.exists():
        return (0, 0)

    result = load_json(result_path)
    summary = load_json(summary_path)
    entries = file_entries_by_path(summary)

    repaired_files = 0
    inserted_total = 0
    new_file_results: list[dict[str, Any]] = []
    for file_result in result.get("file_results", []):
        if not isinstance(file_result, dict):
            continue
        relative_path = str(file_result.get("file", ""))
        if not relative_path:
            new_file_results.append(file_result)
            continue
        if only_zero and int(file_result.get("inserted_count") or 0) != 0:
            new_file_results.append(file_result)
            continue

        file_entry = entries.get(relative_path)
        if not file_entry:
            new_file_results.append(file_result)
            continue

        mappings = file_entry.get("mappings", {}).get("high_structured", [])
        if not isinstance(mappings, list):
            mappings = []
        summary_text = str(file_entry.get("summary", {}).get("high_structured", ""))
        inserted_count = insert_high_detail_comments(repo_dir, relative_path, mappings, summary_text)

        updated = dict(file_result)
        updated["inserted_count"] = inserted_count
        if inserted_count:
            updated["repair_status"] = "inserted"
            repaired_files += 1
            inserted_total += inserted_count
        else:
            updated["repair_status"] = "still_zero"
        new_file_results.append(updated)

    result["file_results"] = new_file_results
    if result.get("status") == "failed" and repaired_files:
        result["status"] = "ok"
        result.pop("error", None)
    save_json(result_path, result)
    return (repaired_files, inserted_total)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repos-explained", default="repos-explained")
    parser.add_argument("--instance-id", action="append", default=[])
    parser.add_argument("--all", action="store_true", help="Repair all instance dirs under repos-explained.")
    parser.add_argument("--include-nonzero", action="store_true", help="Also reinsert files that already had comments.")
    args = parser.parse_args()

    root = Path(args.repos_explained)
    if args.all:
        instance_dirs = sorted(path for path in root.glob("sphinx-doc__sphinx-*") if path.is_dir())
    else:
        requested: list[str] = []
        for value in args.instance_id:
            requested.extend(item.strip() for item in value.split(",") if item.strip())
        instance_dirs = [root / instance_id for instance_id in requested]

    total_files = 0
    total_insertions = 0
    for instance_dir in instance_dirs:
        repaired_files, inserted_count = repair_instance(instance_dir, only_zero=not args.include_nonzero)
        total_files += repaired_files
        total_insertions += inserted_count
        print(f"{instance_dir.name}: repaired_files={repaired_files} inserted_comments={inserted_count}")

    print(f"total_repaired_files={total_files} total_inserted_comments={total_insertions}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
