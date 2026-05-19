"""
Build a dataset of code-description pairs from labml_nn Python files.

Two types of pairs are extracted:
1. Docstring pairs: class/function definitions with their docstrings
2. Comment pairs: # comment blocks immediately above code lines
"""

import ast
import csv
import io
import re
import tokenize
from pathlib import Path

EVALUATION_DIR = Path(__file__).resolve().parents[1]
BASE_DIR = EVALUATION_DIR / "source-repos" / "annotated_deep_learning_paper_implementations" / "labml_nn"
OUTPUT_FILE = EVALUATION_DIR / "data" / "dataset_full.csv"


def get_docstring(node) -> str | None:
    """Return the docstring of an AST node, or None if absent."""
    if (
        node.body
        and isinstance(node.body[0], ast.Expr)
        and isinstance(node.body[0].value, ast.Constant)
        and isinstance(node.body[0].value.value, str)
    ):
        return node.body[0].value.value.strip()
    return None


def extract_name_from_code(first_line: str) -> str:
    """Derive a short name from the first line of a code block."""
    s = first_line.strip()
    # assignment: self.foo = ... or foo = ...
    m = re.match(r"(?:self\.)?(\w+)\s*=", s)
    if m:
        return m.group(1)
    # return / yield statement — skip to expression
    m = re.match(r"(?:return|yield)\s+(.+)", s)
    if m:
        return m.group(1)[:60]
    return s[:80]


def extract_docstring_pairs(source: str, tree: ast.Module, rel_path: Path) -> list[dict]:
    """Extract class/function nodes that have docstrings."""
    lines = source.splitlines()
    pairs = []

    for node in ast.walk(tree):
        if not isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        docstring = get_docstring(node)
        if not docstring:
            continue
        code = "\n".join(lines[node.lineno - 1 : node.end_lineno])
        pairs.append(
            {
                "name": node.name,
                "description": docstring,
                "code": code,
                "file_path": str(rel_path),
            }
        )

    return pairs


def extract_comment_pairs(source: str, rel_path: Path) -> list[dict]:
    """Extract standalone # comment blocks paired with the code that follows them.

    Uses the tokenize module so that content inside string literals (docstrings)
    is never mistaken for comments.
    """
    lines = source.splitlines()
    n = len(lines)

    # Collect all real comment tokens with their positions
    try:
        all_tokens = list(tokenize.generate_tokens(io.StringIO(source).readline))
    except tokenize.TokenError:
        return []

    # Build a set of (row, col) for comment tokens so we can identify
    # "standalone" comments: the comment is the first real token on its line.
    # We also record which rows are occupied by non-comment, non-whitespace tokens.
    code_rows: set[int] = set()
    comment_info: list[tuple[int, int, str]] = []  # (row, col, text)

    for tok in all_tokens:
        if tok.type == tokenize.COMMENT:
            comment_info.append((tok.start[0], tok.start[1], tok.string))
        elif tok.type not in (
            tokenize.NEWLINE,
            tokenize.NL,
            tokenize.INDENT,
            tokenize.DEDENT,
            tokenize.ENCODING,
            tokenize.ENDMARKER,
        ):
            code_rows.add(tok.start[0])

    # A comment is "standalone" on its line if that row has no code tokens
    standalone: dict[int, tuple[int, str]] = {}  # row -> (col, text)
    for row, col, text in comment_info:
        if row not in code_rows:
            standalone[row] = (col, text)

    if not standalone:
        return []

    pairs: list[dict] = []
    sorted_rows = sorted(standalone)
    i = 0
    total = len(sorted_rows)

    while i < total:
        # Collect a block of consecutive standalone comment rows
        block_rows = [sorted_rows[i]]
        i += 1
        while i < total:
            prev = block_rows[-1]
            cur = sorted_rows[i]
            # Allow rows to be adjacent or separated by blank lines only
            gap_lines = [lines[r - 1].strip() for r in range(prev + 1, cur)]
            if all(ln == "" for ln in gap_lines):
                block_rows.append(cur)
                i += 1
            else:
                break

        # Extract comment texts (strip leading # and whitespace)
        comment_texts = [standalone[r][1][1:].strip() for r in block_rows]
        meaningful = [t for t in comment_texts if t]
        if not meaningful:
            continue
        description = " ".join(meaningful)
        if len(description) < 8:
            continue

        # The code that follows starts after the last comment row
        last_comment_row = block_rows[-1]  # 1-indexed
        code_start = last_comment_row  # 0-indexed = last_comment_row - 1, but we want the NEXT line

        # Skip blank lines
        while code_start < n and not lines[code_start].strip():
            code_start += 1

        if code_start >= n:
            continue

        next_line = lines[code_start].strip()
        # Skip if followed by another comment or nothing meaningful
        if not next_line or code_start + 1 in standalone or code_start in {r - 1 for r in standalone}:
            pass  # still try to collect

        # Collect the code lines following the comment block
        code_indent = len(lines[code_start]) - len(lines[code_start].lstrip())
        code_lines: list[str] = []

        j = code_start
        while j < n:
            s = lines[j].strip()
            if not s:
                break
            cur_indent = len(lines[j]) - len(lines[j].lstrip())
            # Stop at a standalone comment at same/shallower indent
            if (j + 1) in standalone and cur_indent <= code_indent:
                break
            # Stop at a line with strictly shallower indent than the first code line
            if code_lines and cur_indent < code_indent:
                break
            code_lines.append(lines[j])
            j += 1

        if not code_lines:
            continue

        code = "\n".join(code_lines)
        name = extract_name_from_code(code_lines[0])

        pairs.append(
            {
                "name": name,
                "description": description,
                "code": code,
                "file_path": str(rel_path),
            }
        )

    return pairs


def process_file(py_file: Path) -> list[dict]:
    rel_path = py_file.relative_to(BASE_DIR.parent)
    try:
        source = py_file.read_text(encoding="utf-8")
    except Exception as e:
        print(f"  [skip] {py_file.name}: cannot read — {e}")
        return []

    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        print(f"  [skip] {py_file.name}: syntax error — {e}")
        return []

    pairs = extract_docstring_pairs(source, tree, rel_path)
    pairs += extract_comment_pairs(source, rel_path)
    return pairs


def main():
    py_files = sorted(BASE_DIR.rglob("*.py"))
    print(f"Processing {len(py_files)} files …\n")

    all_pairs: list[dict] = []
    for py_file in py_files:
        file_pairs = process_file(py_file)
        if file_pairs:
            print(f"  {py_file.relative_to(BASE_DIR)}: {len(file_pairs)} pairs")
        all_pairs.extend(file_pairs)

    print(f"\nTotal pairs: {len(all_pairs)}")

    with open(OUTPUT_FILE, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["name", "description", "code", "file_path"])
        writer.writeheader()
        writer.writerows(all_pairs)

    print(f"Saved → {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
