"""
Aggregate statistics across LLM-judge results files.

For each model computes:
  count, mean, median, min, max, std  of the metric column.
  generation_cost_usd — sum of price__* column from the generated dataset CSV.
"""

from pathlib import Path

import pandas as pd

EVALUATION_DIR = Path(__file__).resolve().parents[1]
RESULTS_DIR = EVALUATION_DIR / "results"
GENERATED_DATA_DIR = EVALUATION_DIR / "data" / "generated"

# (results_csv, generated_dataset_csv, price_column)
# USD per 1M input tokens
MODEL_PRICE_PER_1M = {
    "gpt-4o-mini":  "$0.15",
    "gpt-4.1":      "$2.00",
    "gpt-4.1-mini": "$0.40",
}

MODELS = {
    "gpt-4o-mini": (
        RESULTS_DIR / "results_remapping__gpt_4o_mini__high__structured.csv",
        GENERATED_DATA_DIR / "dataset_5topics_files_generated_gpt_4o_mini.csv",
        "price__gpt_4o_mini",
    ),
    "gpt-4.1": (
        RESULTS_DIR / "results_remapping__gpt_4_1__high__structured.csv",
        GENERATED_DATA_DIR / "dataset_5topics_files_generated_gpt_4_1.csv",
        "price__gpt_4_1",
    ),
    "gpt-4.1-mini": (
        RESULTS_DIR / "results_remapping__gpt_4_1_mini__high__structured.csv",
        GENERATED_DATA_DIR / "dataset_5topics_files_generated_gpt_4_1_mini.csv",
        "price__gpt_4_1_mini",
    ),
}

OUTPUT_PATH = RESULTS_DIR / "stats_summary.csv"


def generation_cost(dataset_path: Path, price_col: str) -> float | None:
    """Sum the price column from the generated dataset CSV."""
    if not dataset_path.exists():
        return None
    df = pd.read_csv(dataset_path, usecols=[price_col])
    return round(pd.to_numeric(df[price_col], errors="coerce").sum(), 6)


def main():
    rows = []
    total_gen_cost = 0.0

    for model_name, (results_path, dataset_path, price_col) in MODELS.items():
        if not results_path.exists():
            print(f"[SKIP] {results_path.name} not found")
            continue

        results_df = pd.read_csv(results_path)
        metrics = pd.to_numeric(results_df["metric"], errors="coerce").dropna()

        gen_cost = generation_cost(dataset_path, price_col)
        gen_cost_str = f"${gen_cost:.6f}" if gen_cost is not None else "n/a"
        if gen_cost is not None:
            total_gen_cost += gen_cost

        row = {
            "model":               model_name,
            "price_per_1M_input":  MODEL_PRICE_PER_1M.get(model_name, ""),
            "count":               metrics.count(),
            "mean":                round(metrics.mean(), 4),
            "median":              round(metrics.median(), 4),
            "min":                 round(metrics.min(), 4),
            "max":                 round(metrics.max(), 4),
            "std":                 round(metrics.std(), 4),
            "generation_cost_usd": gen_cost_str,
        }
        rows.append(row)
        print(
            f"{model_name:<16}  count={row['count']}  "
            f"mean={row['mean']:.4f}  median={row['median']:.4f}  "
            f"min={row['min']:.4f}  max={row['max']:.4f}  "
            f"gen_cost={gen_cost_str}"
        )

    if not rows:
        print("No results files found.")
        return

    total_row = {
        "model":               "TOTAL",
        "price_per_1M_input":  "",
        "count":               "",
        "mean":                "",
        "median":              "",
        "min":                 "",
        "max":                 "",
        "std":                 "",
        "generation_cost_usd": f"${total_gen_cost:.6f}",
    }
    rows.append(total_row)
    print(f"\nTOTAL generation cost: ${total_gen_cost:.6f}")

    pd.DataFrame(rows).to_csv(OUTPUT_PATH, index=False)
    print(f"Summary saved to: {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
