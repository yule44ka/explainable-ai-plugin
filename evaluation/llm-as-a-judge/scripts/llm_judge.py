"""
LLM-as-a-Judge evaluation for code explanation quality.

Iterates over all provided CSV files and evaluates every remapping column
against the expected reference using OpenAI as a judge.

For each (csv_file, remapping_column) pair a separate results file is saved:
  results_{remapping_column}.csv
"""

import io
import json
import logging
import os
import time
import tokenize
from pathlib import Path

import pandas as pd
from dotenv import load_dotenv
from openai import OpenAI

EVALUATION_DIR = Path(__file__).resolve().parents[1]
GENERATED_DATA_DIR = EVALUATION_DIR / "data" / "generated"
RESULTS_DIR = EVALUATION_DIR / "results"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

CSV_FILES = [
    # GENERATED_DATA_DIR / "dataset_5topics_files_generated_gpt_4o.csv",
    # GENERATED_DATA_DIR / "dataset_5topics_files_generated_gpt_4o_mini.csv",
    # GENERATED_DATA_DIR / "dataset_5topics_files_generated_gpt_4_1.csv",
    GENERATED_DATA_DIR / "dataset_5topics_files_generated_gpt_4_1_mini.csv",
    # GENERATED_DATA_DIR / "dataset_5topics_files_generated_gpt_4_1_nano.csv",
]

EXPECTED_COL = "expected"

JUDGE_MODEL = "o3"

SYSTEM_PROMPT = """You are an expert code explanations evaluator. 
Your task is to assess the quality of an automatically generated code explanation by comparing it to a reference (expected) explanation.

You evaluate two artifacts:
1. GENERATED – a list of code components produced by an AI tool, where each component has:
   - an "explanationComponent": a short textual description of what the code does
   - "codeSegments": the actual code lines assigned to this component

2. EXPECTED – a reference list of code components written by a human expert, where each component has:
   - a "description": a detailed explanation (may include Markdown and math)
   - "code": the code block covered by this component

Consider the following aspects in your evaluation:
- Segmentation: does the generated split match the reference in terms of logical grouping?
- Explanation quality: are the explanations accurate, complete, and informative compared to the reference?
- Coverage: do the generated components cover all important concepts from the reference?

Return a JSON object with exactly this schema:
{
  "metric": <double 0.0-1.0>,
  "explain_metric": "<string>"
}

Scoring approximate guide for "metric":
  0.0-0.1 = awful quality: totally wrong or no segmentations and explanations
  0.1–0.3  = poor quality: wrong segmentation, vague or incorrect explanations, major concepts missing
  0.4–0.6  = acceptable quality: roughly correct structure, explanations partially match the reference
  0.7–0.9  = good quality: logical segmentation, accurate explanations, most concepts covered
  1.0 = excellent: segmentation, explanations and coverage are equivalent by quality to the reference
  
You can make evaluation of any score in this range with up to 3 digits after the coma.

"explain_metric" must be a concise paragraph justifying the score 

Be strict but fair. Output ONLY the JSON object, no extra text."""

USER_PROMPT_TEMPLATE = """Evaluate the following code explanation.

FILE: {file_path}

=== GENERATED EXPLANATION ===
{generated_text}

=== EXPECTED (REFERENCE) EXPLANATION ===
{expected_text}

Return your evaluation as a JSON object following the schema from your instructions."""

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def strip_comments_and_docstrings(code: str) -> str:
    """Remove comments and docstrings from a Python code snippet."""
    try:
        tokens = tokenize.generate_tokens(io.StringIO(code).readline)
        # Use 2-tuple form so untokenize reformats spacing itself (no stale positions)
        cleaned_tokens = []
        for tok_type, tok_string, *_ in tokens:
            if tok_type == tokenize.COMMENT:
                continue
            # Drop triple-quoted strings (docstrings); keep normal string literals
            if tok_type == tokenize.STRING and (
                tok_string.startswith('"""') or tok_string.startswith("'''")
            ):
                continue
            cleaned_tokens.append((tok_type, tok_string))
        result = tokenize.untokenize(cleaned_tokens)
        # Remove blank lines and normalize extra spaces introduced by 2-tuple untokenize
        lines = [ln.rstrip() for ln in result.splitlines() if ln.strip()]
        return "\n".join(lines)
    except tokenize.TokenError:
        # Fallback: strip comment lines manually if tokenizer fails on a snippet
        lines = [
            ln for ln in code.splitlines()
            if ln.strip() and not ln.strip().startswith("#")
        ]
        return "\n".join(lines)


def format_generated(components: list[dict]) -> str:
    """Convert generated JSON components to a readable text block."""
    lines = []
    for i, comp in enumerate(components, 1):
        explanation = comp.get("explanationComponent", "(no explanation)")
        segments = comp.get("codeSegments", [])
        code_lines = [seg.get("code", "") for seg in segments]
        lines.append(f"[Component {i}]")
        lines.append(f"Explanation: {explanation}")
        lines.append(f"Code segments:")
        for cl in code_lines:
            lines.append(f"  {cl}")
        lines.append("")
    return "\n".join(lines)


def format_expected(components: list[dict]) -> str:
    """Convert expected JSON components to a readable text block.

    Comments and docstrings are stripped from code to keep the prompt focused
    on structure and logic rather than inline documentation.
    """
    lines = []
    for i, comp in enumerate(components, 1):
        description = comp.get("description", "(no description)")
        raw_code = comp.get("code", "(no code)")
        code = strip_comments_and_docstrings(raw_code)
        lines.append(f"[Component {i}]")
        lines.append(f"Description: {description}")
        lines.append(f"Code:")
        for code_line in code.split("\n")[:10]:  # First 10 lines to keep prompt manageable
            lines.append(f"  {code_line}")
        if len(code.split("\n")) > 10:
            lines.append(f"  ... ({len(code.split(chr(10))) - 10} more lines)")
        lines.append("")
    return "\n".join(lines)


def parse_json_response(content: str) -> dict:
    """Extract JSON from the model response, handling markdown code fences."""
    content = content.strip()
    if content.startswith("```"):
        # Strip ```json ... ``` fences
        content = content.split("```")[1]
        if content.startswith("json"):
            content = content[4:]
    return json.loads(content.strip())


# ---------------------------------------------------------------------------
# Main evaluation loop
# ---------------------------------------------------------------------------


def evaluate_row(client: OpenAI, row: pd.Series, generated_col: str) -> dict:
    """Call the judge LLM for a single CSV row. Returns a dict with scores."""
    file_path = row.get("file_path", "unknown")

    # Parse JSON columns
    try:
        generated = json.loads(row[generated_col])
    except (json.JSONDecodeError, TypeError):
        return {"error": f"Could not parse {generated_col}"}

    try:
        expected = json.loads(row[EXPECTED_COL])
    except (json.JSONDecodeError, TypeError):
        return {"error": f"Could not parse {EXPECTED_COL}"}

    generated_text = format_generated(generated)
    expected_text = format_expected(expected)

    user_prompt = USER_PROMPT_TEMPLATE.format(
        file_path=file_path,
        generated_text=generated_text,
        expected_text=expected_text,
    )

    response = client.chat.completions.create(
        model=JUDGE_MODEL,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        response_format={"type": "json_object"},
    )

    raw = response.choices[0].message.content
    parsed = parse_json_response(raw)
    usage = response.usage
    return {
        "file_path": file_path,
        "num_generated_components": len(generated),
        "num_expected_components": len(expected),
        "metric": parsed["metric"],
        "explain_metric": parsed["explain_metric"],
        "input_tokens": usage.prompt_tokens if usage else None,
        "output_tokens": usage.completion_tokens if usage else None,
    }


def evaluate_column(client: OpenAI, df: pd.DataFrame, generated_col: str) -> None:
    """Run judge evaluation for a single generated column and save results."""
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    output_path = RESULTS_DIR / f"results_{generated_col}.csv"

    valid_rows = df[[generated_col, EXPECTED_COL, "file_path"]].dropna(
        subset=[generated_col, EXPECTED_COL]
    )
    logging.info(
        "Evaluating column '%s': %d rows, judge model: %s",
        generated_col, len(valid_rows), JUDGE_MODEL,
    )

    results = []
    for idx, (_, row) in enumerate(valid_rows.iterrows(), 1):
        file_path = row["file_path"]
        logging.info("[%d/%d] Processing: %s", idx, len(valid_rows), file_path)
        try:
            result = evaluate_row(client, row, generated_col)
            results.append(result)
            logging.info(
                "[%d/%d] Done: %s — metric=%s | %s...",
                idx, len(valid_rows), file_path,
                result.get("metric"),
                str(result.get("explain_metric", ""))[:100],
            )

            if idx < len(valid_rows):
                time.sleep(0.5)  # Avoid rate limiting

        except Exception as e:
            logging.error("[%d/%d] ERROR on %s: %s", idx, len(valid_rows), file_path, e)
            results.append({"file_path": file_path, "metric": None, "explain_metric": str(e)})

    results_df = pd.DataFrame(results)

    # Print aggregate statistics
    logging.info("=" * 50)
    logging.info("AGGREGATE RESULTS for '%s'", generated_col)
    metrics = pd.to_numeric(results_df["metric"], errors="coerce")
    logging.info("  count : %d", metrics.count())
    logging.info("  mean  : %.3f", metrics.mean())
    logging.info("  std   : %.3f", metrics.std())
    logging.info("  min   : %s", metrics.min())
    logging.info("  max   : %s", metrics.max())

    results_df.to_csv(output_path, index=False)
    logging.info("Results saved to: %s", output_path)


def main():
    load_dotenv(EVALUATION_DIR / ".env")
    load_dotenv(EVALUATION_DIR.parent / ".env")
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise EnvironmentError(
            "OPENAI_API_KEY not found. Set it in .env file: OPENAI_API_KEY=sk-..."
        )

    client = OpenAI(api_key=api_key)

    for csv_path in CSV_FILES:
        if not csv_path.exists():
            logging.warning("CSV file not found, skipping: %s", csv_path)
            continue

        logging.info("Loading: %s", csv_path)
        df = pd.read_csv(csv_path)

        remapping_cols = [c for c in df.columns if c.startswith("remapping__")]
        logging.info("Found %d remapping columns: %s", len(remapping_cols), remapping_cols)

        for col in remapping_cols:
            evaluate_column(client, df, col)


if __name__ == "__main__":
    main()
