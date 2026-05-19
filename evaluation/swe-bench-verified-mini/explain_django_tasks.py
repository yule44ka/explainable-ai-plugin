#!/usr/bin/env python3
"""Prepare SWE-bench task copies and insert Junie explanations."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any

from list_patch_files import files_from_patch


DEFAULT_DATASET = "swe-bench-verified-mini.jsonl"
DEFAULT_SOURCE_REPO = "repos/django__django"
DEFAULT_OUTPUT_ROOT = "repos-explained"
DEFAULT_REPO = "django/django"
DEFAULT_JUNIE_MODEL = "gemini-3.1-flash-lite-preview"
DEFAULT_JUNIE_RETRIES = 2
DEFAULT_JUNIE_RETRY_DELAY = 30

SUMMARY_START_MARKER = "BEGIN_EXPLAINABLE_AI_SUMMARY_WITH_MAPPINGS_JSON"
SUMMARY_END_MARKER = "END_EXPLAINABLE_AI_SUMMARY_WITH_MAPPINGS_JSON"
MAX_JUNIE_PROMPT_CHARS = 60000

PRINT_LOCK = threading.Lock()


def log(message: str, quiet: bool = False) -> None:
    if not quiet:
        with PRINT_LOCK:
            print(message, flush=True)


def load_dataset(path: Path) -> list[dict[str, Any]]:
    text = path.read_text(encoding="utf-8")
    stripped = text.lstrip()
    if stripped.startswith("["):
        rows = json.loads(text)
        if not isinstance(rows, list):
            raise ValueError(f"{path} must contain a JSON array or JSONL rows")
        return [dict(row) for row in rows]

    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
    return rows


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


def run_command(
    args: list[str],
    cwd: Path,
    timeout: int | None = None,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
    )


def run_streaming_command(
    args: list[str],
    cwd: Path,
    timeout: int | None = None,
    env: dict[str, str] | None = None,
    quiet: bool = False,
    log_prefix: str = "",
) -> subprocess.CompletedProcess[str]:
    process = subprocess.Popen(
        args,
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=1,
    )
    stdout_chunks: list[str] = []
    stderr_chunks: list[str] = []

    def read_pipe(pipe: Any, chunks: list[str], output: Any, label: str) -> None:
        for line in iter(pipe.readline, ""):
            chunks.append(line)
            if not quiet:
                prefix = f"{log_prefix}{label}: " if log_prefix else f"{label}: "
                with PRINT_LOCK:
                    output.write(prefix + line)
                    output.flush()
        pipe.close()

    stdout_thread = threading.Thread(
        target=read_pipe,
        args=(process.stdout, stdout_chunks, sys.stdout, "stdout"),
        daemon=True,
    )
    stderr_thread = threading.Thread(
        target=read_pipe,
        args=(process.stderr, stderr_chunks, sys.stderr, "stderr"),
        daemon=True,
    )
    stdout_thread.start()
    stderr_thread.start()

    try:
        returncode = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        stdout_thread.join(timeout=5)
        stderr_thread.join(timeout=5)
        timeout_message = f"Command timed out after {timeout} seconds\n"
        stderr_chunks.append(timeout_message)
        if not quiet:
            with PRINT_LOCK:
                sys.stderr.write((f"{log_prefix}stderr: " if log_prefix else "stderr: ") + timeout_message)
                sys.stderr.flush()
        return subprocess.CompletedProcess(
            args=args,
            returncode=124,
            stdout="".join(stdout_chunks),
            stderr="".join(stderr_chunks),
        )

    stdout_thread.join()
    stderr_thread.join()
    return subprocess.CompletedProcess(
        args=args,
        returncode=returncode,
        stdout="".join(stdout_chunks),
        stderr="".join(stderr_chunks),
    )


def git(repo_dir: Path, *args: str, timeout: int | None = None) -> subprocess.CompletedProcess[str]:
    return run_command(["git", *args], cwd=repo_dir, timeout=timeout)


def ensure_checkout(repo_dir: Path, base_commit: str) -> None:
    result = git(repo_dir, "checkout", "--detach", base_commit, timeout=180)
    if result.returncode != 0:
        raise RuntimeError(f"git checkout {base_commit} failed:\n{result.stderr}")

    result = git(repo_dir, "reset", "--hard", timeout=180)
    if result.returncode != 0:
        raise RuntimeError(f"git reset failed:\n{result.stderr}")

    result = git(repo_dir, "clean", "-fdx", timeout=180)
    if result.returncode != 0:
        raise RuntimeError(f"git clean failed:\n{result.stderr}")


def copy_repo(source_repo: Path, target_repo: Path, overwrite: bool) -> None:
    if target_repo.exists():
        if not overwrite:
            raise FileExistsError(f"{target_repo} already exists; pass --overwrite to rebuild it")
        shutil.rmtree(target_repo)

    target_repo.parent.mkdir(parents=True, exist_ok=True)
    result = run_command(
        ["git", "clone", "--no-hardlinks", str(source_repo), str(target_repo)],
        cwd=source_repo.parent,
        timeout=900,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git clone failed:\n{result.stderr}")


def existing_patch_files(task: dict[str, Any], repo_dir: Path) -> list[str]:
    files: list[str] = []
    for path in files_from_patch(task.get("patch", "")):
        full_path = repo_dir / path
        if full_path.is_file() and path not in files:
            files.append(path)
    return files


def candidate_random_files(repo_dir: Path, glob_pattern: str) -> list[str]:
    root = repo_dir
    django_root = repo_dir / "django"
    if django_root.exists():
        root = django_root
    if not root.exists():
        return []
    return sorted(
        str(path.relative_to(repo_dir))
        for path in root.rglob(glob_pattern)
        if path.is_file()
    )


def selected_files_for_task(
    task: dict[str, Any],
    repo_dir: Path,
    random_files_count: int,
    random_file_glob: str,
    seed: int,
) -> dict[str, list[str]]:
    patch_files = existing_patch_files(task, repo_dir)
    patch_set = set(patch_files)
    candidates = [
        path for path in candidate_random_files(repo_dir, random_file_glob)
        if path not in patch_set
    ]

    rng = random.Random(f"{seed}:{task.get('instance_id', '')}")
    random_files = rng.sample(candidates, min(random_files_count, len(candidates)))

    combined = patch_files + [path for path in random_files if path not in patch_set]
    return {
        "patch_files": patch_files,
        "random_files": random_files,
        "combined_files": combined,
    }


def numbered_code(code: str, real_start_line: int = 1) -> str:
    return "\n".join(
        f"{index + real_start_line}: {line}"
        for index, line in enumerate(code.splitlines())
    )


def build_task_junie_prompt(
    repo_dir: Path,
    relative_paths: list[str],
    response_file: Path,
) -> str:
    files_payload = []
    for relative_path in relative_paths:
        content = (repo_dir / relative_path).read_text(encoding="utf-8", errors="replace")
        files_payload.append(
            {
                "path": relative_path,
                "content": content,
                "numbered_content": numbered_code(content),
            }
        )

    return f"""
You are an expert code explainer and code-to-explanation mapper. Generate explanations and mappings for ALL files listed below in ONE response.

The explanation of each selected code segment should cover its purpose, foundations of way of implementation, relation to the requested change, previous and new behavior, algorithmic logic, implementation and architectural decisions, use of abstractions, and the final effect of the segment.

For EACH file, generate exactly one explanation of the whole file:
- high_structured: 8-10 bullet points, high-detail, as a single string
- Use as many bullet points as needed to explain the file clearly and in high details and cover the mapped code.
- Use "•" for first-level bullets and "◦" for second-level bullets when useful.
- Bullets must be separated by "\n". Never return an array for the explanation text.

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


def split_relative_paths_for_prompt(
    repo_dir: Path,
    relative_paths: list[str],
    response_file: Path,
    max_chars: int = MAX_JUNIE_PROMPT_CHARS,
) -> list[list[str]]:
    if not relative_paths:
        return []
    prompt = build_task_junie_prompt(repo_dir=repo_dir, relative_paths=relative_paths, response_file=response_file)
    if len(prompt) <= max_chars or len(relative_paths) == 1:
        return [relative_paths]

    midpoint = max(1, len(relative_paths) // 2)
    left = split_relative_paths_for_prompt(repo_dir, relative_paths[:midpoint], response_file, max_chars=max_chars)
    right = split_relative_paths_for_prompt(repo_dir, relative_paths[midpoint:], response_file, max_chars=max_chars)
    return left + right


def extract_json_between_markers(text: str) -> str | None:
    pattern = re.compile(
        re.escape(SUMMARY_START_MARKER) + r"\s*(.*?)\s*" + re.escape(SUMMARY_END_MARKER),
        re.DOTALL,
    )
    match = pattern.search(text)
    if match:
        return match.group(1).strip()
    return None


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
        # Junie occasionally emits an extra string close after an object in
        # codeSegments, e.g. {"line": 37, "code": "..."},"},. Fix this
        # before escaping control characters, otherwise the stray quote makes
        # subsequent structural JSON look like a string.
        repaired = re.sub(r'\},"\}\s*,', "},", text)
        repaired = escape_control_chars_in_json_strings(repaired)
        try:
            return json.loads(repaired)
        except json.JSONDecodeError:
            return json.loads(repaired)


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


def run_junie_batch(
    repo_dir: Path,
    artifacts_dir: Path,
    relative_paths: list[str],
    model: str,
    timeout: int,
    quiet: bool,
    log_prefix: str,
    retries: int,
    retry_delay: int,
    response_path: Path,
) -> dict[str, Any]:
    prompt = build_task_junie_prompt(
        repo_dir=repo_dir,
        relative_paths=relative_paths,
        response_file=response_path,
    )
    prompt_path = artifacts_dir / "prompt.txt"
    prompt_path.write_text(prompt, encoding="utf-8")
    prompt_summary_path = artifacts_dir / "prompt_summary_with_mappings.txt"
    prompt_summary_path.write_text(prompt, encoding="utf-8")
    append_task_log(artifacts_dir, f"Saved final prompt: {prompt_summary_path}", dry_run=False)

    agent_logs_dir = artifacts_dir / "agent_logs"
    agent_logs_dir.mkdir(parents=True, exist_ok=True)
    append_task_log(artifacts_dir, f"Agent logs directory: {agent_logs_dir}", dry_run=False)
    json_output_path = agent_logs_dir / "junie-output.json"
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
    redacted_command = [
        "--auth=<redacted>" if arg.startswith("--auth=") else arg
        for arg in command
    ]
    save_json(
        artifacts_dir / "junie_command.json",
        {
            "cwd": str(repo_dir),
            "argv": redacted_command,
            "model": model,
            "json_output_file": str(json_output_path),
            "response_file": str(response_path),
            "timeout_seconds": timeout,
            "auth_source": "JUNIE_API_KEY" if env.get("JUNIE_API_KEY") else None,
        },
    )

    result: subprocess.CompletedProcess[str] | None = None
    elapsed = 0.0
    for attempt in range(retries + 1):
        if attempt:
            append_task_log(
                artifacts_dir,
                f"Retrying Junie attempt {attempt + 1}/{retries + 1} after {retry_delay}s",
                dry_run=False,
            )
            time.sleep(retry_delay)
        started_at = time.monotonic()
        result = run_streaming_command(
            command,
            cwd=repo_dir,
            timeout=timeout,
            env=env,
            quiet=quiet,
            log_prefix=log_prefix,
        )
        elapsed += time.monotonic() - started_at
        current_json_output = ""
        if json_output_path.exists():
            current_json_output = json_output_path.read_text(encoding="utf-8", errors="replace")
        if result.returncode == 0 or not is_retryable_junie_failure(result, current_json_output):
            break

    assert result is not None

    (artifacts_dir / "stdout.txt").write_text(result.stdout, encoding="utf-8", errors="replace")
    (artifacts_dir / "stderr.txt").write_text(result.stderr, encoding="utf-8", errors="replace")
    (agent_logs_dir / "stdout.log").write_text(result.stdout, encoding="utf-8", errors="replace")
    (agent_logs_dir / "stderr.log").write_text(result.stderr, encoding="utf-8", errors="replace")
    (artifacts_dir / "returncode.txt").write_text(str(result.returncode), encoding="utf-8")

    json_output = ""
    if json_output_path.exists():
        json_output = json_output_path.read_text(encoding="utf-8", errors="replace")

    if result.returncode != 0:
        raise RuntimeError(f"Junie failed for task with code {result.returncode}: {result.stderr}")

    parsed = load_task_summary_with_mappings(response_path, result.stdout, result.stderr, json_output)
    (artifacts_dir / "summary_with_mappings.json").write_text(
        json.dumps(parsed, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (artifacts_dir / "elapsed_seconds.txt").write_text(f"{elapsed:.3f}", encoding="utf-8")
    response_path.unlink(missing_ok=True)
    return parsed


def generate_task_with_junie(
    repo_dir: Path,
    artifacts_dir: Path,
    relative_paths: list[str],
    model: str,
    timeout: int,
    quiet: bool,
    log_prefix: str,
    retries: int,
    retry_delay: int,
) -> dict[str, Any]:
    artifacts_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix="junie-summary-with-mappings-", suffix=".json", delete=False) as fh:
        response_path = Path(fh.name)

    chunk_paths = split_relative_paths_for_prompt(repo_dir, relative_paths, response_path)
    if len(chunk_paths) <= 1:
        return run_junie_batch(
            repo_dir=repo_dir,
            artifacts_dir=artifacts_dir,
            relative_paths=relative_paths,
            model=model,
            timeout=timeout,
            quiet=quiet,
            log_prefix=log_prefix,
            retries=retries,
            retry_delay=retry_delay,
            response_path=response_path,
        )

    append_task_log(
        artifacts_dir,
        f"Prompt too large for one Junie run; splitting into {len(chunk_paths)} chunk(s).",
        dry_run=False,
    )
    combined_files: list[dict[str, Any]] = []
    chunk_results_dir = artifacts_dir / "chunks"
    for index, chunk_relative_paths in enumerate(chunk_paths, start=1):
        chunk_artifacts_dir = chunk_results_dir / f"chunk-{index:02d}"
        chunk_artifacts_dir.mkdir(parents=True, exist_ok=True)
        chunk_response_path = chunk_artifacts_dir / "response.json"
        parsed = run_junie_batch(
            repo_dir=repo_dir,
            artifacts_dir=chunk_artifacts_dir,
            relative_paths=chunk_relative_paths,
            model=model,
            timeout=timeout,
            quiet=quiet,
            log_prefix=f"{log_prefix}[chunk {index}/{len(chunk_paths)}] ",
            retries=retries,
            retry_delay=retry_delay,
            response_path=chunk_response_path,
        )
        combined_files.extend(
            entry for entry in parsed.get("files", []) if isinstance(entry, dict)
        )

    parsed = {"files": combined_files}
    (artifacts_dir / "summary_with_mappings.json").write_text(
        json.dumps(parsed, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (artifacts_dir / "prompt.txt").write_text(
        build_task_junie_prompt(repo_dir=repo_dir, relative_paths=relative_paths, response_file=response_path),
        encoding="utf-8",
    )
    (artifacts_dir / "prompt_summary_with_mappings.txt").write_text(
        (artifacts_dir / "prompt.txt").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    response_path.unlink(missing_ok=True)
    return parsed


def is_retryable_junie_failure(result: subprocess.CompletedProcess[str], json_output: str = "") -> bool:
    output = f"{result.stdout}\n{result.stderr}\n{json_output}"
    retryable_markers = [
        "Unable to connect to LLM service",
        "504 Gateway Timeout",
        "Gateway Timeout",
        "Command timed out",
        "Can not parse response",
        "GoogleContent",
        "Field 'parts' is required",
    ]
    return any(marker in output for marker in retryable_markers)


def comment_style_for(path: str) -> tuple[str, str, str | None]:
    suffix = Path(path).suffix.lower()
    if suffix in {".py", ".sh", ".cfg", ".ini", ".toml", ".yml", ".yaml"}:
        return ("line", "#", None)
    if suffix in {".js", ".ts", ".java", ".kt", ".c", ".h", ".cpp", ".css"}:
        return ("line", "//", None)
    if suffix in {".html", ".htm", ".xml", ".xhtml", ".svg"}:
        return ("block", "<!--", "-->")
    return ("block", "/*", "*/")


def remove_line_number_references(text: str) -> str:
    lines = []
    for line in text.splitlines():
        cleaned = re.sub(r"(?i)^\s*(?:line|lines)\s+\d+(?:\s*[-\u2013]\s*\d+)?\s*[:,-]?\s*", "", line)
        cleaned = re.sub(r"(?i)\s*\((?:line|lines)\s+\d+(?:\s*[-\u2013]\s*\d+)?\)", "", cleaned)
        cleaned = re.sub(r"(?i)\s*,?\s*(?:on|at|in)\s+(?:line|lines)\s+\d+(?:\s*[-\u2013]\s*\d+)?\b", "", cleaned)
        cleaned = re.sub(r"(?i)\s*,?\s*(?:line|lines)\s+\d+(?:\s*[-\u2013]\s*\d+)?\b", "", cleaned)
        lines.append(cleaned.rstrip())
    return "\n".join(lines)


def line_number_from_text(text: str) -> int | None:
    match = re.match(r"\s*(\d+)\s*:", text)
    if match:
        return int(match.group(1))
    return None


def segment_line_number(segment: dict[str, Any]) -> int | None:
    for key in ("line", "lineNumber", "line_number", "startLine", "start_line", "lineStart"):
        raw_line = segment.get(key)
        if str(raw_line).isdigit():
            return int(raw_line)
    for key in ("code", "codeFragment", "code_segment", "codeSegment"):
        raw_code = segment.get(key)
        if isinstance(raw_code, str):
            line_number = line_number_from_text(raw_code)
            if line_number is not None:
                return line_number
    return None


def segment_code_text(segment: dict[str, Any]) -> str:
    for key in ("code", "codeFragment", "code_fragment", "fragment", "code_segment", "codeSegment"):
        value = segment.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return ""


def mapping_code_segments(mapping: dict[str, Any]) -> list[dict[str, Any]]:
    segments = mapping.get("codeSegments")
    if isinstance(segments, list):
        return [segment for segment in segments if isinstance(segment, dict)]

    segment = mapping.get("codeSegment")
    if isinstance(segment, dict):
        return [segment]
    if isinstance(segment, str):
        return [{"code": segment}]

    if any(key in mapping for key in ("code", "codeFragment", "code_fragment", "fragment", "line", "lineNumber")):
        return [mapping]

    return []


def mapping_explanation_component(mapping: dict[str, Any]) -> str:
    for key in ("explanationComponent", "component", "explanation", "summaryComponent"):
        value = mapping.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return ""


def summary_components(summary_text: str) -> list[str]:
    components: list[str] = []
    current: list[str] = []
    for raw_line in summary_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith(("•", "-", "*")):
            if current:
                components.append(" ".join(current).strip())
            current = [line]
        elif current:
            current.append(line)
    if current:
        components.append(" ".join(current).strip())
    return components


def line_indent(line: str) -> str:
    match = re.match(r"[ \t]*", line)
    return match.group(0) if match else ""


def format_comment(style: tuple[str, str, str | None], indent: str, explanation: str) -> str:
    kind, prefix, suffix = style
    comment_lines = []
    for line in explanation.splitlines():
        if not line.strip():
            continue
        if kind == "line":
            comment_lines.append(f"{indent}{prefix} {line.rstrip()}\n")
        else:
            comment_lines.append(f"{indent}{prefix} {line.rstrip()} {suffix}\n")
    return "".join(comment_lines)


def normalized_code_line(line: str) -> str:
    return line.strip()


def find_code_fragment_line(lines: list[str], fragment: str) -> int | None:
    fragment_lines = [line for line in fragment.splitlines() if line.strip()]
    if not fragment_lines:
        return None

    normalized_lines = [normalized_code_line(line) for line in lines]
    normalized_fragment = [normalized_code_line(line) for line in fragment_lines]
    first = normalized_fragment[0]
    if not first:
        return None

    fragment_len = len(normalized_fragment)
    for idx, line in enumerate(normalized_lines):
        if line != first:
            continue
        if fragment_len == 1:
            return idx + 1
        candidate = normalized_lines[idx:idx + fragment_len]
        if candidate == normalized_fragment:
            return idx + 1

    for idx, line in enumerate(normalized_lines):
        if first and (first in line or line in first):
            return idx + 1

    return None


def build_comment_insertions(
    lines: list[str],
    relative_path: str,
    mappings: list[dict[str, Any]],
    summary_text: str = "",
) -> list[dict[str, Any]]:
    style = comment_style_for(relative_path)
    insertions: list[dict[str, Any]] = []
    fallback_components = summary_components(summary_text)
    if not mappings and fallback_components:
        indent = line_indent(lines[0]) if lines else ""
        return [{"line_index": 0, "text": format_comment(style, indent, "\n".join(fallback_components))}]

    for index, mapping in enumerate(mappings):
        segments = mapping_code_segments(mapping)
        line_numbers: list[int] = []
        for segment in segments:
            line_number = segment_line_number(segment)
            if line_number is None:
                line_number = find_code_fragment_line(lines, segment_code_text(segment))
            if line_number is not None:
                line_numbers.append(line_number)
        if not line_numbers:
            if not lines and (mapping_explanation_component(mapping).strip() or index < len(fallback_components)):
                line_numbers.append(1)
            else:
                continue

        line_index = 0 if not lines else max(0, min(min(line_numbers) - 1, len(lines) - 1))
        explanation = remove_line_number_references(mapping_explanation_component(mapping)).strip()
        if not explanation and index < len(fallback_components):
            explanation = remove_line_number_references(fallback_components[index]).strip()
        if not explanation:
            continue

        insertions.append(
            {
                "line_index": line_index,
                "text": format_comment(style, line_indent(lines[line_index]) if lines else "", explanation),
            }
        )

    grouped: dict[int, list[str]] = {}
    for insertion in insertions:
        grouped.setdefault(insertion["line_index"], []).append(insertion["text"])

    return [
        {"line_index": line_index, "text": "".join(texts)}
        for line_index, texts in grouped.items()
    ]


def insert_high_detail_comments(
    repo_dir: Path,
    relative_path: str,
    mappings: list[dict[str, Any]],
    summary_text: str = "",
) -> int:
    file_path = repo_dir / relative_path
    lines = file_path.read_text(encoding="utf-8", errors="replace").splitlines(keepends=True)
    insertions = build_comment_insertions(lines, relative_path, mappings, summary_text=summary_text)
    if not insertions:
        return 0

    for insertion in sorted(insertions, key=lambda item: item["line_index"], reverse=True):
        line_index = insertion["line_index"]
        lines[line_index:line_index] = [insertion["text"]]

    file_path.write_text("".join(lines), encoding="utf-8")
    return len(insertions)


def safe_artifact_name(relative_path: str) -> str:
    return relative_path.replace("/", "__")


def write_per_file_summary_artifacts(
    task_artifacts_dir: Path,
    file_entry: dict[str, Any],
) -> None:
    relative_path = str(file_entry.get("path", ""))
    if not relative_path:
        return
    artifacts_dir = task_artifacts_dir / "files" / safe_artifact_name(relative_path)
    artifacts_dir.mkdir(parents=True, exist_ok=True)
    (artifacts_dir / "summary_with_mappings.json").write_text(
        json.dumps(file_entry, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def process_task_explanations(
    repo_dir: Path,
    relative_paths: list[str],
    task_artifacts_dir: Path,
    model: str,
    timeout: int,
    dry_run: bool,
    prepare_only: bool,
    quiet: bool,
    log_prefix: str,
    retries: int,
    retry_delay: int,
) -> list[dict[str, Any]]:
    if dry_run:
        return [
            {"file": relative_path, "status": "dry_run", "inserted_count": 0}
            for relative_path in relative_paths
        ]
    if prepare_only:
        return [
            {"file": relative_path, "status": "prepared", "inserted_count": 0}
            for relative_path in relative_paths
        ]

    summary_with_mappings = generate_task_with_junie(
        repo_dir=repo_dir,
        artifacts_dir=task_artifacts_dir,
        relative_paths=relative_paths,
        model=model,
        timeout=timeout,
        quiet=quiet,
        log_prefix=log_prefix,
        retries=retries,
        retry_delay=retry_delay,
    )
    entries_by_path = {
        str(entry.get("path", "")): entry
        for entry in summary_with_mappings.get("files", [])
        if isinstance(entry, dict)
    }

    file_results: list[dict[str, Any]] = []
    for relative_path in relative_paths:
        file_entry = entries_by_path.get(relative_path)
        if not file_entry:
            file_results.append(
                {"file": relative_path, "status": "missing_in_junie_response", "inserted_count": 0}
            )
            continue

        write_per_file_summary_artifacts(task_artifacts_dir, file_entry)
        high_structured = file_entry.get("mappings", {}).get("high_structured", [])
        if not isinstance(high_structured, list):
            high_structured = []
        summary_text = str(file_entry.get("summary", {}).get("high_structured", ""))
        inserted_count = insert_high_detail_comments(repo_dir, relative_path, high_structured, summary_text)
        file_results.append({"file": relative_path, "status": "ok", "inserted_count": inserted_count})

    return file_results


def save_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def append_task_log(task_artifacts_dir: Path, message: str, dry_run: bool) -> None:
    if dry_run:
        return
    task_artifacts_dir.mkdir(parents=True, exist_ok=True)
    line = message.rstrip("\n") + "\n"
    for log_name in ("task.log", "process.log"):
        with (task_artifacts_dir / log_name).open("a", encoding="utf-8") as fh:
            fh.write(line)


def requested_instance_ids(raw_values: list[str]) -> list[str]:
    ids: list[str] = []
    for raw_value in raw_values:
        for value in raw_value.split(","):
            instance_id = value.strip()
            if instance_id and instance_id not in ids:
                ids.append(instance_id)
    return ids


def process_task(task: dict[str, Any], args: argparse.Namespace, root_dir: Path) -> dict[str, Any]:
    instance_id = task["instance_id"]
    source_repo = (root_dir / args.source_repo).resolve()
    task_dir = (root_dir / args.output_root / instance_id).resolve()
    repo_dir = task_dir / "repo"
    artifacts_dir = task_dir / "_explanations"

    if not args.dry_run:
        if task_dir.exists() and args.overwrite:
            shutil.rmtree(task_dir)
        if task_dir.exists() and not repo_dir.exists():
            raise FileExistsError(
                f"{task_dir} exists but does not contain repo/; pass --overwrite to rebuild it"
            )

    log(f"[{instance_id}] preparing repository", args.quiet)
    append_task_log(artifacts_dir, f"[{instance_id}] preparing repository", args.dry_run)
    if not args.dry_run:
        copy_repo(source_repo, repo_dir, args.overwrite)
        append_task_log(artifacts_dir, f"[{instance_id}] copied {source_repo} -> {repo_dir}", args.dry_run)
        ensure_checkout(repo_dir, task["base_commit"])
        append_task_log(artifacts_dir, f"[{instance_id}] checked out {task['base_commit']}", args.dry_run)
    else:
        repo_dir = source_repo

    selected = selected_files_for_task(
        task=task,
        repo_dir=repo_dir,
        random_files_count=args.random_files_count,
        random_file_glob=args.random_file_glob,
        seed=args.seed,
    )

    task_info = {
        "instance_id": instance_id,
        "repo": task.get("repo"),
        "base_commit": task.get("base_commit"),
        "selected_files": selected,
    }
    if not args.dry_run:
        save_json(artifacts_dir / "task_info.json", task_info)
        save_json(artifacts_dir / "selected_files.json", selected)
        append_task_log(
            artifacts_dir,
            f"[{instance_id}] patch files: {selected['patch_files']}",
            args.dry_run,
        )
        append_task_log(
            artifacts_dir,
            f"[{instance_id}] random files: {selected['random_files']}",
            args.dry_run,
        )
        append_task_log(
            artifacts_dir,
            f"[{instance_id}] combined files: {selected['combined_files']}",
            args.dry_run,
        )
        save_json(
            artifacts_dir / "run_config.json",
            {
                "dataset": args.dataset,
                "source_repo": args.source_repo,
                "output_root": args.output_root,
                "offset": args.offset,
                "limit": args.limit,
                "workers": args.workers,
                "random_files_count": args.random_files_count,
                "random_file_glob": args.random_file_glob,
                "seed": args.seed,
                "junie_model": args.junie_model,
                "junie_timeout": args.junie_timeout,
                "junie_retries": args.junie_retries,
                "junie_retry_delay": args.junie_retry_delay,
                "prepare_only": args.prepare_only,
                "dry_run": args.dry_run,
            },
        )
    else:
        log(json.dumps(task_info, ensure_ascii=False), args.quiet)

    combined_files = selected["combined_files"]
    log(f"[{instance_id}] explaining {len(combined_files)} file(s) in one Junie run", args.quiet)
    append_task_log(
        artifacts_dir,
        f"[{instance_id}] explaining {len(combined_files)} file(s) in one Junie run: {combined_files}",
        args.dry_run,
    )
    file_results = process_task_explanations(
        repo_dir=repo_dir,
        relative_paths=combined_files,
        task_artifacts_dir=artifacts_dir,
        model=args.junie_model,
        timeout=args.junie_timeout,
        dry_run=args.dry_run,
        prepare_only=args.prepare_only,
        quiet=args.quiet,
        log_prefix=f"[{instance_id}] ",
        retries=args.junie_retries,
        retry_delay=args.junie_retry_delay,
    )
    for file_result in file_results:
        append_task_log(artifacts_dir, f"[{instance_id}] file result: {file_result}", args.dry_run)

    result = {
        "instance_id": instance_id,
        "status": "ok",
        "repo_dir": str(repo_dir),
        "file_results": file_results,
    }
    if not args.dry_run:
        save_json(artifacts_dir / "result.json", result)
        append_task_log(artifacts_dir, f"[{instance_id}] done", args.dry_run)
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Create explained SWE-bench task repositories.",
    )
    parser.add_argument("--dataset", default=DEFAULT_DATASET)
    parser.add_argument("--source-repo", default=DEFAULT_SOURCE_REPO)
    parser.add_argument("--repo", default=DEFAULT_REPO, help="Dataset repo filter, e.g. django/django or sphinx-doc/sphinx.")
    parser.add_argument("--output-root", default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument(
        "--instance-id",
        action="append",
        default=[],
        help=(
            "Process specific task instance id(s), e.g. django__django-12155. "
            "May be passed multiple times or as a comma-separated list."
        ),
    )
    parser.add_argument("--offset", type=int, default=0, help="Skip this many selected tasks.")
    parser.add_argument("--limit", type=int, default=None, help="Process at most this many selected tasks.")
    parser.add_argument("--workers", type=int, default=1, help="Number of task-level worker threads.")
    parser.add_argument("--random-files-count", type=int, default=0)
    parser.add_argument("--random-file-glob", default="*.py")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--junie-model", default=DEFAULT_JUNIE_MODEL)
    parser.add_argument("--junie-timeout", type=int, default=600)
    parser.add_argument("--junie-retries", type=int, default=DEFAULT_JUNIE_RETRIES)
    parser.add_argument("--junie-retry-delay", type=int, default=DEFAULT_JUNIE_RETRY_DELAY)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="Copy repositories, checkout base commits, and save selected files without running Junie.",
    )
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--quiet", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    root_dir = Path(__file__).resolve().parent
    load_dotenv(root_dir.parent / ".env", override=False)
    load_dotenv(root_dir / ".env", override=False)

    if args.random_files_count < 0:
        raise SystemExit("--random-files-count must be >= 0")
    if args.workers < 1:
        raise SystemExit("--workers must be >= 1")

    dataset_path = (root_dir / args.dataset).resolve()
    rows = load_dataset(dataset_path)
    selected_tasks = [
        row for row in rows
        if row.get("repo") == args.repo
    ]
    requested_ids = requested_instance_ids(args.instance_id)
    if requested_ids:
        tasks_by_id = {task["instance_id"]: task for task in selected_tasks}
        missing_ids = [instance_id for instance_id in requested_ids if instance_id not in tasks_by_id]
        if missing_ids:
            raise SystemExit(f"Unknown {args.repo} instance id(s): {', '.join(missing_ids)}")
        requested_set = set(requested_ids)
        batch = [task for task in selected_tasks if task["instance_id"] in requested_set]
    else:
        batch = selected_tasks[args.offset:]
        if args.limit is not None:
            batch = batch[:args.limit]

    if requested_ids:
        log(
            f"Selected {len(batch)} {args.repo} task(s) by instance id from {len(selected_tasks)} available",
            args.quiet,
        )
    else:
        log(f"Selected {len(batch)} {args.repo} task(s) from {len(selected_tasks)} available", args.quiet)
    if not batch:
        return 0

    results: list[dict[str, Any]] = []
    failures = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        future_to_task = {
            executor.submit(process_task, task, args, root_dir): task
            for task in batch
        }
        for future in concurrent.futures.as_completed(future_to_task):
            task = future_to_task[future]
            instance_id = task.get("instance_id", "<unknown>")
            try:
                result = future.result()
                results.append(result)
                log(f"[{instance_id}] done", args.quiet)
            except Exception as exc:
                failures += 1
                result = {"instance_id": instance_id, "status": "failed", "error": str(exc)}
                results.append(result)
                if not args.dry_run:
                    task_dir = root_dir / args.output_root / instance_id
                    save_json(task_dir / "_explanations" / "result.json", result)
                    append_task_log(
                        task_dir / "_explanations",
                        f"[{instance_id}] failed: {exc}",
                        dry_run=False,
                    )
                log(f"[{instance_id}] failed: {exc}", args.quiet)

    summary_slug = args.repo.replace("/", "__").replace("-", "_")
    summary_path = root_dir / args.output_root / f"_last_explain_{summary_slug}_tasks_summary.json"
    if not args.dry_run:
        save_json(summary_path, results)

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
