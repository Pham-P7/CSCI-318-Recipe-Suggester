import argparse
from contextlib import closing
from datetime import datetime
import json
from pathlib import Path
import sqlite3

from ingredient_processing import parse_ingredients, normalize_ingredient, PARTS, QUANTITIES
from recipe_metadata_cleaning import file_hash

REVIEW_DATE = "2026-10-25"

# Small, manually reviewed Food.com references, not rules inferred from array lengths.
# Each pair is (original name, quantity text, source line, alternative group).
# The original name and both parsed arrays must still match before using a reference.
REVIEWED = {
    38: {
        "name": "Low-Fat Berry Blue Frozen Dessert",
        "url": "https://www.food.com/recipe/low-fat-berry-blue-frozen-dessert-38",
        "quantities": ["4", "1/4", "1", "1"],
        "pairs": [
            ("blueberries", "4 cups", 1, None),
            ("granulated sugar", "1/4 cup", 2, None),
            ("vanilla yogurt", "1 cup", 3, None),
            ("lemon juice", "1 tablespoon", 4, None),
        ],
        "added": [],
    },
    39: {
        "name": "Biryani",
        "url": "https://www.food.com/recipe/biryani-39",
        "quantities": ["1", "4", "2", "2", "8", "1/4", "8", "1/2", "1", "1",
                       "1/4", "1/4", "1/2", "1/4", "2", "3", None, "2", "1", "1",
                       "8", "2", "1/3", "1/3", "1/3", "6"],
        "pairs": [
            ("saffron", "1 tablespoon", 1, None),
            ("milk", "4 teaspoons", 2, None),
            ("hot green chili peppers", "2", 3, None),
            ("onions", "2 large", 4, None),
            ("garlic", "8 cloves", 5, None),
            ("clove", "1/4 teaspoon", 6, None),
            ("peppercorns", "8 whole", 7, None),
            ("cardamom seed", "1/2 teaspoon", 8, None),
            ("cumin seed", "1 teaspoon", 10, None),
            ("poppy seed", "1/4 teaspoon", 11, None),
            ("mace", "1/4 teaspoon", 12, None),
            ("cilantro", "1/2 cup", 13, "39:13"),
            ("mint leaf", "1/2 cup", 13, "39:13"),
            ("fresh lemon juice", "1/4 cup", 14, None),
            ("plain yogurt", "2 cups", 15, None),
            ("boneless chicken", "3 lbs", 16, None),
            ("salt", None, 17, None),
            ("ghee", "1 tablespoon", 19, None),
            ("onion", "1", 20, None),
            ("tomatoes", "8 large", 21, None),
            ("basmati rice", "2 cups", 22, "39:22"),
            ("long-grain rice", "2 cups", 22, "39:22"),
            ("raisins", "1/3 cup", 23, None),
            ("cashews", "1/3 cup", 24, None),
            ("eggs", "6", 26, None),
        ],
        "added": [("coriander seed", "1 teaspoon", 9, None),
                  ("vegetable oil", "2 tablespoons", 18, None),
                  ("almonds", "1/3 cup", 25, None)],
    },
    42: {
        "name": "Cabbage Soup",
        "url": "https://www.food.com/recipe/cabbage-soup-42",
        "quantities": ["46", "4", "1", "2", "1"],
        "pairs": [
            ("plain tomato juice", "46 ounces", 1, None),
            ("cabbage", "4 cups", 2, None),
            ("onion", "1 medium", 3, None),
            ("carrots", "2 large", 4, None),
            ("celery", "1 cup", 5, None),
        ],
        "added": [],
    },
}


def build_pairs(row):
    """Keep every original ingredient; quantities need a reviewed source line."""
    try:
        names = parse_ingredients(row[PARTS]) or []
    except ValueError:
        # The original raw fields remain in the copied recipes table.
        return []
    try:
        quantities = parse_ingredients(row[QUANTITIES])
    except ValueError:
        quantities = None
    reference = REVIEWED.get(row["recipeId"])
    if reference and (row["name"] != reference["name"]
                      or names != [item[0] for item in reference["pairs"]]
                      or quantities != reference["quantities"]):
        reference = None
    records = []
    entries = [(name, None, None, None) for name in names]
    if reference:
        entries = reference["pairs"] + reference["added"]
    for position, (name, quantity, line, alternative) in enumerate(entries, 1):
        note = "No reviewed ingredient line and unit; array lengths do not verify a pair"
        if reference:
            note = "Reviewed source ingredient line" if quantity is not None else "Source gives no amount"
        records.append({
            "recipeId": row["recipeId"], "position": position,
            "ingredient_name": name,
            "ingredient_key": normalize_ingredient(name) if name else None,
            "quantity_text": quantity,
            "pair_status": "verified" if quantity is not None else "unresolved",
            "source_url": reference["url"] if reference else None,
            "source_line": line, "alternative_group": alternative,
            "source_added": int(position > len(names)), "pair_note": note,
        })
    return records


def export(input_metadata_db, output_dir):
    """Copy retained metadata recipes and add one table for backend display."""
    source, output = Path(input_metadata_db).resolve(), Path(output_dir).resolve()
    if not source.is_file():
        raise FileNotFoundError(f"Cleaned database not found: {source}")
    if output.exists():
        raise FileExistsError(f"Choose a new output directory: {output}")
    before = file_hash(source)
    summary = {"recipes_processed": 0, "fully_verified_recipes": 0,
               "partially_verified_recipes": 0, "unresolved_recipes": 0,
               "recipes_without_ingredients": 0, "verified_ingredient_rows": 0,
               "unresolved_ingredient_rows": 0, "source_added_ingredient_rows": 0,
               "raw_count_mismatches": 0, "source_sha256_before": before,
               "reviewed_source_date": REVIEW_DATE}
    with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as original:
        original.row_factory = sqlite3.Row
        original.execute("PRAGMA query_only=ON")
        tables = {row[0] for row in original.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"recipes", "recipe_metadata", "removed_recipes"}.issubset(tables):
            raise ValueError("Use the cleaned metadata database, not the original db.db")
        overlap = original.execute("SELECT COUNT(*) FROM recipes JOIN removed_recipes USING(recipeId)").fetchone()[0]
        if overlap or "recipe_ingredients" in tables:
            raise ValueError("Input contains removed recipe IDs or already has recipe_ingredients")
        output.mkdir(parents=True)
        (output / ".gitignore").write_text("*\n", encoding="utf-8")
        with closing(sqlite3.connect(output / "metadata_with_ingredients.db")) as cleaned:
            original.backup(cleaned)
            cleaned.execute("PRAGMA foreign_keys=ON")
            cleaned.execute("""CREATE TABLE recipe_ingredients(
                recipeId INTEGER REFERENCES recipes(recipeId), position INTEGER NOT NULL,
                ingredient_name TEXT, ingredient_key TEXT, quantity_text TEXT,
                pair_status TEXT NOT NULL CHECK(pair_status IN ('verified','unresolved')),
                source_url TEXT, source_line INTEGER, alternative_group TEXT,
                source_added INTEGER NOT NULL, pair_note TEXT,
                PRIMARY KEY(recipeId,position),
                CHECK((pair_status='verified' AND quantity_text IS NOT NULL AND source_url IS NOT NULL)
                   OR (pair_status='unresolved' AND quantity_text IS NULL)))""")
            for row in original.execute("SELECT * FROM recipes ORDER BY recipeId"):
                records = build_pairs(row)
                summary["recipes_processed"] += 1
                verified = sum(record["pair_status"] == "verified" for record in records)
                unresolved = len(records) - verified
                summary["verified_ingredient_rows"] += verified
                summary["unresolved_ingredient_rows"] += unresolved
                summary["source_added_ingredient_rows"] += sum(record["source_added"] for record in records)
                if not records:
                    summary["recipes_without_ingredients"] += 1
                elif not unresolved:
                    summary["fully_verified_recipes"] += 1
                elif verified:
                    summary["partially_verified_recipes"] += 1
                else:
                    summary["unresolved_recipes"] += 1
                try:
                    names, quantities = parse_ingredients(row[PARTS]), parse_ingredients(row[QUANTITIES])
                    summary["raw_count_mismatches"] += names is not None and quantities is not None and len(names) != len(quantities)
                except ValueError:
                    pass
                cleaned.executemany("INSERT INTO recipe_ingredients VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                    [tuple(record.values()) for record in records])
            cleaned.execute("CREATE INDEX recipe_ingredients_key ON recipe_ingredients(ingredient_key)")
            cleaned.commit()
            summary["integrity"] = cleaned.execute("PRAGMA integrity_check").fetchone()[0]
            summary["foreign_key_violations"] = cleaned.execute("PRAGMA foreign_key_check").fetchall()
    summary["source_sha256_after"] = file_hash(source)
    summary["source_unchanged"] = before == summary["source_sha256_after"]
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    if not summary["source_unchanged"] or summary["integrity"] != "ok" or summary["foreign_key_violations"]:
        raise RuntimeError("Verification failed; review the generated output")
    return summary


def main():
    parser = argparse.ArgumentParser(description="Export verified ingredient quantities without guessing")
    parser.add_argument("--db", type=Path, required=True, help="Cleaned metadata database")
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    output = args.output_dir or Path(__file__).resolve().parent / "quantity_output.local" / datetime.now().strftime("run_%Y%m%d_%H%M%S_%f")
    print(json.dumps(export(args.db, output), indent=2))
    print(f"Output directory: {output.resolve()}")


if __name__ == "__main__":
    main()
