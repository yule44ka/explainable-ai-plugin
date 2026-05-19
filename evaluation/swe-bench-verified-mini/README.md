---
dataset_info:
  features:
  - name: repo
    dtype: string
  - name: instance_id
    dtype: string
  - name: base_commit
    dtype: string
  - name: patch
    dtype: string
  - name: test_patch
    dtype: string
  - name: problem_statement
    dtype: string
  - name: hints_text
    dtype: string
  - name: created_at
    dtype: string
  - name: version
    dtype: string
  - name: FAIL_TO_PASS
    dtype: string
  - name: PASS_TO_PASS
    dtype: string
  - name: environment_setup_commit
    dtype: string
  splits:
  - name: test
    num_bytes: 777136.2
    num_examples: 50
  download_size: 181086
  dataset_size: 777136.2
configs:
- config_name: default
  data_files:
  - split: test
    path: data/test-*
---

SWEBench-verified-mini is a subset of SWEBench-verified that uses 50 instead of 500 datapoints, requires 5GB instead of 130GB of storage and has approximately the same distribution of performance, test pass rates and difficulty as the original dataset.

You can find more details here: [https://github.com/mariushobbhahn/make_swe_bench_verified_mini](https://github.com/mariushobbhahn/make_swe_bench_verified_mini)

If you use the [Inspect implementation](https://github.com/UKGovernmentBEIS/inspect_evals/tree/main/src/inspect_evals/swe_bench), you can merely switch the `dataset: str = "princeton-nlp/SWE-bench_Verified",` to `dataset: str = "MariusHobbhahn/swe-bench-verified-mini",` in the "swe_bench.py" file.

## Junie runner

`run_junie_swebench.py` runs Junie on tasks from `swe-bench-verified-mini.jsonl`
using repositories from `repos`.

The runner loads API keys and other environment variables from `.env` before
starting Junie or evaluation. Existing shell variables win by default; pass
`--env-override` to let `.env` replace them.

Example `.env`:

```bash
JUNIE_API_KEY=...
OPENAI_API_KEY=...
```

For each selected task it:

1. copies the source repository from `repos` into the run's temporary worktree;
2. resets and checks out the copied repository at `base_commit`;
3. runs Junie with the issue text as the task prompt;
4. saves Junie's stdout/stderr and generated `git diff`;
5. restores files touched by the official `test_patch`;
6. applies the official `test_patch`;
7. runs both `FAIL_TO_PASS` and `PASS_TO_PASS` tests;
8. writes timing and success data to `results/junie/<run-id>/results.jsonl`
   and `results/junie/<run-id>/results.csv`;
9. deletes the temporary repository copy unless `--keep-workdirs` is passed.

Junie and evaluation stdout/stderr are streamed to the console while still
being saved to artifact files. Pass `--quiet` to keep only the result files.

Before applying the official `test_patch`, the runner restores files touched by
that patch. This prevents agent-authored test edits from causing
`patch does not apply`. Pass `--keep-agent-test-edits` to disable this behavior.
For Django, `PASS_TO_PASS` entries that are docstring descriptions are resolved
to their real `module.Class.test_method` names before running `tests/runtests.py`.

The runner always copies repositories from `repos` to
`results/junie/<run-id>/worktrees/<instance-id>/...` before starting each task.
This keeps Junie runs isolated from each other and from the source repositories.
Temporary copies are deleted after each task unless `--keep-workdirs` is passed.

To remove old temporary repository copies from previous runs:

```bash
python3 run_junie_swebench.py --cleanup-workdirs
```

Examples:

```bash
# Check task preparation without running Junie or tests.
python3 run_junie_swebench.py --dry-run --limit 1

# Run one concrete task.
python3 run_junie_swebench.py --instance-id django__django-11790

# For django/django tasks, copy the temporary worktree from a per-instance
# explained repository under repos-explained/<instance-id>.
python3 run_junie_swebench.py \
  --use-django-repos-explained \
  --instance-id django__django-11790

# Use another env file.
python3 run_junie_swebench.py --env-file ../.env --instance-id django__django-11790

# Run 10 tasks in parallel. Each task gets its own temporary repository copy.
python3 run_junie_swebench.py --limit 10 --workers 10

# Run tasks 11-50 in parallel.
python3 run_junie_swebench.py --offset 10 --limit 40 --workers 10

# Generate patches without running evaluation.
python3 run_junie_swebench.py --limit 10 --workers 10 --evaluation-mode none

# Run only FAIL_TO_PASS tests, skipping PASS_TO_PASS.
python3 run_junie_swebench.py --limit 5 --fail-to-pass-only
```

By default the runner calls:

```bash
junie --model gemini-3-flash-preview \
  --skip-update-check --project <repo> --timeout <millis> --task <prompt>
```

Use `--junie-command` to pass a different Junie binary or a shell template:

```bash
python3 run_junie_swebench.py \
  --junie-command "junie --provider openai --model gpt-5.4"

python3 run_junie_swebench.py \
  --junie-command "junie --project {project} --timeout {timeout_ms} --task {task}"
```

The built-in evaluator supports `django/django` and `sphinx-doc/sphinx` entries
from this mini dataset. For custom evaluation, pass `--eval-command`; the
template receives `{tests}`, `{fail_to_pass}`, `{pass_to_pass}`, `{repo}`, and
`{instance_id}`.

For old Django tasks, Python 3.13 is too new. If
`.venvs/django39/bin/python` exists, the runner uses it automatically for
`django/django` evaluation. You can also pass an explicit interpreter:

```bash
python3 run_junie_swebench.py \
  --instance-id django__django-11790 \
  --eval-python .venvs/django39/bin/python
```

Old Sphinx tasks also need a Python 3.9 environment with compatible
dependencies, especially `Jinja2<3.1` because newer Jinja2 removed
`environmentfilter`. If `.venvs/sphinx39/bin/python` exists, the runner uses it
automatically for `sphinx-doc/sphinx` evaluation.
