import argparse
from datetime import datetime
import json
from pathlib import Path
import sqlite3

DATABASE = Path(__file__).resolve().with_name("db.db")
PARTS = "recipeIngredientParts"
QUANTITIES = "recipeIngredientQuantities"
MISSING = ("NA", "NULL", "NA_character_", "NA_integer_", "NA_real_")
ALIASES = {
    "eggs": "egg", "chicken breasts": "chicken breast",
    "boneless chicken breasts": "boneless chicken breast",
    "garlic cloves": "garlic clove", "onions": "onion", "carrots": "carrot",
    "potatoes": "potato", "tomatoes": "tomato", "lemons": "lemon", "limes": "lime",
}


def parse_ingredients(value):
    """Read a quoted value or c(...) list, keeping commas inside quoted names."""
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError("Expected text or a missing value")
    text = value.strip()
    if not text or text in MISSING:
        return None
    if "".join(text.split()) == "character(0)":
        return []
    is_vector = text.startswith("c")
    if is_vector:
        text = text[1:].lstrip()
        if not text.startswith("(") or not text.endswith(")"):
            raise ValueError("Invalid vector parentheses")
        text = text[1:-1].strip()
    if not text:
        return []

    escapes = {"n": "\n", "r": "\r", "t": "\t", "a": "\a", "b": "\b",
               "f": "\f", "v": "\v", "\\": "\\", '"': '"', "'": "'"}
    values = []
    index = 0
    while index < len(text):
        while index < len(text) and text[index].isspace():
            index += 1
        if text[index] in ('"', "'"):
            quote = text[index]
            index += 1
            name = ""
            while index < len(text) and text[index] != quote:
                character = text[index]
                if character == "\\":
                    index += 1
                    if index == len(text) or text[index] not in escapes:
                        raise ValueError("Unsupported or unfinished escape sequence")
                    character = escapes[text[index]]
                name += character
                index += 1
            if index == len(text):
                raise ValueError("Unclosed quote")
            index += 1
            values.append(name)
        else:
            end = index
            while end < len(text) and text[end] != ",":
                end += 1
            marker = text[index:end].strip()
            if marker not in MISSING or marker == "NULL":
                raise ValueError("Expected a quoted name or NA")
            values.append(None)
            index = end
        while index < len(text) and text[index].isspace():
            index += 1
        if index == len(text):
            break
        if not is_vector or text[index] != "," or not text[index + 1:].strip():
            raise ValueError("Invalid separator or missing list element")
        index += 1
    return values


def normalize_ingredient(name):
    """Lowercase, clean whitespace, and apply exact aliases only."""
    name = " ".join(name.lower().split())
    return ALIASES.get(name, name)


def add_issue(recipe, field, code, message):
    recipe["issues"].append({"field": field, "code": code, "message": message,
                             "raw_value": recipe["raw_fields"][field]})


def process_recipe(row):
    """Parse two lists, prepare ingredient keys, and report data problems."""
    recipe = {
        "recipeId": row["recipeId"], "name": row["name"],
        "raw_fields": {PARTS: row[PARTS], QUANTITIES: row[QUANTITIES]},
        "fields": {}, "ingredients": [], "ingredient_keys": [], "issues": [],
        "status": "ok", "ingredient_count": None, "quantity_count": None,
        "ingredient_quantity_mismatch": None,
    }
    original_names = None
    # Parse ingredients and quantities independently. Keep all original values.
    for field in (PARTS, QUANTITIES):
        try:
            values = parse_ingredients(row[field])
        except ValueError as error:
            values = None
            add_issue(recipe, field, "parse_error", str(error))
            if field == PARTS:
                recipe["status"] = "parse_error"
        else:
            if field == QUANTITIES and values is None:
                add_issue(recipe, field, "missing_value", "Quantity field is missing")
        if field == PARTS:
            original_names = values
        cleaned = None
        if values is not None:
            cleaned = []
            for value in values:
                if value is None or not value.strip() or value.strip() in MISSING or value.strip() == "character(0)":
                    cleaned.append(None)
                else:
                    cleaned.append(value)
        recipe["fields"][field] = cleaned
        if field == QUANTITIES and cleaned and None in cleaned:
            add_issue(recipe, field, "missing_elements", "Some quantities are missing; positions preserved")

    names = recipe["fields"][PARTS]
    quantities = recipe["fields"][QUANTITIES]
    if recipe["status"] != "parse_error":
        if names is None:
            recipe["status"] = "missing"
            add_issue(recipe, PARTS, "missing_value", "Ingredient field is missing")
        elif not names:
            recipe["status"] = "empty"
            add_issue(recipe, PARTS, "empty_list", "Ingredient list is empty")

    # Keep every position for display, but count each matching key only once.
    duplicates = []
    if names is not None:
        recipe["ingredient_count"] = len(names)
        for position, name in enumerate(names, start=1):
            key = None
            if name is None:
                add_issue(recipe, PARTS, "missing_element", f"Missing ingredient at position {position}")
            else:
                candidate = normalize_ingredient(name)
                has_letter = False
                invalid_control = False
                for character in name:
                    if character.isalpha():
                        has_letter = True
                    if ord(character) < 32 and not character.isspace():
                        invalid_control = True
                if not has_letter or invalid_control:
                    add_issue(recipe, PARTS, "invalid_ingredient", f"Invalid ingredient at position {position}")
                else:
                    key = candidate
                    if key in recipe["ingredient_keys"]:
                        duplicates.append(key)
                    else:
                        recipe["ingredient_keys"].append(key)
            recipe["ingredients"].append({
                "recipeId": recipe["recipeId"], "position": position,
                "ingredient_raw": original_names[position - 1], "ingredient_key": key,
            })
    if duplicates:
        add_issue(recipe, PARTS, "duplicate_ingredients", "Repeated keys: " + ", ".join(duplicates))
    if quantities is not None:
        recipe["quantity_count"] = len(quantities)
    if names is not None and quantities is not None:
        recipe["ingredient_quantity_mismatch"] = len(names) != len(quantities)
        if recipe["ingredient_quantity_mismatch"]:
            add_issue(recipe, QUANTITIES, "count_mismatch", "Ingredient and quantity counts differ; no pairing applied")
    return recipe


def process_database(db_path, output_dir):
    """Stream recipes into separate output files; never change the source."""
    source = Path(db_path).resolve()
    output = Path(output_dir).resolve()
    if not source.is_file():
        raise FileNotFoundError(f"Database not found: {source}")
    if output.exists():
        raise FileExistsError(f"Choose a new output directory: {output}")
    summary = {
        "total_recipes_processed": 0, "successfully_parsed_recipes": 0,
        "recipes_with_missing_ingredients": 0, "recipes_with_empty_ingredients": 0,
        "recipes_with_duplicate_ingredients": 0, "ingredient_quantity_mismatches": 0,
        "ingredient_rows_written": 0, "normalized_ingredient_count": 0, "parsing_failures": 0,
    }
    all_keys = set()
    connection = sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)
    try:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only = ON")
        rows = connection.execute(f"SELECT recipeId, name, {PARTS}, {QUANTITIES} FROM recipes ORDER BY recipeId")
        output.mkdir(parents=True)
        (output / ".gitignore").write_text("*\n!.gitignore\n!summary.json\n", encoding="utf-8")
        with (
            (output / "ingredients.jsonl").open("x", encoding="utf-8") as ingredient_file,
            (output / "recipes.jsonl").open("x", encoding="utf-8") as recipe_file,
            (output / "validation_issues.jsonl").open("x", encoding="utf-8") as issue_file,
        ):
            for row in rows:
                recipe = process_recipe(row)
                summary["total_recipes_processed"] += 1
                if recipe["status"] != "parse_error":
                    summary["successfully_parsed_recipes"] += 1
                if recipe["status"] == "missing":
                    summary["recipes_with_missing_ingredients"] += 1
                if recipe["status"] == "empty":
                    summary["recipes_with_empty_ingredients"] += 1
                if recipe["ingredient_quantity_mismatch"] is True:
                    summary["ingredient_quantity_mismatches"] += 1
                for ingredient in recipe["ingredients"]:
                    ingredient_file.write(json.dumps(ingredient, ensure_ascii=False) + "\n")
                    summary["ingredient_rows_written"] += 1
                all_keys.update(recipe["ingredient_keys"])
                for issue in recipe["issues"]:
                    if issue["code"] == "duplicate_ingredients":
                        summary["recipes_with_duplicate_ingredients"] += 1
                    if issue["code"] == "parse_error":
                        summary["parsing_failures"] += 1
                    issue_file.write(json.dumps({"recipeId": recipe["recipeId"], **issue}, ensure_ascii=False) + "\n")
                recipe_file.write(json.dumps(recipe, ensure_ascii=False) + "\n")
    finally:
        connection.close()
    summary["normalized_ingredient_count"] = len(all_keys)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description="Clean recipe ingredients using read-only SQLite")
    parser.add_argument("--db", type=Path, default=DATABASE)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args(argv)
    output = args.output_dir
    if output is None:
        run_name = datetime.now().strftime("run_%Y%m%d_%H%M%S_%f")
        output = DATABASE.parent / "cleaned_output" / run_name
    summary = process_database(args.db, output)
    for name, count in summary.items():
        print(f"{name}: {count}")
    print(f"Output directory: {output.resolve()}")


if __name__ == "__main__":
    main()
