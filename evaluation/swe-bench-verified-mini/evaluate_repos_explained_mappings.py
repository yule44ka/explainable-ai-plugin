#!/usr/bin/env python3
"""LLM-as-a-judge evaluation for generated repos-explained mappings.

This runner evaluates already-generated ``summary_with_mappings.json`` files
under ``repos-explained/``. It does not regenerate summaries or mappings.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import json
import os
import threading
import time
from pathlib import Path
from typing import Any

from openai import OpenAI


DEFAULT_MODEL = "gpt-5.5"
DEFAULT_WORKERS = 4
DEFAULT_REPOS_EXPLAINED = Path(__file__).resolve().parent / "repos-explained"
DEFAULT_ENV_FILE = Path(__file__).resolve().parents[1] / ".env"
DEFAULT_DATASET = Path(__file__).resolve().parent / "swe-bench-verified-mini.jsonl"
DEFAULT_OUTPUT_JSONL = DEFAULT_REPOS_EXPLAINED / "_task_mapping_judge_results_gpt_5_5.jsonl"
DEFAULT_OUTPUT_CSV = DEFAULT_REPOS_EXPLAINED / "_task_mapping_judge_results_gpt_5_5.csv"

PRINT_LOCK = threading.Lock()
WRITE_LOCK = threading.Lock()

RUBRIC_FIELDS = [
    "mapping_line_accuracy",
    "mapping_semantic_alignment",
    "explanation_factual_accuracy",
    "implementation_detail",
    "algorithmic_logic",
    "algorithmic_choice_rationale",
    "architecture_and_abstractions",
    "change_behavior_context",
    "coverage_completeness",
    "segmentation_clarity",
]


SYSTEM_PROMPT = """You are an expert code explanation and code-to-explanation mapping evaluator.
Your task is to assess the quality of an automatically generated explanation and its mappings to source code.

You evaluate one SWE-bench task containing multiple files. For each file you see:
1. SOURCE CODE - the numbered source file.
2. GENERATED EXPLANATION - a high-detail structured explanation of the file.
3. GENERATED MAPPINGS - a list of explanation components mapped to code line fragments.

Evaluate the task as a whole, not each file independently. Your scores should reflect the overall quality across all files in the task.

Evaluate the artifact using exactly these 10 rubrics. Each rubric must be scored 0 or 1 (0 if not in explanation, 1 if in it):
1. mapping_line_accuracy: Are the referenced line numbers valid, and do fragments correspond to the exact source lines?
2. mapping_semantic_alignment: Do explanation components map to the code that actually implements the described behavior?
3. explanation_factual_accuracy: Is the explanation technically correct, with no hallucinated behavior or incorrect claims?
4. implementation_detail: Does it explain concrete implementation mechanics, data flow, control flow, APIs, state changes, and edge cases?
5. algorithmic_logic: Does it explain the algorithmic logic and step-by-step behavior of the code?
6. algorithmic_choice_rationale: Does it explain why this algorithm or approach is used, including tradeoffs where inferable from code/context?
7. architecture_and_abstractions: Does it explain architectural decisions, abstractions, class/function responsibilities, and module relationships?
8. change_behavior_context: Does it explain relation to the requested change, previous behavior, new behavior, and final effect when such context is available?
9. coverage_completeness: Do explanation and mappings cover all important code regions without leaving major logic unmapped?
10. segmentation_clarity: Are mappings split into coherent logical units, ordered clearly, and free from harmful redundancy?

Use this rubric scale:
  Count number of completed rubric - this is your final mark.

Return a JSON object with exactly this schema:
{
  "metric": <integer 0-10>,
  "mapping_line_accuracy": <integer 0 or 1>,
  "mapping_semantic_alignment": <integer 0 or 1>,
  "explanation_factual_accuracy": <integer 0 or 1>,
  "implementation_detail": <integer 0 or 1>,
  "algorithmic_logic": <integer 0 or 1>,
  "algorithmic_choice_rationale": <integer 0 or 1>,
  "architecture_and_abstractions": <integer 0 or 1>,
  "change_behavior_context": <integer 0 or 1>,
  "coverage_completeness": <integer 0 or 1>,
  "segmentation_clarity": <integer 0 or 1>,
  "explain_metric": "<string>"
}

The "metric" must equal the sum of the 10 binary rubric scores.
The "explain_metric" must briefly justify which rubrics failed and why.

Be strict but fair. Output ONLY the JSON object, no extra text."""


USER_PROMPT_TEMPLATE = """Evaluate this generated code explanation and mapping at the task level.

INSTANCE: {instance_id}

=== TASK CONTEXT ===
{task_context}

=== TASK FILES WITH GENERATED EXPLANATIONS AND MAPPINGS ===
{files_payload}

Return your evaluation as a JSON object following the schema from your instructions."""


def log(message: str, quiet: bool = False) -> None:
    if quiet:
        return
    with PRINT_LOCK:
        print(message, flush=True)


def load_dotenv(path: Path, override: bool = False) -> dict[str, str]:
    loaded: dict[str, str] = {}
    if not path.exists():
        return loaded
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line.removeprefix("export ").strip()
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip("'").strip('"')
        if not key:
            continue
        if override or key not in os.environ:
            os.environ[key] = value
        loaded[key] = value
    return loaded


def parse_json_response(content: str) -> dict[str, Any]:
    text = content.strip()
    if text.startswith("```"):
        parts = text.split("```")
        if len(parts) >= 3:
            text = parts[1]
            if text.lstrip().startswith("json"):
                text = text.lstrip()[4:]
    return json.loads(text.strip())


def load_dataset_contexts(path: Path, max_chars: int) -> dict[str, str]:
    if not path.exists():
        return {}

    contexts: dict[str, str] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc

        instance_id = row.get("instance_id")
        if not isinstance(instance_id, str) or not instance_id:
            continue

        context = {
            "repo": row.get("repo", ""),
            "instance_id": instance_id,
            "problem_statement": row.get("problem_statement", ""),
            "hints_text": row.get("hints_text", ""),
            "patch": row.get("patch", ""),
            "test_patch": row.get("test_patch", ""),
            "FAIL_TO_PASS": row.get("FAIL_TO_PASS", ""),
            "PASS_TO_PASS": row.get("PASS_TO_PASS", ""),
        }
        text = json.dumps(context, ensure_ascii=False, indent=2)
        if len(text) > max_chars:
            text = text[:max_chars] + "\n... [TRUNCATED BY EVALUATION RUNNER]"
        contexts[instance_id] = text
    return contexts


def numbered_code(code: str, max_chars: int) -> str:
    if len(code) > max_chars:
        code = code[:max_chars] + "\n... [TRUNCATED BY EVALUATION RUNNER]"
    return "\n".join(f"{idx}: {line}" for idx, line in enumerate(code.splitlines(), start=1))


def normalize_segment(segment: dict[str, Any]) -> dict[str, Any]:
    fragment = segment.get("fragment")
    if fragment is None:
        fragment = segment.get("code", "")
    return {
        "line": segment.get("line"),
        "fragment": fragment,
    }


def normalize_mapping(mapping: dict[str, Any]) -> dict[str, Any]:
    component = ""
    for key in ("explanationComponent", "component", "explanation", "summaryComponent"):
        value = mapping.get(key)
        if isinstance(value, str) and value.strip():
            component = value
            break

    raw_segments = mapping.get("codeSegments")
    if not isinstance(raw_segments, list):
        raw_segments = []

    return {
        "explanationComponent": component,
        "codeSegments": [
            normalize_segment(segment)
            for segment in raw_segments
            if isinstance(segment, dict)
        ],
    }


def load_prompt_numbered_sources(artifacts_dir: Path) -> dict[str, str]:
    prompt_path = artifacts_dir / "prompt_summary_with_mappings.txt"
    if not prompt_path.exists():
        return {}

    text = prompt_path.read_text(encoding="utf-8", errors="replace")
    marker = "Input files:"
    marker_index = text.rfind(marker)
    if marker_index < 0:
        return {}

    payload_text = text[marker_index + len(marker):].strip()
    try:
        files_payload = json.loads(payload_text)
    except json.JSONDecodeError:
        return {}

    if not isinstance(files_payload, list):
        return {}

    sources: dict[str, str] = {}
    for file_payload in files_payload:
        if not isinstance(file_payload, dict):
            continue
        path = file_payload.get("path")
        numbered = file_payload.get("numbered_content")
        if isinstance(path, str) and isinstance(numbered, str):
            sources[path] = numbered
    return sources


def discover_tasks(repos_explained: Path) -> list[dict[str, Any]]:
    tasks: list[dict[str, Any]] = []
    for summary_path in sorted(repos_explained.glob("*/_explanations/summary_with_mappings.json")):
        task_dir = summary_path.parents[1]
        artifacts_dir = summary_path.parent
        instance_id = task_dir.name
        repo_dir = task_dir / "repo"
        numbered_sources = load_prompt_numbered_sources(artifacts_dir)

        try:
            payload = json.loads(summary_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            tasks.append(
                {
                    "instance_id": instance_id,
                    "summary_path": str(summary_path),
                    "error": f"Invalid JSON in summary_with_mappings.json: {exc}",
                }
            )
            continue

        files = payload.get("files", [])
        if not isinstance(files, list):
            continue

        task_files: list[dict[str, Any]] = []
        for file_entry in files:
            if not isinstance(file_entry, dict):
                continue
            relative_path = str(file_entry.get("path", "")).strip()
            if not relative_path:
                continue
            task_files.append(
                {
                    "file_path": relative_path,
                    "repo_dir": repo_dir,
                    "numbered_source": numbered_sources.get(relative_path),
                    "file_entry": file_entry,
                }
            )
        tasks.append(
            {
                "instance_id": instance_id,
                "repo_dir": repo_dir,
                "summary_path": summary_path,
                "files": task_files,
            }
        )
    return tasks


def result_key(result: dict[str, Any]) -> str:
    return str(result.get("instance_id", ""))


def load_completed_keys(output_jsonl: Path) -> set[str]:
    if not output_jsonl.exists():
        return set()
    keys: set[str] = set()
    for line in output_jsonl.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if row.get("metric") is None:
            continue
        keys.add(result_key(row))
    return keys


def append_jsonl(path: Path, row: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with WRITE_LOCK:
        with path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def empty_rubric_scores() -> dict[str, None]:
    return {field: None for field in RUBRIC_FIELDS}


def build_file_payload(file_item: dict[str, Any], max_code_chars: int) -> dict[str, Any]:
    file_path = str(file_item.get("file_path", ""))
    repo_dir = Path(file_item["repo_dir"])
    source_path = repo_dir / file_path
    source_from_prompt = file_item.get("numbered_source")

    if isinstance(source_from_prompt, str) and source_from_prompt.strip():
        source_code_block = source_from_prompt
        if len(source_code_block) > max_code_chars:
            source_code_block = source_code_block[:max_code_chars] + "\n... [TRUNCATED BY EVALUATION RUNNER]"
    elif source_path.exists():
        code = source_path.read_text(encoding="utf-8", errors="replace")
        source_code_block = numbered_code(code, max_chars=max_code_chars)
    else:
        source_code_block = f"[SOURCE FILE NOT FOUND: {source_path}]"

    file_entry = file_item["file_entry"]
    summary = file_entry.get("summary", {})
    mappings = file_entry.get("mappings", {})
    explanation = summary.get("high_structured", "") if isinstance(summary, dict) else ""
    high_structured_mappings = mappings.get("high_structured", []) if isinstance(mappings, dict) else []
    if not isinstance(high_structured_mappings, list):
        high_structured_mappings = []

    normalized_mappings = [
        normalize_mapping(mapping)
        for mapping in high_structured_mappings
        if isinstance(mapping, dict)
    ]

    return {
        "path": file_path,
        "source_code_with_line_numbers": source_code_block,
        "generated_explanation": explanation,
        "generated_mappings": normalized_mappings,
        "num_mappings": len(normalized_mappings),
    }


def evaluate_task(
    client: OpenAI,
    task: dict[str, Any],
    model: str,
    max_code_chars: int,
    retries: int,
    retry_delay: float,
) -> dict[str, Any]:
    instance_id = str(task.get("instance_id", ""))

    if task.get("error"):
        return {
            "instance_id": instance_id,
            "num_files": 0,
            "num_mappings": 0,
            "metric": None,
            **empty_rubric_scores(),
            "explain_metric": task["error"],
            "error": task["error"],
        }

    files_payload = [
        build_file_payload(file_item, max_code_chars=max_code_chars)
        for file_item in task.get("files", [])
    ]
    total_mappings = sum(int(file_payload["num_mappings"]) for file_payload in files_payload)

    user_prompt = USER_PROMPT_TEMPLATE.format(
        instance_id=instance_id,
        task_context=task.get("task_context", "(no task context available)"),
        files_payload=json.dumps(files_payload, ensure_ascii=False, indent=2),
    )

    last_error: Exception | None = None
    for attempt in range(1, retries + 2):
        try:
            response = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": user_prompt},
                ],
                response_format={"type": "json_object"},
            )
            content = response.choices[0].message.content or "{}"
            parsed = parse_json_response(content)
            usage = response.usage
            return {
                "instance_id": instance_id,
                "num_files": len(files_payload),
                "num_mappings": total_mappings,
                "metric": parsed.get("metric"),
                **{field: parsed.get(field) for field in RUBRIC_FIELDS},
                "explain_metric": parsed.get("explain_metric", ""),
                "input_tokens": usage.prompt_tokens if usage else None,
                "output_tokens": usage.completion_tokens if usage else None,
                "model": model,
            }
        except Exception as exc:
            last_error = exc
            if attempt <= retries:
                time.sleep(retry_delay * attempt)

    error = str(last_error) if last_error else "Unknown OpenAI error"
    return {
        "instance_id": instance_id,
        "num_files": len(files_payload),
        "num_mappings": total_mappings,
        "metric": None,
        **empty_rubric_scores(),
        "explain_metric": error,
        "error": error,
        "model": model,
    }


def write_csv(output_csv: Path, rows: list[dict[str, Any]]) -> None:
    output_csv.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "instance_id",
        "num_files",
        "num_mappings",
        "metric",
        *RUBRIC_FIELDS,
        "explain_metric",
        "input_tokens",
        "output_tokens",
        "model",
        "error",
    ]
    with output_csv.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def load_jsonl_rows(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Evaluate generated repos-explained task mappings with OpenAI LLM-as-a-judge."
    )
    parser.add_argument("--repos-explained", type=Path, default=DEFAULT_REPOS_EXPLAINED)
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS)
    parser.add_argument("--limit", type=int, default=None, help="Limit number of tasks to evaluate.")
    parser.add_argument("--instance-id", action="append", default=None)
    parser.add_argument("--output-jsonl", type=Path, default=DEFAULT_OUTPUT_JSONL)
    parser.add_argument("--output-csv", type=Path, default=DEFAULT_OUTPUT_CSV)
    parser.add_argument("--max-code-chars", type=int, default=60_000)
    parser.add_argument("--max-task-context-chars", type=int, default=12_000)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--retry-delay", type=float, default=10.0)
    parser.add_argument("--force", action="store_true", help="Re-evaluate rows already present in output JSONL.")
    parser.add_argument("--quiet", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    load_dotenv(args.env_file)

    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise EnvironmentError(f"OPENAI_API_KEY not found. Set it in {args.env_file}.")

    tasks = discover_tasks(args.repos_explained)
    task_contexts = load_dataset_contexts(args.dataset, args.max_task_context_chars)
    for task in tasks:
        task["task_context"] = task_contexts.get(str(task.get("instance_id", "")), "(no task context available)")

    if args.instance_id:
        allowed = set(args.instance_id)
        tasks = [task for task in tasks if task.get("instance_id") in allowed]

    completed = set() if args.force else load_completed_keys(args.output_jsonl)
    pending = [task for task in tasks if result_key(task) not in completed]
    if args.limit is not None:
        pending = pending[: args.limit]

    if args.force:
        args.output_jsonl.parent.mkdir(parents=True, exist_ok=True)
        args.output_jsonl.write_text("", encoding="utf-8")
        if args.output_csv.exists():
            args.output_csv.unlink()

    log(f"Discovered: {len(tasks)} tasks", args.quiet)
    log(f"Already completed: {len(completed)}", args.quiet)
    log(f"Pending: {len(pending)}", args.quiet)
    log(f"Judge model: {args.model}", args.quiet)

    client = OpenAI(api_key=api_key)
    results = load_jsonl_rows(args.output_jsonl) if not args.force else []

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {
            executor.submit(
                evaluate_task,
                client,
                task,
                args.model,
                args.max_code_chars,
                args.retries,
                args.retry_delay,
            ): task
            for task in pending
        }

        total = len(futures)
        for index, future in enumerate(concurrent.futures.as_completed(futures), start=1):
            task = futures[future]
            try:
                row = future.result()
            except Exception as exc:
                row = {
                    "instance_id": task.get("instance_id", ""),
                    "num_files": len(task.get("files", [])),
                    "num_mappings": None,
                    "metric": None,
                    **empty_rubric_scores(),
                    "explain_metric": str(exc),
                    "error": str(exc),
                    "model": args.model,
                }
            append_jsonl(args.output_jsonl, row)
            results.append(row)
            status = f"metric={row.get('metric')}"
            if row.get("metric") is None and row.get("error"):
                status += f" error={str(row.get('error'))[:180]}"
            log(
                f"[{index}/{total}] {row.get('instance_id')} {status}",
                args.quiet,
            )

    write_csv(args.output_csv, results)
    log(f"JSONL saved to: {args.output_jsonl}", args.quiet)
    log(f"CSV saved to: {args.output_csv}", args.quiet)


if __name__ == "__main__":
    main()
