#!/usr/bin/env python3
"""Run Junie on SWE-bench style tasks and evaluate edited repositories."""

from __future__ import annotations

import argparse
import ast
import concurrent.futures
import csv
import datetime as dt
import json
import os
import shutil
import shlex
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any


DEFAULT_DATASET = "swe-bench-verified-mini.jsonl"
DEFAULT_REPOS_DIR = "repos"
DEFAULT_REPOS_EXPLAINED_DIR = "repos-explained"
DEFAULT_RESULTS_DIR = "results/junie"
DEFAULT_JUNIE_COMMAND = "junie --model gemini-3-flash-preview"
SPHINX_CONSTRAINTS_FILE = Path(__file__).resolve().parent / "requirements-sphinx39-constraints.txt"


PRINT_LOCK = threading.Lock()


def log(message: str, quiet: bool = False) -> None:
    if not quiet:
        with PRINT_LOCK:
            print(message, flush=True)


def utc_now() -> str:
    return dt.datetime.now(dt.UTC).isoformat()


def load_dotenv(path: Path, override: bool = False) -> dict[str, str]:
    loaded: dict[str, str] = {}
    if not path.exists():
        return loaded
    for raw_line in path.read_text(encoding="utf-8").splitlines():
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


def repo_slug(repo_name: str) -> str:
    return repo_name.replace("/", "__")


def run_command(
    args: list[str] | str,
    cwd: Path,
    timeout: int | None = None,
    env: dict[str, str] | None = None,
    shell: bool = False,
    stream: bool = False,
    log_prefix: str = "",
) -> subprocess.CompletedProcess[str]:
    if stream:
        return run_streaming_command(
            args=args,
            cwd=cwd,
            timeout=timeout,
            env=env,
            shell=shell,
            log_prefix=log_prefix,
        )
    return subprocess.run(
        args,
        cwd=cwd,
        env=env,
        shell=shell,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
    )


def run_streaming_command(
    args: list[str] | str,
    cwd: Path,
    timeout: int | None = None,
    env: dict[str, str] | None = None,
    shell: bool = False,
    log_prefix: str = "",
) -> subprocess.CompletedProcess[str]:
    process = subprocess.Popen(
        args,
        cwd=cwd,
        env=env,
        shell=shell,
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
        raise

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


def ensure_clean_checkout(repo_dir: Path, base_commit: str, clean: bool) -> None:
    if not (repo_dir / ".git").exists():
        raise RuntimeError(f"{repo_dir} is not a git repository")

    if clean:
        result = git(repo_dir, "reset", "--hard", timeout=120)
        if result.returncode != 0:
            raise RuntimeError(f"git reset failed:\n{result.stderr}")
        result = git(repo_dir, "clean", "-fdx", timeout=120)
        if result.returncode != 0:
            raise RuntimeError(f"git clean failed:\n{result.stderr}")

    result = git(repo_dir, "checkout", base_commit, timeout=120)
    if result.returncode != 0:
        raise RuntimeError(f"git checkout {base_commit} failed:\n{result.stderr}")


def load_dataset(path: Path) -> list[dict[str, Any]]:
    tasks: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                tasks.append(json.loads(line))
    return tasks


def parse_json_list(value: str | list[str]) -> list[str]:
    if isinstance(value, list):
        return value
    parsed = json.loads(value)
    if not isinstance(parsed, list):
        raise ValueError(f"Expected JSON list, got {type(parsed).__name__}")
    return [str(item) for item in parsed]


def django_test_label(test_name: str) -> str:
    # SWE-bench Django entries look like:
    # "test_x (auth_tests.test_forms.AuthenticationFormTest)"
    if " (" in test_name and test_name.endswith(")"):
        method, class_path = test_name[:-1].split(" (", 1)
        return f"{class_path}.{method}"
    return test_name


def django_docstring_test_map(repo_dir: Path) -> dict[str, str]:
    tests_dir = repo_dir / "tests"
    mapping: dict[str, str] = {}
    for path in tests_dir.rglob("test*.py"):
        module = ".".join(path.relative_to(tests_dir).with_suffix("").parts)
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"))
        except UnicodeDecodeError:
            tree = ast.parse(path.read_text(encoding="latin-1"))
        except SyntaxError:
            continue
        for class_node in [node for node in tree.body if isinstance(node, ast.ClassDef)]:
            for func_node in [node for node in class_node.body if isinstance(node, ast.FunctionDef)]:
                if not func_node.name.startswith("test"):
                    continue
                docstring = ast.get_docstring(func_node)
                if not docstring:
                    continue
                first_line = docstring.strip().splitlines()[0].strip()
                if first_line:
                    mapping[first_line] = f"{module}.{class_node.name}.{func_node.name}"
    return mapping


def resolve_django_tests(repo_dir: Path, tests: list[str]) -> tuple[list[str], list[str]]:
    docstring_map = django_docstring_test_map(repo_dir)
    resolved: list[str] = []
    unresolved: list[str] = []
    for test in tests:
        label = django_test_label(test)
        if label != test:
            resolved.append(label)
            continue
        if test in docstring_map:
            resolved.append(docstring_map[test])
        elif label.startswith("test") or "." in label:
            resolved.append(label)
        else:
            unresolved.append(test)
    return resolved, unresolved


def pytest_collect_nodeids(
    repo_dir: Path,
    python_exe: str,
    roots: list[str],
    env: dict[str, str],
) -> list[str]:
    result = run_command(
        [python_exe, "-m", "pytest", "--collect-only", "-q", *roots],
        cwd=repo_dir,
        timeout=300,
        env=env,
    )
    if result.returncode not in {0, 5}:
        raise RuntimeError(f"pytest collection failed:\n{result.stdout}\n{result.stderr}")

    nodeids: list[str] = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if "::" in line and not line.startswith("=") and line not in nodeids:
            nodeids.append(line)
    return nodeids


def resolve_sphinx_pytest_tests(
    repo_dir: Path,
    output_dir: Path,
    tests: list[str],
    python_exe: str,
) -> list[str]:
    roots: list[str] = []
    for test in tests:
        root = test.split("::", 1)[0]
        if root not in roots:
            roots.append(root)

    collected = pytest_collect_nodeids(repo_dir, python_exe, roots, eval_env_for({"repo": "sphinx-doc/sphinx"}, repo_dir))
    resolved: list[str] = []
    unresolved: list[str] = []

    for test in tests:
        if test in collected:
            matches = [test]
        else:
            matches = [nodeid for nodeid in collected if nodeid.startswith(test)]

        if not matches:
            resolved.append(test)
            unresolved.append(test)
            continue

        for match in matches:
            if match not in resolved:
                resolved.append(match)

    save_text(output_dir / "eval_resolved_tests.txt", "\n".join(resolved) + ("\n" if resolved else ""))
    save_text(output_dir / "eval_unresolved_tests.txt", "\n".join(unresolved) + ("\n" if unresolved else ""))
    save_text(output_dir / "eval_collected_tests.txt", "\n".join(collected) + ("\n" if collected else ""))
    return resolved


def eval_python_for(task: dict[str, Any], requested: str | None) -> str:
    if requested:
        return requested
    if task["repo"] == "django/django":
        local_venv_python = Path(__file__).resolve().parent / ".venvs/django39/bin/python"
        if local_venv_python.exists():
            return str(local_venv_python)
        system_python = Path("/usr/bin/python3")
        if system_python.exists():
            return str(system_python)
    if task["repo"] == "sphinx-doc/sphinx":
        local_venv_python = Path(__file__).resolve().parent / ".venvs/sphinx39/bin/python"
        if local_venv_python.exists():
            return str(local_venv_python)
        system_python = Path("/usr/bin/python3")
        if system_python.exists():
            return str(system_python)
    return sys.executable


def remove_path_entries(path_value: str, entries_to_remove: set[Path]) -> str:
    kept: list[str] = []
    resolved_remove = {entry.resolve() for entry in entries_to_remove if entry.exists()}
    for raw_entry in path_value.split(os.pathsep):
        if not raw_entry:
            continue
        entry = Path(raw_entry).expanduser()
        try:
            resolved_entry = entry.resolve()
        except OSError:
            resolved_entry = entry
        if resolved_entry not in resolved_remove:
            kept.append(raw_entry)
    return os.pathsep.join(kept)


def junie_env_for(task: dict[str, Any]) -> dict[str, str]:
    env = os.environ.copy()
    project_dir = Path(__file__).resolve().parent
    eval_venv_bins = {
        project_dir / ".venvs/django39/bin",
        project_dir / ".venvs/sphinx39/bin",
    }
    env["PATH"] = remove_path_entries(env.get("PATH", ""), eval_venv_bins)
    env.pop("VIRTUAL_ENV", None)
    env.pop("PYTHONHOME", None)
    if task["repo"] == "sphinx-doc/sphinx" and SPHINX_CONSTRAINTS_FILE.exists():
        env["PIP_CONSTRAINT"] = str(SPHINX_CONSTRAINTS_FILE)
    return env


def parse_pinned_requirements(path: Path) -> dict[str, str]:
    pins: dict[str, str] = {}
    if not path.exists():
        return pins
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "==" not in line:
            continue
        package, version = line.split("==", 1)
        pins[package.strip().lower()] = version.strip()
    return pins


def check_python_package_versions(python_exe: str, pins: dict[str, str], cwd: Path) -> dict[str, str | None]:
    script = (
        "import importlib.metadata as m, json, sys; "
        "pins=json.loads(sys.argv[1]); "
        "out={}; "
        "\nfor name in pins:\n"
        "    try:\n"
        "        out[name]=m.version(name)\n"
        "    except m.PackageNotFoundError:\n"
        "        out[name]=None\n"
        "print(json.dumps(out, sort_keys=True))"
    )
    result = run_command([python_exe, "-c", script, json.dumps(pins)], cwd=cwd, timeout=120)
    if result.returncode != 0:
        raise RuntimeError(f"failed to inspect Python package versions:\n{result.stderr}")
    return json.loads(result.stdout)


def ensure_sphinx_eval_constraints(python_exe: str, output_dir: Path, repo_dir: Path) -> None:
    pins = parse_pinned_requirements(SPHINX_CONSTRAINTS_FILE)
    if not pins:
        return

    before = check_python_package_versions(python_exe, pins, cwd=repo_dir)
    save_text(output_dir / "sphinx_eval_constraints_before.json", json.dumps(before, indent=2, sort_keys=True))
    mismatched = {
        package: {"expected": expected, "actual": before.get(package)}
        for package, expected in pins.items()
        if before.get(package) != expected
    }
    save_text(
        output_dir / "sphinx_eval_constraints_mismatches.json",
        json.dumps(mismatched, indent=2, sort_keys=True),
    )
    if not mismatched:
        return

    result = run_command(
        [
            python_exe,
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--no-deps",
            "--constraint",
            str(SPHINX_CONSTRAINTS_FILE),
            "--requirement",
            str(SPHINX_CONSTRAINTS_FILE),
        ],
        cwd=repo_dir,
        timeout=300,
    )
    save_text(output_dir / "sphinx_eval_constraints_repair_stdout.txt", result.stdout)
    save_text(output_dir / "sphinx_eval_constraints_repair_stderr.txt", result.stderr)
    if result.returncode != 0:
        raise RuntimeError(
            "failed to restore Sphinx eval dependency constraints:\n"
            f"{result.stderr or result.stdout}"
        )

    after = check_python_package_versions(python_exe, pins, cwd=repo_dir)
    save_text(output_dir / "sphinx_eval_constraints_after.json", json.dumps(after, indent=2, sort_keys=True))
    remaining = {
        package: {"expected": expected, "actual": after.get(package)}
        for package, expected in pins.items()
        if after.get(package) != expected
    }
    if remaining:
        raise RuntimeError(f"Sphinx eval dependency constraints still mismatched: {remaining}")


def test_command_for(
    task: dict[str, Any],
    repo_dir: Path,
    output_dir: Path,
    tests: list[str],
    python_exe: str,
) -> list[str]:
    if task["repo"] == "django/django":
        resolved, unresolved = resolve_django_tests(repo_dir, tests)
        save_text(output_dir / "eval_resolved_tests.txt", "\n".join(resolved) + ("\n" if resolved else ""))
        save_text(output_dir / "eval_unresolved_tests.txt", "\n".join(unresolved) + ("\n" if unresolved else ""))
        return [
            python_exe,
            "tests/runtests.py",
            "--verbosity=2",
            *resolved,
        ]
    if task["repo"] == "sphinx-doc/sphinx":
        resolved = resolve_sphinx_pytest_tests(repo_dir, output_dir, tests, python_exe)
        return [python_exe, "-m", "pytest", "-q", *resolved]
    return [python_exe, "-m", "pytest", "-q", *tests]


def eval_env_for(task: dict[str, Any], repo_dir: Path) -> dict[str, str]:
    env = os.environ.copy()
    if task["repo"] in {"django/django", "sphinx-doc/sphinx"}:
        existing = env.get("PYTHONPATH")
        env["PYTHONPATH"] = str(repo_dir) if not existing else f"{repo_dir}{os.pathsep}{existing}"
    if task["repo"] == "sphinx-doc/sphinx" and SPHINX_CONSTRAINTS_FILE.exists():
        env["PIP_CONSTRAINT"] = str(SPHINX_CONSTRAINTS_FILE)
    return env


def build_prompt(task: dict[str, Any]) -> str:
     prompt = [
            "Solve this issue in the current repository.",
            "DO NOT change tests from /tests.",
            "Problem statement:",
            task["problem_statement"].strip(),
     ]
     return "\n".join(prompt)


def run_junie(
    task: dict[str, Any],
    repo_dir: Path,
    output_dir: Path,
    command: str,
    timeout: int,
    stream_logs: bool,
    log_prefix: str,
) -> subprocess.CompletedProcess[str]:
    prompt = build_prompt(task)
    task_file = output_dir / "task.md"
    task_file.write_text(prompt, encoding="utf-8")
    timeout_ms = timeout * 1000
    env = junie_env_for(task)

    if "{" in command:
        rendered = command.format(
            project=shlex.quote(str(repo_dir)),
            task=shlex.quote(prompt),
            task_file=shlex.quote(str(task_file)),
            timeout=timeout,
            timeout_ms=timeout_ms,
        )
        return run_command(
            rendered,
            cwd=repo_dir,
            timeout=timeout + 30,
            env=env,
            shell=True,
            stream=stream_logs,
            log_prefix=log_prefix,
        )

    args = shlex.split(command)
    if os.environ.get("JUNIE_API_KEY") and "--auth" not in args and "-a" not in args:
        args.append(f"--auth={os.environ['JUNIE_API_KEY']}")
    command_args = [
        *args,
        "--skip-update-check",
        "--project",
        str(repo_dir),
        "--timeout",
        str(timeout_ms),
        "--task",
        prompt,
    ]
    redacted_args = [
        "--auth=<redacted>" if arg.startswith("--auth=") else arg
        for arg in command_args
    ]
    save_text(output_dir / "junie_command.txt", shlex.join(redacted_args))
    return run_command(
        command_args,
        cwd=repo_dir,
        timeout=timeout + 30,
        env=env,
        stream=stream_logs,
        log_prefix=log_prefix,
    )


def junie_auth_failed(result: subprocess.CompletedProcess[str]) -> bool:
    output = f"{result.stdout}\n{result.stderr}"
    return "Cannot find authorization" in output or "Please authenticate before running Junie" in output


def save_text(path: Path, text: str) -> None:
    path.write_text(text or "", encoding="utf-8", errors="replace")


def create_agent_baseline(repo_dir: Path, output_dir: Path) -> str:
    """Create a local baseline in the temporary repo before Junie edits it."""
    before_status = git(repo_dir, "status", "--short", timeout=120)
    save_text(output_dir / "git_status_before_junie.txt", before_status.stdout + before_status.stderr)
    if before_status.returncode != 0:
        raise RuntimeError(f"git status before Junie failed:\n{before_status.stderr}")

    if not before_status.stdout.strip():
        save_text(output_dir / "agent_baseline_ref.txt", "HEAD\n")
        return "HEAD"

    add_result = git(repo_dir, "add", "-A", timeout=120)
    save_text(output_dir / "git_baseline_add_stdout.txt", add_result.stdout)
    save_text(output_dir / "git_baseline_add_stderr.txt", add_result.stderr)
    if add_result.returncode != 0:
        raise RuntimeError(f"git add baseline failed:\n{add_result.stderr}")

    env = os.environ.copy()
    env.update(
        {
            "GIT_AUTHOR_NAME": "SWE-bench Runner",
            "GIT_AUTHOR_EMAIL": "swebench-runner@example.invalid",
            "GIT_COMMITTER_NAME": "SWE-bench Runner",
            "GIT_COMMITTER_EMAIL": "swebench-runner@example.invalid",
        },
    )
    commit_result = run_command(
        ["git", "commit", "--no-verify", "-m", "SWE-bench runner baseline before Junie"],
        cwd=repo_dir,
        env=env,
        timeout=120,
    )
    save_text(output_dir / "git_baseline_commit_stdout.txt", commit_result.stdout)
    save_text(output_dir / "git_baseline_commit_stderr.txt", commit_result.stderr)
    if commit_result.returncode != 0:
        raise RuntimeError(f"git commit baseline failed:\n{commit_result.stderr}")

    save_text(output_dir / "agent_baseline_ref.txt", "HEAD\n")
    return "HEAD"


def save_model_patch(repo_dir: Path, output_dir: Path, baseline_ref: str) -> None:
    status = git(repo_dir, "status", "--short", timeout=120)
    save_text(output_dir / "git_status.txt", status.stdout + status.stderr)
    if status.returncode != 0:
        raise RuntimeError(f"git status after Junie failed:\n{status.stderr}")

    untracked = git(repo_dir, "ls-files", "--others", "--exclude-standard", timeout=120)
    save_text(output_dir / "git_untracked_files.txt", untracked.stdout + untracked.stderr)
    if untracked.returncode != 0:
        raise RuntimeError(f"git ls-files untracked failed:\n{untracked.stderr}")

    add_result = git(repo_dir, "add", "-A", timeout=120)
    save_text(output_dir / "git_add_after_junie_stdout.txt", add_result.stdout)
    save_text(output_dir / "git_add_after_junie_stderr.txt", add_result.stderr)
    if add_result.returncode != 0:
        raise RuntimeError(f"git add after Junie failed:\n{add_result.stderr}")

    patch = git(repo_dir, "diff", "--cached", "--binary", baseline_ref, timeout=120)
    save_text(output_dir / "model.patch", patch.stdout + patch.stderr)
    if patch.returncode != 0:
        raise RuntimeError(f"git diff {baseline_ref} failed:\n{patch.stderr}")

    staged_status = git(repo_dir, "status", "--short", timeout=120)
    save_text(output_dir / "git_status_after_model_patch.txt", staged_status.stdout + staged_status.stderr)
    if staged_status.returncode != 0:
        raise RuntimeError(f"git status after saving model.patch failed:\n{staged_status.stderr}")

    reset_result = git(repo_dir, "reset", "--", timeout=120)
    save_text(output_dir / "git_reset_after_model_patch_stdout.txt", reset_result.stdout)
    save_text(output_dir / "git_reset_after_model_patch_stderr.txt", reset_result.stderr)
    if reset_result.returncode != 0:
        raise RuntimeError(f"git reset after saving model.patch failed:\n{reset_result.stderr}")

    unstaged_status = git(repo_dir, "status", "--short", timeout=120)
    save_text(
        output_dir / "git_status_after_model_patch_unstaged.txt",
        unstaged_status.stdout + unstaged_status.stderr,
    )
    if unstaged_status.returncode != 0:
        raise RuntimeError(f"git status after unstaging model.patch files failed:\n{unstaged_status.stderr}")


def paths_from_patch(patch_text: str) -> list[str]:
    paths: list[str] = []
    for line in patch_text.splitlines():
        if line.startswith("+++ b/") or line.startswith("--- a/"):
            path = line[6:]
            if path != "/dev/null" and path not in paths:
                paths.append(path)
    return paths


def restore_patch_targets(repo_dir: Path, patch_text: str, output_dir: Path) -> None:
    paths = paths_from_patch(patch_text)
    save_text(output_dir / "test_patch_targets.txt", "\n".join(paths) + ("\n" if paths else ""))
    if not paths:
        return

    existing_paths: list[str] = []
    new_paths: list[str] = []
    for path in paths:
        result = git(repo_dir, "cat-file", "-e", f"HEAD:{path}", timeout=120)
        if result.returncode == 0:
            existing_paths.append(path)
        else:
            new_paths.append(path)

    save_text(
        output_dir / "restore_test_targets_existing.txt",
        "\n".join(existing_paths) + ("\n" if existing_paths else ""),
    )
    save_text(
        output_dir / "restore_test_targets_skipped_new.txt",
        "\n".join(new_paths) + ("\n" if new_paths else ""),
    )
    if not existing_paths:
        save_text(output_dir / "restore_test_targets_stdout.txt", "")
        save_text(output_dir / "restore_test_targets_stderr.txt", "")
        return

    result = git(repo_dir, "checkout", "HEAD", "--", *existing_paths, timeout=120)
    save_text(output_dir / "restore_test_targets_stdout.txt", result.stdout)
    save_text(output_dir / "restore_test_targets_stderr.txt", result.stderr)
    if result.returncode != 0:
        raise RuntimeError(f"Failed to restore test patch targets:\n{result.stderr}")


def restore_agent_test_edits(repo_dir: Path, output_dir: Path) -> None:
    model_patch_path = output_dir / "model.patch"
    if not model_patch_path.exists():
        save_text(output_dir / "restore_agent_test_edits_targets.txt", "")
        return

    paths = [
        path for path in paths_from_patch(model_patch_path.read_text(encoding="utf-8", errors="replace"))
        if path == "tests" or path.startswith("tests/")
    ]
    save_text(
        output_dir / "restore_agent_test_edits_targets.txt",
        "\n".join(paths) + ("\n" if paths else ""),
    )
    if not paths:
        save_text(output_dir / "restore_agent_test_edits_stdout.txt", "")
        save_text(output_dir / "restore_agent_test_edits_stderr.txt", "")
        return

    existing_paths: list[str] = []
    new_paths: list[str] = []
    for path in paths:
        result = git(repo_dir, "cat-file", "-e", f"HEAD:{path}", timeout=120)
        if result.returncode == 0:
            existing_paths.append(path)
        else:
            new_paths.append(path)

    save_text(
        output_dir / "restore_agent_test_edits_existing.txt",
        "\n".join(existing_paths) + ("\n" if existing_paths else ""),
    )
    save_text(
        output_dir / "restore_agent_test_edits_removed_new.txt",
        "\n".join(new_paths) + ("\n" if new_paths else ""),
    )

    stdout_parts: list[str] = []
    stderr_parts: list[str] = []
    if existing_paths:
        result = git(repo_dir, "checkout", "HEAD", "--", *existing_paths, timeout=120)
        stdout_parts.append(result.stdout)
        stderr_parts.append(result.stderr)
        if result.returncode != 0:
            raise RuntimeError(f"Failed to restore agent-edited test files:\n{result.stderr}")

    for path in new_paths:
        target = repo_dir / path
        if target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()

    save_text(output_dir / "restore_agent_test_edits_stdout.txt", "".join(stdout_parts))
    save_text(output_dir / "restore_agent_test_edits_stderr.txt", "".join(stderr_parts))


def apply_test_patch(repo_dir: Path, output_dir: Path, test_patch: str) -> subprocess.CompletedProcess[str]:
    patch_path = output_dir / "test.patch"
    patch_path.write_text(test_patch, encoding="utf-8")
    return run_command(["git", "apply", str(patch_path)], cwd=repo_dir, timeout=120)


def apply_existing_model_patch(repo_dir: Path, output_dir: Path, instance_id: str, results_dir: str) -> None:
    source_patch = Path(results_dir) / instance_id / "model.patch"
    if not source_patch.exists():
        raise RuntimeError(f"Existing model patch not found: {source_patch}")

    patch_text = source_patch.read_text(encoding="utf-8", errors="replace")
    save_text(output_dir / "existing_model_patch_source.txt", str(source_patch) + "\n")
    save_text(output_dir / "model.patch", patch_text)
    if not patch_text.strip():
        raise RuntimeError(f"Existing model patch is empty: {source_patch}")

    result = run_command(["git", "apply", "--binary", str(output_dir / "model.patch")], cwd=repo_dir, timeout=120)
    save_text(output_dir / "existing_model_patch_apply_stdout.txt", result.stdout)
    save_text(output_dir / "existing_model_patch_apply_stderr.txt", result.stderr)
    if result.returncode != 0:
        raise RuntimeError(f"Failed to apply existing model patch {source_patch}:\n{result.stderr}")


def render_eval_template(template: str, task: dict[str, Any], tests: list[str]) -> str:
    return template.format(
        instance_id=shlex.quote(task["instance_id"]),
        repo=shlex.quote(task["repo"]),
        tests=" ".join(shlex.quote(test) for test in tests),
        fail_to_pass=" ".join(shlex.quote(test) for test in parse_json_list(task["FAIL_TO_PASS"])),
        pass_to_pass=" ".join(shlex.quote(test) for test in parse_json_list(task["PASS_TO_PASS"])),
    )


def evaluate(
    task: dict[str, Any],
    repo_dir: Path,
    output_dir: Path,
    include_pass_to_pass: bool,
    eval_command: str | None,
    eval_python: str,
    timeout: int,
    stream_logs: bool,
    keep_agent_test_edits: bool,
    log_prefix: str,
) -> tuple[subprocess.CompletedProcess[str] | None, str]:
    tests = parse_json_list(task["FAIL_TO_PASS"])
    if include_pass_to_pass:
        tests.extend(parse_json_list(task["PASS_TO_PASS"]))

    if not keep_agent_test_edits:
        if stream_logs:
            log(f"{log_prefix}restore agent edits under tests/")
        restore_agent_test_edits(repo_dir, output_dir)
        if stream_logs:
            log(f"{log_prefix}restore official test patch targets")
        restore_patch_targets(repo_dir, task["test_patch"], output_dir)

    patch_result = apply_test_patch(repo_dir, output_dir, task["test_patch"])
    save_text(output_dir / "test_patch_stdout.txt", patch_result.stdout)
    save_text(output_dir / "test_patch_stderr.txt", patch_result.stderr)
    if patch_result.returncode != 0:
        if stream_logs:
            if patch_result.stdout:
                log(patch_result.stdout.rstrip("\n"))
            if patch_result.stderr:
                with PRINT_LOCK:
                    print(patch_result.stderr, end="", file=sys.stderr, flush=True)
        return None, "test_patch_failed"

    if eval_command:
        command = render_eval_template(eval_command, task, tests)
        save_text(output_dir / "eval_command.txt", command)
        result = run_command(
            command,
            cwd=repo_dir,
            timeout=timeout,
            env=eval_env_for(task, repo_dir),
            shell=True,
            stream=stream_logs,
            log_prefix=log_prefix,
        )
    else:
        command_args = test_command_for(task, repo_dir, output_dir, tests, eval_python)
        save_text(output_dir / "eval_command.txt", shlex.join(command_args))
        result = run_command(
            command_args,
            cwd=repo_dir,
            timeout=timeout,
            env=eval_env_for(task, repo_dir),
            stream=stream_logs,
            log_prefix=log_prefix,
        )

    save_text(output_dir / "eval_stdout.txt", result.stdout)
    save_text(output_dir / "eval_stderr.txt", result.stderr)
    return result, "passed" if result.returncode == 0 else "failed"


def append_jsonl(path: Path, row: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    fields = [
        "instance_id",
        "repo",
        "base_commit",
        "status",
        "success",
        "agent_seconds",
        "eval_seconds",
        "total_seconds",
        "junie_returncode",
        "eval_returncode",
        "output_dir",
        "error",
    ]
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row.get(field, "") for field in fields})


def select_tasks(tasks: list[dict[str, Any]], args: argparse.Namespace) -> list[dict[str, Any]]:
    selected = tasks
    if args.repo:
        selected = [task for task in selected if task["repo"] == args.repo]
    if args.instance_id:
        wanted = set(args.instance_id)
        selected = [task for task in selected if task["instance_id"] in wanted]
    if args.offset:
        selected = selected[args.offset :]
    if args.limit is not None:
        selected = selected[: args.limit]
    return selected


def copy_repo_for_task(
    task: dict[str, Any],
    args: argparse.Namespace,
    results_root: Path,
) -> Path:
    source_repo_dir = source_repo_dir_for_task(task, args)
    task_work_root = results_root / "worktrees" / task["instance_id"]
    copied_repo_dir = task_work_root / repo_slug(task["repo"])
    task_work_root.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        source_repo_dir,
        copied_repo_dir,
        symlinks=True,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".pytest_cache", ".mypy_cache"),
    )
    return copied_repo_dir


def source_repo_dir_for_task(task: dict[str, Any], args: argparse.Namespace) -> Path:
    if args.use_repos_explained or (args.use_django_repos_explained and task["repo"] == "django/django"):
        instance_dir = Path(args.repos_explained_dir) / task["instance_id"]
        repo_dir = instance_dir / "repo"
        if repo_dir.exists():
            return repo_dir
        return instance_dir
    return Path(args.repos_dir) / repo_slug(task["repo"])


def should_checkout_task(task: dict[str, Any], args: argparse.Namespace) -> bool:
    return not (args.use_repos_explained or (args.use_django_repos_explained and task["repo"] == "django/django"))


def remove_path(path: Path, quiet: bool) -> None:
    if path.exists():
        shutil.rmtree(path)
        log(f"removed: {path}", quiet)


def cleanup_workdirs(results_dir: Path, quiet: bool) -> int:
    removed = 0
    for worktrees_dir in sorted(results_dir.glob("*/worktrees")):
        if worktrees_dir.is_dir():
            remove_path(worktrees_dir, quiet)
            removed += 1
    log(f"Cleanup complete. Removed {removed} worktrees directorie(s).", quiet)
    return removed


def run_task(
    task: dict[str, Any],
    args: argparse.Namespace,
    results_root: Path,
    repo_dir: Path | None = None,
) -> dict[str, Any]:
    instance_id = task["instance_id"]
    repo_dir = repo_dir or Path(args.repos_dir) / repo_slug(task["repo"])
    output_dir = results_root / instance_id
    output_dir.mkdir(parents=True, exist_ok=True)
    task_log_prefix = f"[{instance_id}]"

    row: dict[str, Any] = {
        "instance_id": instance_id,
        "repo": task["repo"],
        "base_commit": task["base_commit"],
        "started_at": utc_now(),
        "output_dir": str(output_dir),
        "success": False,
        "status": "error",
        "error": "",
    }
    total_start = time.monotonic()
    agent_seconds = 0.0
    eval_seconds = 0.0
    agent_baseline_ref = "HEAD"
    junie_result: subprocess.CompletedProcess[str] | None = None
    eval_result: subprocess.CompletedProcess[str] | None = None

    try:
        log(f"{task_log_prefix} repo: {repo_dir}", args.quiet)
        if should_checkout_task(task, args):
            log(f"{task_log_prefix} checkout: {task['base_commit']}", args.quiet)
            ensure_clean_checkout(repo_dir, task["base_commit"], clean=not args.no_clean)
        else:
                    log(f"{task_log_prefix} checkout: skipped for repos-explained repo", args.quiet)

        agent_baseline_ref = create_agent_baseline(repo_dir, output_dir)

        agent_start = time.monotonic()
        if args.existing_results_dir:
            log(f"{task_log_prefix} junie: skipped; applying existing model.patch", args.quiet)
            save_text(output_dir / "agent_stdout.txt", "")
            save_text(output_dir / "agent_stderr.txt", "Junie skipped; existing model.patch was applied.\n")
            apply_existing_model_patch(repo_dir, output_dir, instance_id, args.existing_results_dir)
            row["junie_returncode"] = ""
            row["status"] = "patch_applied"
        elif args.dry_run:
            log(f"{task_log_prefix} dry-run: Junie and evaluation are skipped", args.quiet)
            save_text(output_dir / "agent_stdout.txt", "")
            save_text(output_dir / "agent_stderr.txt", "dry run: Junie was not executed\n")
            row["status"] = "dry_run"
        else:
            log(f"{task_log_prefix} junie: start timeout={args.junie_timeout}s", args.quiet)
            junie_result = run_junie(
                task=task,
                repo_dir=repo_dir,
                output_dir=output_dir,
                command=args.junie_command,
                timeout=args.junie_timeout,
                stream_logs=not args.quiet,
                log_prefix=f"[{instance_id} junie] ",
            )
            save_text(output_dir / "agent_stdout.txt", junie_result.stdout)
            save_text(output_dir / "agent_stderr.txt", junie_result.stderr)
            row["junie_returncode"] = junie_result.returncode
            if junie_auth_failed(junie_result):
                row["status"] = "junie_failed"
                row["error"] = "Junie authorization not found. Run `junie` interactively, pass --auth, or set JUNIE_API_KEY."
            log(
                f"{task_log_prefix} junie: exit={junie_result.returncode} "
                f"elapsed={time.monotonic() - agent_start:.3f}s",
                args.quiet,
            )
            if junie_result.returncode != 0:
                row["status"] = "junie_failed"
        agent_seconds = time.monotonic() - agent_start

        if args.existing_results_dir:
            status = git(repo_dir, "status", "--short", timeout=120)
            save_text(output_dir / "git_status.txt", status.stdout + status.stderr)
            if status.returncode != 0:
                raise RuntimeError(f"git status after applying existing model.patch failed:\n{status.stderr}")
        else:
            log(f"{task_log_prefix} artifacts: saving model.patch and git_status.txt", args.quiet)
            save_model_patch(repo_dir, output_dir, baseline_ref=agent_baseline_ref)

        if args.dry_run:
            return row
        if (
            junie_result is not None
            and (junie_result.returncode != 0 or junie_auth_failed(junie_result))
            and not args.eval_on_junie_failure
        ):
            return row

        if args.evaluation_mode == "none":
            row["status"] = "generated"
            return row

        eval_start = time.monotonic()
        eval_python = eval_python_for(task, args.eval_python)
        if task["repo"] == "sphinx-doc/sphinx" and not args.eval_command:
            log(f"{task_log_prefix} eval: verify Sphinx dependency constraints", args.quiet)
            ensure_sphinx_eval_constraints(eval_python, output_dir, repo_dir)
        log(f"{task_log_prefix} eval: apply test_patch and run tests", args.quiet)
        eval_result, status = evaluate(
            task=task,
            repo_dir=repo_dir,
            output_dir=output_dir,
            include_pass_to_pass=not args.fail_to_pass_only,
            eval_command=args.eval_command,
            eval_python=eval_python,
            timeout=args.eval_timeout,
            stream_logs=not args.quiet,
            keep_agent_test_edits=args.keep_agent_test_edits,
            log_prefix=f"[{instance_id} eval] ",
        )
        eval_seconds = time.monotonic() - eval_start
        log(
            f"{task_log_prefix} eval: status={status} "
            f"exit={getattr(eval_result, 'returncode', 'n/a')} elapsed={eval_seconds:.3f}s",
            args.quiet,
        )
        row["status"] = status
        if eval_result is not None:
            row["eval_returncode"] = eval_result.returncode
            row["success"] = eval_result.returncode == 0

    except subprocess.TimeoutExpired as exc:
        row["status"] = "timeout"
        row["error"] = str(exc)
    except Exception as exc:  # noqa: BLE001 - runner must record failures and continue.
        row["status"] = "error"
        row["error"] = str(exc)
    finally:
        row["finished_at"] = utc_now()
        row["agent_seconds"] = round(agent_seconds, 3)
        row["eval_seconds"] = round(eval_seconds, 3)
        row["total_seconds"] = round(time.monotonic() - total_start, 3)
        save_text(output_dir / "result.json", json.dumps(row, indent=2, ensure_ascii=False, sort_keys=True))
        if not args.no_final_clean:
            try:
                if should_checkout_task(task, args):
                    log(f"{task_log_prefix} cleanup: restore repository", args.quiet)
                    ensure_clean_checkout(repo_dir, task["base_commit"], clean=not args.no_clean)
                else:
                    log(f"{task_log_prefix} cleanup: checkout restore skipped for repos-explained repo", args.quiet)
            except Exception as exc:  # noqa: BLE001
                row["cleanup_error"] = str(exc)

    return row


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run Junie on tasks from swe-bench-verified-mini and evaluate results.",
    )
    parser.add_argument("--dataset", default=DEFAULT_DATASET, help="Path to JSONL dataset.")
    parser.add_argument("--repos-dir", default=DEFAULT_REPOS_DIR, help="Directory with cloned repositories.")
    parser.add_argument(
        "--repos-explained-dir",
        default=DEFAULT_REPOS_EXPLAINED_DIR,
        help="Directory with per-instance explained repositories.",
    )
    parser.add_argument(
        "--use-django-repos-explained",
        action="store_true",
        help=(
            "For django/django tasks, copy the source repository from "
            "--repos-explained-dir/<instance-id> instead of --repos-dir/django__django."
        ),
    )
    parser.add_argument(
        "--use-repos-explained",
        action="store_true",
        help=(
            "For selected tasks, copy the source repository from "
            "--repos-explained-dir/<instance-id>/repo instead of --repos-dir/<repo-slug>."
        ),
    )
    parser.add_argument("--results-dir", default=DEFAULT_RESULTS_DIR, help="Directory for run artifacts.")
    parser.add_argument("--env-file", default=".env", help="Path to .env file with API keys.")
    parser.add_argument("--env-override", action="store_true", help="Let .env override existing environment variables.")
    parser.add_argument("--junie-command", default=DEFAULT_JUNIE_COMMAND, help="Junie command or template.")
    parser.add_argument("--junie-timeout", type=int, default=1800, help="Junie timeout in seconds.")
    parser.add_argument("--eval-timeout", type=int, default=900, help="Evaluation timeout in seconds.")
    parser.add_argument(
        "--evaluation-mode",
        choices=["local", "none"],
        default="local",
        help="Evaluation backend: local test runner, or no evaluation.",
    )
    parser.add_argument("--eval-python", help="Python interpreter used by the built-in evaluator.")
    parser.add_argument("--eval-command", help="Optional shell command template for evaluation.")
    parser.add_argument(
        "--existing-results-dir",
        help=(
            "Skip Junie and evaluate model.patch files from this previous results run "
            "(expects <dir>/<instance-id>/model.patch)."
        ),
    )
    parser.add_argument("--repo", help="Run only tasks for this repo, e.g. django/django.")
    parser.add_argument("--instance-id", action="append", help="Run only this instance id; repeatable.")
    parser.add_argument("--offset", type=int, default=0, help="Skip the first N selected tasks.")
    parser.add_argument("--limit", type=int, help="Run at most N selected tasks.")
    parser.add_argument("--workers", type=int, default=1, help="Number of tasks to run in parallel.")
    parser.add_argument(
        "--fail-to-pass-only",
        action="store_true",
        help="Run only FAIL_TO_PASS tests. By default, PASS_TO_PASS tests are included too.",
    )
    parser.add_argument("--eval-on-junie-failure", action="store_true", help="Run eval even if Junie exits non-zero.")
    parser.add_argument("--no-clean", action="store_true", help="Do not git reset/clean before checkout.")
    parser.add_argument("--no-final-clean", action="store_true", help="Leave repository with generated changes/tests.")
    parser.add_argument("--dry-run", action="store_true", help="Prepare tasks without running Junie or evaluation.")
    parser.add_argument("--quiet", action="store_true", help="Do not stream Junie/eval logs to the console.")
    parser.add_argument(
        "--keep-agent-test-edits",
        action="store_true",
        help="Do not restore files touched by test_patch before applying official tests.",
    )
    parser.add_argument(
        "--keep-workdirs",
        action="store_true",
        help="Keep per-task temporary repository copies under results/<run-id>/worktrees.",
    )
    parser.add_argument(
        "--cleanup-workdirs",
        action="store_true",
        help="Delete old temporary repository copies under --results-dir and exit.",
    )
    return parser


def run_task_with_isolated_repo(
    index: int,
    total: int,
    task: dict[str, Any],
    args: argparse.Namespace,
    results_root: Path,
) -> dict[str, Any]:
    log(f"[{index}/{total}] {task['instance_id']} ({task['repo']})", args.quiet)
    copied_repo_dir: Path | None = None
    try:
        source_repo_dir = source_repo_dir_for_task(task, args)
        log(f"[{task['instance_id']}] copy repo for isolated worker from {source_repo_dir}", args.quiet)
        copied_repo_dir = copy_repo_for_task(task, args, results_root)
        return run_task(task, args, results_root, repo_dir=copied_repo_dir)
    finally:
        if copied_repo_dir is not None and not args.keep_workdirs:
            work_root = copied_repo_dir.parent
            try:
                shutil.rmtree(work_root)
                log(f"[{task['instance_id']}] removed workdir: {work_root}", args.quiet)
                try:
                    work_root.parent.rmdir()
                except OSError:
                    pass
            except Exception as exc:  # noqa: BLE001
                log(f"[{task['instance_id']}] failed to remove workdir {work_root}: {exc}", args.quiet)


def run_tasks(tasks: list[dict[str, Any]], args: argparse.Namespace, results_root: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    jsonl_path = results_root / "results.jsonl"
    if args.workers <= 1:
        for index, task in enumerate(tasks, start=1):
            row = run_task_with_isolated_repo(index, len(tasks), task, args, results_root)
            rows.append(row)
            append_jsonl(jsonl_path, row)
            log(
                f"[{task['instance_id']}] status={row['status']} success={row['success']} "
                f"total={row['total_seconds']}s",
                args.quiet,
            )
        return rows

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        future_to_task = {
            executor.submit(run_task_with_isolated_repo, index, len(tasks), task, args, results_root): task
            for index, task in enumerate(tasks, start=1)
        }
        for future in concurrent.futures.as_completed(future_to_task):
            task = future_to_task[future]
            try:
                row = future.result()
            except Exception as exc:  # noqa: BLE001
                row = {
                    "instance_id": task["instance_id"],
                    "repo": task["repo"],
                    "base_commit": task["base_commit"],
                    "started_at": utc_now(),
                    "finished_at": utc_now(),
                    "output_dir": str(results_root / task["instance_id"]),
                    "success": False,
                    "status": "error",
                    "error": str(exc),
                    "agent_seconds": 0.0,
                    "eval_seconds": 0.0,
                    "total_seconds": 0.0,
                }
            rows.append(row)
            append_jsonl(jsonl_path, row)
            log(
                f"[{task['instance_id']}] status={row['status']} success={row['success']} "
                f"total={row['total_seconds']}s",
                args.quiet,
            )
    return rows


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    env_path = Path(args.env_file).resolve()
    loaded_env = load_dotenv(env_path, override=args.env_override)
    if loaded_env:
        log(f"Loaded environment variables from {env_path}: {', '.join(sorted(loaded_env))}", args.quiet)

    dataset_path = Path(args.dataset).resolve()
    repos_dir = Path(args.repos_dir).resolve()
    repos_explained_dir = Path(args.repos_explained_dir).resolve()
    results_dir = Path(args.results_dir).resolve()
    if args.cleanup_workdirs:
        cleanup_workdirs(results_dir, args.quiet)
        return 0

    results_root = (results_dir / dt.datetime.now().strftime("%Y%m%d-%H%M%S")).resolve()
    args.repos_dir = str(repos_dir)
    args.repos_explained_dir = str(repos_explained_dir)
    results_root.mkdir(parents=True, exist_ok=True)

    tasks = select_tasks(load_dataset(dataset_path), args)
    if not tasks:
        parser.error("No tasks selected.")
    if args.workers < 1:
        parser.error("--workers must be >= 1.")
    if args.offset < 0:
        parser.error("--offset must be >= 0.")

    log(
        f"Selected {len(tasks)} task(s). workers={args.workers}. Results: {results_root}",
        args.quiet,
    )
    if args.workers > 1:
        log(
            f"Parallel mode: each task uses an isolated repository copy under {results_root / 'worktrees'}",
            args.quiet,
        )

    rows = run_tasks(tasks, args, results_root)
    write_csv(results_root / "results.csv", rows)
    passed = sum(1 for row in rows if row.get("success"))
    if args.evaluation_mode == "local":
        log(f"Done. Passed {passed}/{len(rows)}.", args.quiet)
    else:
        log("Done. Generated patches without evaluation.", args.quiet)
    if args.dry_run:
        return 0
    if args.evaluation_mode == "none":
        return 0 if all(row.get("status") == "generated" for row in rows) else 1
    return 0 if passed == len(rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
