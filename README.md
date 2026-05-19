# Explainable AI Plugin

This repository contains an IntelliJ IDEA plugin for AI-assisted code explanations and the reproducibility materials for evaluating those explanations.

The project has two main parts:

- `plugin/explainable-ai-plugin/` - the IntelliJ IDEA plugin implementation.
- `evaluation/` - datasets, scripts, results, and documentation for the dissertation evaluation.

## Repository Structure

```text
.
├── plugin/explainable-ai-plugin/       IntelliJ IDEA plugin source code
├── evaluation/                         Evaluation scripts, data, and results
│   ├── llm-as-a-judge/                 Explanation-quality evaluation on labml.ai data
│   ├── swe-bench-verified-mini/        Paired SWE-bench Verified Mini evaluation
└── README.md                           This overview
```

Large local experiment repositories are intentionally not committed. They are ignored under:

- `evaluation/llm-as-a-judge/source-repos/`
- `evaluation/swe-bench-verified-mini/repos/`
- `evaluation/swe-bench-verified-mini/repos-explained/`
- `evaluation/swe-bench-verified-mini/**/worktrees/`

## IntelliJ IDEA Plugin

The plugin helps developers generate, inspect, and insert AI explanations for source code.

Main capabilities:

- Generate explanations for selected code.
- Choose explanation detail level: low, medium, or high.
- Choose explanation format: paragraph or structured bullets.
- Map explanation fragments back to source-code segments.
- Highlight mapped explanation/code relationships in the tool window.
- Generate or modify code through Junie CLI and explain the resulting changes.
- Insert high-detail explanation comments before mapped code blocks.
- Use either Junie CLI or OpenAI API as the explanation provider.

Plugin documentation: [`plugin/explainable-ai-plugin/README.md`](plugin/explainable-ai-plugin/README.md)

### Build and Run

```bash
cd plugin/explainable-ai-plugin
./gradlew build
./gradlew runIde
```

Requirements:

- JDK 21
- IntelliJ IDEA compatible with build `252.25557` or newer
- Gradle wrapper from this repository
- Junie CLI on `PATH` for Junie-backed workflows
- OpenAI API key for OpenAI-backed workflows

Configure credentials in IntelliJ IDEA under:

```text
Settings | Tools | Explainable AI
```

## Evaluation Package

The `evaluation/` folder contains the materials used to evaluate the project in two complementary ways.

Evaluation documentation: [`evaluation/README.md`](evaluation/README.md)

### 1. LLM-as-a-Judge Evaluation

Location:

```text
evaluation/llm-as-a-judge/
```

This part evaluates generated code explanations against human-written labml.ai reference explanations. It includes:

- source and generated CSV datasets;
- OpenAI LLM-as-a-judge scoring script;
- Junie generation scripts;
- aggregate statistics and pivot-table scripts;
- final result CSVs.

Common commands:

```bash
cd evaluation

python3 llm-as-a-judge/scripts/generate_junie_summaries.py
python3 llm-as-a-judge/scripts/generate_junie_swebench_style_summaries.py
python3 llm-as-a-judge/scripts/llm_judge.py
python3 llm-as-a-judge/scripts/stats.py
python3 llm-as-a-judge/scripts/pivot_table.py
```

### 2. SWE-bench Verified Mini Evaluation

Location:

```text
evaluation/swe-bench-verified-mini/
```

This part compares regular repositories with explanation-enriched repositories on SWE-bench Verified Mini tasks. It includes:

- SWE-bench Verified Mini JSONL files;
- Junie task runner;
- scripts for building explanation-enriched repositories;
- mapping-quality judge scripts;
- collected result CSVs, patches, and logs.

Common commands:

```bash
cd evaluation/swe-bench-verified-mini

python3 run_junie_swebench.py --dry-run --limit 1
python3 run_junie_swebench.py --instance-id django__django-11790
python3 run_junie_swebench.py --use-repos-explained --instance-id django__django-11790
python3 evaluate_repos_explained_mappings.py
```

## Environment

For the Python evaluation scripts:

```bash
python3 -m pip install pandas python-dotenv openai
```

For API-backed runs, create an ignored `.env` file in either the repository root or `evaluation/`:

```bash
OPENAI_API_KEY=...
JUNIE_API_KEY=...
```

Do not commit credentials, local virtual environments, cloned benchmark repositories, or generated worktrees.
