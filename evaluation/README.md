# Explainable AI Plugin Evaluation

This folder contains the reproducibility materials for the dissertation evaluation. It is intentionally outside `plugin/explainable-ai-plugin/`, because the IntelliJ plugin code and the experimental pipelines are separate artifacts.

## Structure

- `llm-as-a-judge/` - explanation-quality evaluation on the labml.ai-derived dataset.
- `swe-bench-verified-mini/` - paired Junie evaluation on SWE-bench Verified Mini tasks with regular and explanation-enriched repositories.

Large local repositories are kept out of git:

- `llm-as-a-judge/source-repos/`
- `swe-bench-verified-mini/repos/`
- `swe-bench-verified-mini/repos-explained/`
- `swe-bench-verified-mini/**/worktrees/`

Those paths are ignored by `../.gitignore`. They may exist locally for reruns, but should be recreated or copied manually when someone repeats the experiment.

## Environment

Use Python 3.11+ for the helper scripts. The scripts expect the usual scientific Python stack plus API clients:

```bash
python3 -m pip install pandas python-dotenv openai
```

Junie-based generation/evaluation also requires the `junie` CLI and a configured API key. Put secrets in an ignored `.env` file, either in this folder or in `explainable-ai-plugin/.env`:

```bash
OPENAI_API_KEY=...
JUNIE_API_KEY=...
```

## LLM-as-a-Judge Evaluation

Main folders:

- `llm-as-a-judge/data/` - source CSV datasets.
- `llm-as-a-judge/data/generated/` - generated explanation CSVs and Junie prompt artifacts.
- `llm-as-a-judge/results/` - judge scores and aggregate tables.
- `llm-as-a-judge/scripts/` - dataset, generation, judging, and aggregation scripts.

Run from this `evaluation` folder:

```bash
# Generate Junie summaries and mappings for the 5-topic dataset.
python3 llm-as-a-judge/scripts/generate_junie_summaries.py

# Generate the SWE-bench-style high-structured Junie artifact.
python3 llm-as-a-judge/scripts/generate_junie_swebench_style_summaries.py

# Run the OpenAI LLM-as-a-judge scorer.
python3 llm-as-a-judge/scripts/llm_judge.py

# Rebuild aggregate result tables.
python3 llm-as-a-judge/scripts/stats.py
python3 llm-as-a-judge/scripts/pivot_table.py
```

To rebuild the full labml.ai extraction dataset, first clone `labmlai/annotated_deep_learning_paper_implementations` into `llm-as-a-judge/source-repos/annotated_deep_learning_paper_implementations`, then run:

```bash
python3 llm-as-a-judge/scripts/build_dataset.py
```

## SWE-bench Verified Mini Evaluation

The `swe-bench-verified-mini/` directory is self-contained. Its own `README.md` has runner details; the common commands are:

```bash
cd swe-bench-verified-mini

# Check that task selection and repository copying work.
python3 run_junie_swebench.py --dry-run --limit 1

# Run one regular task.
python3 run_junie_swebench.py --instance-id django__django-11790

# Run one task against an explanation-enriched repository.
python3 run_junie_swebench.py \
  --use-repos-explained \
  --instance-id django__django-11790

# Recollect per-repository result CSVs from run artifacts.
python3 collect_repo_results_csv.py \
  --repo django/django \
  --results-dir results/junie \
  --output django_regular_results.csv

# Judge generated repos-explained mapping quality.
python3 evaluate_repos_explained_mappings.py
```

Before running SWE-bench tasks, prepare local repositories:

- regular repositories under `swe-bench-verified-mini/repos/<repo-slug>`;
- explanation-enriched repositories under `swe-bench-verified-mini/repos-explained/<instance-id>/repo`;
- optional Python 3.9 virtual environments under `swe-bench-verified-mini/.venvs/django39` and `swe-bench-verified-mini/.venvs/sphinx39`.

These folders are ignored because they are large and machine-specific.
