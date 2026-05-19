#!/usr/bin/env python3
"""Collect latest Junie result.json files for one repo into a CSV."""

from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path
from typing import Any


FIELDS = [
    "run_id",
    "agent_actions",
    "instance_id",
    "repo",
    "base_commit",
    "status",
    "success",
    "failed_tests",
    "agent_seconds",
    "eval_seconds",
    "total_seconds",
    "junie_returncode",
    "eval_returncode",
    "output_dir",
    "error",
]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def load_dataset_ids(dataset: Path, repo: str) -> list[str]:
    ids: list[str] = []
    for line in read_text(dataset).splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("repo") == repo:
            ids.append(row["instance_id"])
    return ids


def source_patch_path(output_dir: Path, root: Path) -> Path | None:
    source = output_dir / "existing_model_patch_source.txt"
    if not source.exists():
        return None
    raw = read_text(source).strip()
    if not raw:
        return None
    path = Path(raw)
    if not path.is_absolute():
        path = root / path
    return path


def count_agent_actions(output_dir: Path, root: Path) -> str:
    candidates = [output_dir / "agent_stdout.txt"]
    patch_path = source_patch_path(output_dir, root)
    if patch_path is not None:
        candidates.append(patch_path.parent / "agent_stdout.txt")

    for path in candidates:
        if path.exists():
            count = read_text(path).count("●")
            if count:
                return str(count)
    return "0"


def failed_tests_count(output_dir: Path, status: str) -> str:
    if status == "passed":
        return "0"

    text = ""
    for name in ("eval_stdout.txt", "eval_stderr.txt"):
        path = output_dir / name
        if path.exists():
            text += "\n" + read_text(path)

    total = 0
    found = False
    for pattern in (r"(\d+)\s+failed", r"(\d+)\s+error(?:s)?"):
        for match in re.finditer(pattern, text, flags=re.IGNORECASE):
            total += int(match.group(1))
            found = True
    if found:
        return str(total)
    if status == "failed":
        return "1"
    return ""


def load_latest_results(results_dirs: list[Path], instance_ids: set[str]) -> dict[str, tuple[tuple[str, str], Path, dict[str, Any]]]:
    latest: dict[str, tuple[tuple[str, str], Path, dict[str, Any]]] = {}
    for results_dir in results_dirs:
        for path in results_dir.glob("*/**/result.json"):
            try:
                data = json.loads(read_text(path))
            except Exception:
                continue
            instance_id = data.get("instance_id")
            if instance_id not in instance_ids:
                continue
            run_id = path.parts[-3]
            key = (data.get("finished_at") or "", run_id)
            previous = latest.get(instance_id)
            if previous is None or key > previous[0]:
                latest[instance_id] = (key, path, data)
    return latest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="swe-bench-verified-mini.jsonl")
    parser.add_argument("--repo", required=True)
    parser.add_argument("--results-dir", action="append", default=[], help="Directory for run artifacts. Repeatable.")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    dataset = (root / args.dataset).resolve()
    results_dirs = [(root / path).resolve() for path in (args.results_dir or ["results/junie"])]
    output = (root / args.output).resolve()

    ids = load_dataset_ids(dataset, args.repo)
    latest = load_latest_results(results_dirs, set(ids))

    rows: list[dict[str, Any]] = []
    for instance_id in ids:
        if instance_id not in latest:
            row = {field: "" for field in FIELDS}
            row.update({"instance_id": instance_id, "repo": args.repo, "status": "missing"})
            rows.append(row)
            continue

        _, path, data = latest[instance_id]
        output_dir = Path(data.get("output_dir") or path.parent)
        status = str(data.get("status", ""))
        rows.append(
            {
                "run_id": output_dir.parent.name,
                "agent_actions": count_agent_actions(output_dir, root),
                "instance_id": data.get("instance_id", instance_id),
                "repo": data.get("repo", args.repo),
                "base_commit": data.get("base_commit", ""),
                "status": status,
                "success": str(data.get("success", "")),
                "failed_tests": failed_tests_count(output_dir, status),
                "agent_seconds": data.get("agent_seconds", ""),
                "eval_seconds": data.get("eval_seconds", ""),
                "total_seconds": data.get("total_seconds", ""),
                "junie_returncode": data.get("junie_returncode", ""),
                "eval_returncode": data.get("eval_returncode", ""),
                "output_dir": str(output_dir),
                "error": data.get("error", ""),
            }
        )

    with output.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)

    passed = sum(row["status"] == "passed" for row in rows)
    failed = sum(row["status"] == "failed" for row in rows)
    missing = sum(row["status"] == "missing" for row in rows)
    print(f"{output}")
    print(f"rows={len(rows)} passed={passed} failed={failed} missing={missing}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
