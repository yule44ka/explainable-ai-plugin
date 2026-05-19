#!/usr/bin/env python3
"""Generate explanation summaries and mappings with Junie for the 5-topic dataset."""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import shlex
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any


EVALUATION_DIR = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = EVALUATION_DIR / "data" / "dataset_5topics_files.csv"
DEFAULT_OUTPUT = EVALUATION_DIR / "data" / "generated" / "dataset_5topics_files_generated_junie_gemini_3_1_flash_lite.csv"
JUNIE_MODEL = "gemini-3.1-flash-lite"
MODEL_SLUG = "junie_gemini_3_1_flash_lite"

DETAIL_LEVELS = ("low", "medium", "high")
STRUCTURES = ("unstructured", "structured")

SUMMARY_WITH_MAPPINGS_START_MARKER = "BEGIN_EXPLAINABLE_AI_SUMMARY_WITH_MAPPINGS_JSON"
SUMMARY_WITH_MAPPINGS_END_MARKER = "END_EXPLAINABLE_AI_SUMMARY_WITH_MAPPINGS_JSON"


def summary_col(detail: str, structure: str) -> str:
    return f"summary__{MODEL_SLUG}__{detail}__{structure}"


def remapping_col(detail: str, structure: str) -> str:
    return f"remapping__{MODEL_SLUG}__{detail}__{structure}"


def price_col() -> str:
    return f"price__{MODEL_SLUG}"


def generated_headers() -> list[str]:
    headers: list[str] = []
    for detail in DETAIL_LEVELS:
        for structure in STRUCTURES:
            headers.append(summary_col(detail, structure))
            headers.append(remapping_col(detail, structure))
    headers.append(price_col())
    return headers


def row_key(row: dict[str, str]) -> str:
    return f"{row.get('file_path', '')}|{row.get('name', '')}"


def build_prompt(code: str, file_context: str, response_file: Path) -> str:
    code_with_line_numbers = "\n".join(
        f"{idx}: {line}" for idx, line in enumerate(code.split("\n"), start=1)
    )

    return f"""
You are an expert code explainer and code-to-explanation mapper. For the following code snippet, generate explanations and mappings in ONE response.

Generate 6 explanations of the whole input, one for each combination of detail level (low, medium, high) and structure (unstructured paragraph, structured bullets):
- low_unstructured: One-sentence, low-detail, paragraph style.
- low_structured: 2-3 short bullet points, low-detail, as a single string. Each bullet must start with "•" and be separated by \\n. Never return an array.
- medium_unstructured: 2-3 sentences, medium-detail, paragraph style.
- medium_structured: 3-5 bullet points, medium-detail, as a single string. Use "•" for first-level bullets and "◦" for second-level bullets when useful. Bullets must be separated by \\n. Never return an array.
- high_unstructured: 3-4 sentences, high-detail, paragraph style.
- high_structured: 4-8 bullet points, high-detail, as a single string. Use "•" for first-level bullets and "◦" for second-level bullets when useful. Bullets must be separated by \\n. Never return an array.

For EACH of the 6 explanation strings, also build mappings from explanation components to code segments.

Mapping rules:
1. Each explanationComponent MUST be an exact substring of the corresponding explanation string.
2. Extract explanationComponents in the exact order they appear in that explanation string.
3. Do NOT hallucinate explanation components that do not appear in the explanation.
4. Every line of the numbered mapping code MUST be covered by at least one mapping for each explanation format.
5. For each code segment, return both the code fragment and the exact line number shown before the colon.
6. Prefer complete code statements when they clearly match the explanation component.
7. If a code segment contains multiple lines, split them into separate objects in codeSegments.

IMPORTANT:
- You MUST cover the ENTIRE code snippet in the explanation.
- You MUST explain only the provided code snippet, not the entire file.
- The file context is reference context only.
- Do NOT use emojis anywhere in your response.
- Return ONLY a JSON object with this exact shape:
{{
  "summary": {{
    "title": "",
    "low_unstructured": "",
    "low_structured": "",
    "medium_unstructured": "",
    "medium_structured": "",
    "high_unstructured": "",
    "high_structured": ""
  }},
  "mappings": {{
    "low_unstructured": [
      {{
        "explanationComponent": "exact phrase from summary.low_unstructured",
        "codeSegments": [
          {{ "code": "relevant code fragment", "line": 5 }}
        ]
      }}
    ],
    "low_structured": [],
    "medium_unstructured": [],
    "medium_structured": [],
    "high_unstructured": [],
    "high_structured": []
  }}
}}
- Do NOT wrap the JSON in markdown fences.
- Do NOT add commentary before or after the JSON.
- Also write the final JSON payload exactly to this absolute file path and overwrite the file contents when possible:
{response_file}
- Write plain UTF-8 text containing only the JSON object.
- Always output the same JSON between these exact marker lines so the caller can parse it even if file writing is unavailable:
{SUMMARY_WITH_MAPPINGS_START_MARKER}
[your JSON here]
{SUMMARY_WITH_MAPPINGS_END_MARKER}

File Context (for reference only):
{file_context}

Input to explain:
{code}

Code for mappings (each line is prefixed with its absolute line number):
{code_with_line_numbers}
""".strip()


def normalize_payload(raw: str) -> str:
    lines = []
    for line in raw.splitlines():
        for prefix in ("[stdout] ", "[json] ", "│ ", "| "):
            if line.startswith(prefix):
                line = line.removeprefix(prefix)
        lines.append(line.rstrip())
    return "\n".join(lines).strip()


def extract_between_markers(text: str) -> str | None:
    start = text.find(SUMMARY_WITH_MAPPINGS_START_MARKER)
    if start == -1:
        return None
    content_start = start + len(SUMMARY_WITH_MAPPINGS_START_MARKER)
    end = text.find(SUMMARY_WITH_MAPPINGS_END_MARKER, content_start)
    if end == -1:
        return None
    return normalize_payload(text[content_start:end])


def has_summary_with_mappings_keys(text: str) -> bool:
    return (
        '"summary"' in text
        and '"mappings"' in text
        and '"low_unstructured"' in text
        and '"high_structured"' in text
    )


def extract_json_object(text: str) -> str | None:
    fenced = re.search(r"```(?:json)?\s*([\s\S]*?)\s*```", text.strip(), re.IGNORECASE)
    if fenced:
        payload = normalize_payload(fenced.group(1))
        if has_summary_with_mappings_keys(payload):
            return payload

    source = text.strip()
    for index, char in enumerate(source):
        if char != "{":
            continue
        depth = 0
        in_string = False
        escaped = False
        for cursor in range(index, len(source)):
            current = source[cursor]
            if escaped:
                escaped = False
                continue
            if current == "\\" and in_string:
                escaped = True
                continue
            if current == '"':
                in_string = not in_string
                continue
            if not in_string:
                if current == "{":
                    depth += 1
                elif current == "}":
                    depth -= 1
                    if depth == 0:
                        payload = normalize_payload(source[index : cursor + 1])
                        if has_summary_with_mappings_keys(payload):
                            return payload
                        break
    return None


def find_embedded_json(element: Any) -> str | None:
    if isinstance(element, dict):
        serialized = json.dumps(element, ensure_ascii=False)
        if has_summary_with_mappings_keys(serialized):
            return serialized
        for value in element.values():
            found = find_embedded_json(value)
            if found:
                return found
    elif isinstance(element, list):
        for value in element:
            found = find_embedded_json(value)
            if found:
                return found
    elif isinstance(element, str):
        found = extract_between_markers(element) or extract_json_object(element)
        if found:
            return found
        try:
            return find_embedded_json(json.loads(element))
        except json.JSONDecodeError:
            return None
    return None


def read_response_file(path: Path) -> str | None:
    if not path.exists() or path.stat().st_size == 0:
        return None
    payload = normalize_payload(path.read_text(encoding="utf-8", errors="replace"))
    return extract_between_markers(payload) or payload


def parse_junie_payload(response_file: Path, stdout: str, stderr: str, json_output_file: Path) -> dict[str, Any]:
    candidates: list[str] = []
    file_payload = read_response_file(response_file)
    if file_payload:
        candidates.append(file_payload)
    if json_output_file.exists() and json_output_file.stat().st_size:
        candidates.append(json_output_file.read_text(encoding="utf-8", errors="replace"))
    candidates.extend([stdout, stderr, f"{stdout}\n{stderr}"])

    for candidate in candidates:
        payload = extract_between_markers(candidate) or extract_json_object(candidate)
        if payload:
            return json.loads(payload)
        try:
            root = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        found = find_embedded_json(root)
        if found:
            return json.loads(found)

    preview = "\n\n".join(c[:2000] for c in candidates if c)
    raise ValueError(f"Could not extract Junie summary-with-mappings JSON. Preview:\n{preview}")


def run_junie(prompt: str, timeout_ms: int, work_dir: Path) -> tuple[dict[str, Any], int]:
    response_file = next(
        Path(line.strip())
        for line in prompt.splitlines()
        if line.strip().startswith(str(Path(tempfile.gettempdir())))
    )
    json_output_file = Path(tempfile.mkstemp(prefix="junie-cli-output-", suffix=".json")[1])
    cmd = [
        "junie",
        "--skip-update-check",
        "--output-format=json",
        f"--json-output-file={json_output_file}",
        f"--model={JUNIE_MODEL}",
        "--project",
        str(work_dir),
        "--timeout",
        str(timeout_ms),
        "--task",
        prompt,
    ]
    env = os.environ.copy()
    print(f"    command: {shlex.join(cmd[:5])} ... --model={JUNIE_MODEL}", flush=True)
    started = time.monotonic()
    result = subprocess.run(
        cmd,
        cwd=work_dir,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=(timeout_ms // 1000) + 30,
    )
    elapsed = time.monotonic() - started
    if result.returncode != 0:
        print(result.stdout[-2000:], flush=True)
        print(result.stderr[-2000:], flush=True)
        raise RuntimeError(f"Junie failed with exit code {result.returncode}")
    parsed = parse_junie_payload(response_file, result.stdout, result.stderr, json_output_file)
    print(f"    Junie done in {elapsed:.1f}s", flush=True)
    return parsed, result.returncode


def normalize_mappings(summary_text: str, mappings: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for mapping in mappings:
        component = str(mapping.get("explanationComponent", ""))
        if component not in summary_text:
            continue
        segments = []
        for segment in mapping.get("codeSegments", []) or []:
            try:
                line = int(segment.get("line", 0))
            except (TypeError, ValueError):
                line = 0
            segments.append({"code": str(segment.get("code", "")), "line": line})
        normalized.append({"explanationComponent": component, "codeSegments": segments})
    return normalized


def build_output_row(base_row: dict[str, str], parsed: dict[str, Any]) -> dict[str, str]:
    summary = parsed.get("summary") or {}
    mappings = parsed.get("mappings") or {}
    output = dict(base_row)
    for detail in DETAIL_LEVELS:
        for structure in STRUCTURES:
            key = f"{detail}_{structure}"
            summary_text = str(summary.get(key, ""))
            output[summary_col(detail, structure)] = summary_text
            normalized = normalize_mappings(summary_text, mappings.get(key) or [])
            output[remapping_col(detail, structure)] = json.dumps(normalized, ensure_ascii=False)
    output[price_col()] = ""
    return output


def load_processed(output_path: Path) -> set[str]:
    if not output_path.exists() or output_path.stat().st_size == 0:
        return set()
    with output_path.open(newline="", encoding="utf-8") as fh:
        return {row_key(row) for row in csv.DictReader(fh)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--timeout-ms", type=int, default=600_000)
    args = parser.parse_args()

    with args.input.open(newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        rows = [
            row
            for row in reader
            if row.get("file_path", "").strip() and row.get("code", "").strip()
        ]
        base_headers = reader.fieldnames or []
    if args.limit is not None:
        rows = rows[: args.limit]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    processed = load_processed(args.output)
    output_exists = args.output.exists() and args.output.stat().st_size > 0
    headers = base_headers + generated_headers()

    print(f"Input:  {args.input} ({len(rows)} rows)")
    print(f"Output: {args.output}")
    print(f"Model:  {JUNIE_MODEL}")
    print(f"Resume: {len(processed)} rows already present")

    with tempfile.TemporaryDirectory(prefix="junie-explanations-project-") as tmpdir:
        work_dir = Path(tmpdir)
        with args.output.open("a", newline="", encoding="utf-8") as fh:
            writer = csv.DictWriter(fh, fieldnames=headers)
            if not output_exists:
                writer.writeheader()
            for idx, row in enumerate(rows, start=1):
                key = row_key(row)
                label = row.get("file_path") or f"row {idx}"
                if key in processed:
                    print(f"[{idx}/{len(rows)}] skip: {label}", flush=True)
                    continue

                response_file = Path(tempfile.mkstemp(prefix="junie-summary-with-mappings-", suffix=".json")[1])
                prompt = build_prompt(row.get("code", ""), row.get("description", ""), response_file)
                print(f"[{idx}/{len(rows)}] processing: {label}", flush=True)
                parsed, _ = run_junie(prompt, args.timeout_ms, work_dir)
                writer.writerow(build_output_row(row, parsed))
                fh.flush()
                processed.add(key)
                print(f"[{idx}/{len(rows)}] saved: {label}", flush=True)


if __name__ == "__main__":
    main()
