#!/usr/bin/env python3
"""Generate 5-topic explanations with the SWE-bench repos-explained Junie prompt."""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any


EVALUATION_DIR = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = EVALUATION_DIR / "data" / "dataset_5topics_files.csv"
DEFAULT_OUTPUT = EVALUATION_DIR / "data" / "generated" / "dataset_5topics_files_generated_junie_swebench_style_gemini_3_1_flash_lite.csv"
DEFAULT_MODEL = "gemini-3.1-flash-lite-preview"
FALLBACK_MODEL = "gemini-3.1-flash-lite"
MODEL_SLUG = "junie_swebench_style_gemini_3_1_flash_lite"

SUMMARY_START_MARKER = "BEGIN_EXPLAINABLE_AI_SUMMARY_WITH_MAPPINGS_JSON"
SUMMARY_END_MARKER = "END_EXPLAINABLE_AI_SUMMARY_WITH_MAPPINGS_JSON"


def numbered_code(code: str, real_start_line: int = 1) -> str:
    return "\n".join(
        f"{index + real_start_line}: {line}"
        for index, line in enumerate(code.splitlines())
    )


def build_task_junie_prompt(files_payload: list[dict[str, str]], response_file: Path) -> str:
    return f"""
You are an expert code explainer and code-to-explanation mapper. Generate explanations and mappings for ALL files listed below in ONE response.

The explanation of each selected code segment should cover its purpose, foundations of way of implementation, relation to the requested change, previous and new behavior, algorithmic logic, implementation and architectural decisions, use of abstractions, and the final effect of the segment.

For EACH file, generate exactly one explanation of the whole file:
- high_structured: 8-10 bullet points, high-detail, as a single string
- Use as many bullet points as needed to explain the file clearly and in high details and cover the mapped code.
- Use "•" for first-level bullets and "◦" for second-level bullets when useful.
- Bullets must be separated by "\\n". Never return an array for the explanation text.

For the high_structured explanation of EACH file, also build mappings from exact explanation substrings to code segments in that same file.

Mapping rules:
1. Each explanationComponent MUST be an exact substring of the corresponding explanation string.
2. Extract explanationComponents in the exact order they appear in that explanation string.
3. Every line of each file's numbered content MUST be covered by at least one high_structured mapping.
4. For each code segment, return both the code fragment and the exact line number shown before the colon.
5. Prefer complete code statements when they clearly match the explanation component.
6. If a code segment contains multiple lines, split them into separate objects in codeSegments.
7. Keep mappings scoped to their own file. Never map an explanation for one file to code from another file.

Return ONLY a JSON object with this exact shape:
{{
  "files": [
    {{
      "path": "relative/path.py",
      "summary": {{
        "title": "",
        "high_structured": ""
      }},
      "mappings": {{
        "high_structured": []
      }}
    }}
  ]
}}

The files array MUST contain exactly one entry for every input file path and MUST use the same path strings.
Do not wrap the JSON in markdown fences. Do not add commentary before or after the JSON.
Also write the final JSON payload exactly to this absolute file path and overwrite the file contents when possible:
{response_file}

Always output the same JSON between these exact marker lines:
{SUMMARY_START_MARKER}
[your JSON here]
{SUMMARY_END_MARKER}

Input files:
{json.dumps(files_payload, ensure_ascii=False, indent=2)}
""".strip()


def extract_json_between_markers(text: str) -> str | None:
    pattern = re.compile(
        re.escape(SUMMARY_START_MARKER) + r"\s*(.*?)\s*" + re.escape(SUMMARY_END_MARKER),
        re.DOTALL,
    )
    match = pattern.search(text)
    return match.group(1).strip() if match else None


def extract_first_json_object(text: str) -> str | None:
    start = text.find("{")
    if start == -1:
        return None
    depth = 0
    in_string = False
    escape = False
    for index in range(start, len(text)):
        char = text[index]
        if in_string:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
    return None


def escape_control_chars_in_json_strings(text: str) -> str:
    result: list[str] = []
    in_string = False
    escape = False
    for char in text:
        if in_string:
            if escape:
                result.append(char)
                escape = False
            elif char == "\\":
                result.append(char)
                escape = True
            elif char == '"':
                result.append(char)
                in_string = False
            elif char == "\n":
                result.append("\\n")
            elif char == "\r":
                result.append("\\r")
            elif char == "\t":
                result.append("\\t")
            else:
                result.append(char)
            continue
        result.append(char)
        if char == '"':
            in_string = True
    return "".join(result)


def loads_json_lenient(text: str) -> Any:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        repaired = re.sub(r'\},"\}\s*,', "},", text)
        repaired = re.sub(r'("line"\s*:\s*\d+)\s*,\s*""', r'\1, "code": ""', repaired)
        repaired = escape_control_chars_in_json_strings(repaired)
        return json.loads(repaired)


def extract_task_json_from_junie_wrapper(parsed: Any) -> dict[str, Any] | None:
    if not isinstance(parsed, dict):
        return None
    for change in parsed.get("changes", []):
        if not isinstance(change, dict):
            continue
        after_content = change.get("afterContent")
        if not isinstance(after_content, dict):
            continue
        text = after_content.get("text")
        if not isinstance(text, str) or not text.strip():
            continue
        for payload in (
            extract_json_between_markers(text),
            extract_first_json_object(text),
            text.strip(),
        ):
            if not payload:
                continue
            try:
                candidate = loads_json_lenient(payload)
            except json.JSONDecodeError:
                continue
            if isinstance(candidate, dict) and isinstance(candidate.get("files"), list):
                return candidate
    return None


def load_task_summary_with_mappings(response_file: Path, stdout: str, stderr: str, json_output: str) -> dict[str, Any]:
    candidates: list[str] = []
    if response_file.exists():
        candidates.append(response_file.read_text(encoding="utf-8", errors="replace"))
    candidates.extend([stdout, stderr, json_output])
    for candidate in candidates:
        if not candidate.strip():
            continue
        for payload in (
            extract_json_between_markers(candidate),
            candidate.strip(),
            extract_first_json_object(candidate),
        ):
            if not payload:
                continue
            try:
                parsed = loads_json_lenient(payload)
            except json.JSONDecodeError:
                continue
            if isinstance(parsed, dict) and isinstance(parsed.get("files"), list):
                return parsed
            extracted = extract_task_json_from_junie_wrapper(parsed)
            if extracted is not None:
                return extracted
    raise ValueError("Could not parse Junie task summary-with-mappings JSON")


def run_junie(prompt: str, model: str, timeout_seconds: int, cwd: Path) -> tuple[dict[str, Any], str]:
    json_output_path = Path(tempfile.mkstemp(prefix="junie-output-", suffix=".json")[1])
    env = os.environ.copy()
    command = [
        "junie",
        "--skip-update-check",
        "--output-format=json",
        f"--json-output-file={json_output_path}",
        f"--model={model}",
    ]
    if env.get("JUNIE_API_KEY"):
        command.append(f"--auth={env['JUNIE_API_KEY']}")
    command.append(prompt)

    def run_once(active_model: str) -> subprocess.CompletedProcess[str]:
        command[4] = f"--model={active_model}"
        return subprocess.run(
            command,
            cwd=cwd,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_seconds + 30,
        )

    started = time.monotonic()
    result = run_once(model)
    active_model = model
    combined = f"{result.stdout}\n{result.stderr}"
    if result.returncode != 0 and "Invalid model" in combined and model != FALLBACK_MODEL:
        print(f"Model {model} is not available locally; retrying with {FALLBACK_MODEL}", flush=True)
        result = run_once(FALLBACK_MODEL)
        active_model = FALLBACK_MODEL
    elapsed = time.monotonic() - started

    json_output = ""
    if json_output_path.exists():
        json_output = json_output_path.read_text(encoding="utf-8", errors="replace")
    if result.returncode != 0:
        print(result.stdout[-2000:], flush=True)
        print(result.stderr[-2000:], flush=True)
        raise RuntimeError(f"Junie failed with exit code {result.returncode}")
    print(f"Junie done in {elapsed:.1f}s with model {active_model}", flush=True)
    return load_task_summary_with_mappings(response_file_from_prompt(prompt), result.stdout, result.stderr, json_output), active_model


def response_file_from_prompt(prompt: str) -> Path:
    marker = "Also write the final JSON payload exactly to this absolute file path and overwrite the file contents when possible:"
    lines = prompt.splitlines()
    for index, line in enumerate(lines):
        if line.strip() == marker:
            return Path(lines[index + 1].strip())
    raise ValueError("Could not find response file path in prompt")


def summary_col() -> str:
    return f"summary__{MODEL_SLUG}__high__structured"


def remapping_col() -> str:
    return f"remapping__{MODEL_SLUG}__high__structured"


def price_col() -> str:
    return f"price__{MODEL_SLUG}"


def normalize_code_segments(mapping: dict[str, Any]) -> dict[str, Any]:
    normalized_segments = []
    for segment in mapping.get("codeSegments", []) or []:
        if not isinstance(segment, dict):
            continue
        fragment = segment.get("code", segment.get("fragment", ""))
        try:
            line = int(segment.get("line", 0))
        except (TypeError, ValueError):
            line = 0
        normalized_segments.append({"code": str(fragment), "line": line})
    return {
        "explanationComponent": str(mapping.get("explanationComponent", "")),
        "codeSegments": normalized_segments,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--response-json", type=Path, default=None)
    args = parser.parse_args()

    with args.input.open(newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        base_headers = reader.fieldnames or []
        rows = [
            row
            for row in reader
            if row.get("file_path", "").strip() and row.get("code", "").strip()
        ]

    files_payload = [
        {
            "path": row["file_path"],
            "content": row["code"],
            "numbered_content": numbered_code(row["code"]),
        }
        for row in rows
    ]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    artifacts_dir = args.output.with_suffix("")
    artifacts_dir.mkdir(parents=True, exist_ok=True)
    response_file = Path(tempfile.mkstemp(prefix="junie-summary-with-mappings-", suffix=".json")[1])
    prompt = build_task_junie_prompt(files_payload, response_file)
    (artifacts_dir / "prompt_summary_with_mappings.txt").write_text(prompt, encoding="utf-8")

    print(f"Input:  {args.input} ({len(rows)} rows)")
    print(f"Output: {args.output}")
    print(f"Prompt: {artifacts_dir / 'prompt_summary_with_mappings.txt'}")
    if args.response_json is None:
        parsed, actual_model = run_junie(prompt, args.model, args.timeout, Path.cwd())
    else:
        parsed = loads_json_lenient(args.response_json.read_text(encoding="utf-8", errors="replace"))
        actual_model = "reused-response"
    (artifacts_dir / "summary_with_mappings.json").write_text(
        json.dumps(parsed, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (artifacts_dir / "actual_model.txt").write_text(actual_model + "\n", encoding="utf-8")

    entries_by_path = {
        str(entry.get("path", "")): entry
        for entry in parsed.get("files", [])
        if isinstance(entry, dict)
    }
    headers = base_headers + [summary_col(), remapping_col(), price_col()]
    with args.output.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=headers)
        writer.writeheader()
        for row in rows:
            entry = entries_by_path.get(row["file_path"], {})
            summary = entry.get("summary", {}) if isinstance(entry, dict) else {}
            mappings = entry.get("mappings", {}) if isinstance(entry, dict) else {}
            high_mappings = mappings.get("high_structured", []) if isinstance(mappings, dict) else []
            output = dict(row)
            output[summary_col()] = str(summary.get("high_structured", "")) if isinstance(summary, dict) else ""
            output[remapping_col()] = json.dumps(
                [normalize_code_segments(mapping) for mapping in high_mappings if isinstance(mapping, dict)],
                ensure_ascii=False,
            )
            output[price_col()] = ""
            writer.writerow(output)

    print(f"Saved {len(rows)} rows")


if __name__ == "__main__":
    main()
