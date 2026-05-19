"""
Build a pivot table: rows = models, columns = evaluated code files, values = metric.

Reads results_remapping__{model}__high__structured.csv for each model
and combines them into a single matrix.
"""

from pathlib import Path

import pandas as pd

EVALUATION_DIR = Path(__file__).resolve().parents[1]
RESULTS_DIR = EVALUATION_DIR / "results"
OUTPUT_PATH = RESULTS_DIR / "pivot_metrics.csv"

# USD per 1M input tokens
MODEL_PRICE_PER_1M = {
    "gpt-4o-mini":  "$0.15",
    "gpt-4.1":      "$2.00",
    "gpt-4.1-mini": "$0.40",
}

MODELS = {
    "gpt-4o-mini":  RESULTS_DIR / "results_remapping__gpt_4o_mini__high__structured.csv",
    "gpt-4.1":      RESULTS_DIR / "results_remapping__gpt_4_1__high__structured.csv",
    "gpt-4.1-mini": RESULTS_DIR / "results_remapping__gpt_4_1_mini__high__structured.csv",
}


def main():
    frames = []
    for model_name, path in MODELS.items():
        if not path.exists():
            print(f"[SKIP] {path.name} not found")
            continue
        df = pd.read_csv(path, usecols=["file_path", "metric"])
        df["model"] = model_name
        frames.append(df)

    if not frames:
        print("No results files found.")
        return

    combined = pd.concat(frames, ignore_index=True)
    combined["metric"] = pd.to_numeric(combined["metric"], errors="coerce")

    pivot = combined.pivot(index="model", columns="file_path", values="metric")
    pivot.columns.name = None
    pivot.index.name = "model"

    pivot.insert(0, "price_per_1M_input", pd.Series(MODEL_PRICE_PER_1M))

    print(pivot.to_string())
    print()

    pivot.to_csv(OUTPUT_PATH)
    print(f"Pivot table saved to: {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
