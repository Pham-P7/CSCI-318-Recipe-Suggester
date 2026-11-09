"""Audit recipe fields and clean a separate SQLite copy using standard libraries."""

import argparse
from contextlib import closing
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
from urllib.parse import urlsplit

from ingredient_processing import parse_ingredients

DATABASE = Path(__file__).resolve().with_name("db.db")
TIME_FIELDS = {"prepTime": "prep_minutes", "cookTime": "cook_minutes", "totalTime": "total_minutes"}
NUMBER_FIELDS = ("calories", "fat", "saturatedFat", "cholesterol", "sodium",
                 "carbohydrate", "fiber", "sugar", "protein")
TEXT_FIELDS = ("name", "authorName", "description", "recipeCategory")
LIST_FIELDS = {"images": "image_urls", "keywords": "keyword_list", "recipeInstructions": "instruction_list"}
MISSING = ("NA", "NULL", "NA_character_", "NA_integer_", "NA_real_", "character(0)")
# Days, then optional hours, minutes, and seconds. Years/months are not guessed.
DURATION = re.compile(r"P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?")
# Require a heating action and a positive duration in the same clause.
TIMED_COOKING = re.compile(
    r"(?:^|[.;:]|\band\b|\bthen\b)\s*"
    r"(?P<action>cook|bake|boil|simmer|fry|roast|grill|broil|steam|microwave|saute|sauté)\b"
    r"(?P<context>[^.;:\n]{0,160}?)\b"
    r"(?P<amount>\d+(?:\.\d+)?(?:\s*/\s*\d+)?(?:\s*(?:-|to)\s*\d+(?:\.\d+)?)?)\s*"
    r"(?P<unit>minutes?|mins?|hours?|hrs?|seconds?|secs?)\b", re.IGNORECASE)
OTHER_ACTION = re.compile(
    r"\b(cool|chill|rest|stand|marinate|refrigerate|freeze|sit|set|place|transfer|remove|serve|drain|when|if|unless|not|never|avoid|without)\b",
    re.IGNORECASE)
CONDITIONAL = re.compile(r"\b(optional|if|alternatively|otherwise|may|can also|could)\b", re.IGNORECASE)


def is_missing(value):
    return value is None or isinstance(value, str) and (not value.strip() or value.strip() in MISSING)


def parse_time(value):
    """Return minutes and a problem code. A valid zero stays zero."""
    if is_missing(value):
        return None, "missing"
    if not isinstance(value, str):
        return None, "invalid_time"
    match = DURATION.fullmatch(value.strip())
    if not match or not any(match.groups()) or value.strip().endswith("T"):
        return None, "invalid_time"
    minutes = 0.0
    for part, multiplier in zip(match.groups(), (1440, 60, 1, 1 / 60)):
        if part is not None:
            minutes += float(part) * multiplier
    if not math.isfinite(minutes):
        return None, "invalid_time"
    return minutes, None


def clean_number(value, serving=False, upper=None):
    """Remove invalid numbers; retain high values with a review flag."""
    if is_missing(value):
        return None, "missing"
    try:
        number = float(value)
    except (ValueError, TypeError, OverflowError):
        return None, "invalid_number"
    if not math.isfinite(number) or number < 0:
        return None, "invalid_number"
    if serving:
        if number == 0 or not number.is_integer():
            return None, "invalid_serving"
        number = int(number)
        upper = 1000
    if upper is not None and number > upper:
        return number, "high_value_review"
    return number, None


def parse_list(value, urls=False):
    """Reuse the ingredient parser; never fetch image URLs."""
    if is_missing(value):
        return [], "missing"
    try:
        values = parse_ingredients(value)
    except ValueError:
        return None, "parse_error"
    if not values:
        return [], "empty_list"
    cleaned = []
    problem = None
    for item in values:
        if is_missing(item):
            item = None
            problem = "missing_elements"
        if urls:
            try:
                address = urlsplit(item or "")
                port = address.port
                valid = address.scheme in ("http", "https") and bool(address.hostname)
                valid = valid and not any(character.isspace() for character in item or "")
            except ValueError:
                valid = False
            if not valid:
                problem = "invalid_url"
                continue
        cleaned.append(item)
    return cleaned, problem


def parse_instructions(value):
    """Recover only clear inch marks or quoted words in a single instruction."""
    values, code = parse_list(value)
    if code == "parse_error" and isinstance(value, str):
        text = value.strip()
        if text.startswith('"') and text.endswith('"') and "\\" not in text:
            inner = text[1:-1]
            checked = re.sub(r'(?<=\d)"', "", inner)
            checked = re.sub(r'"[A-Za-z]+(?:[ -][A-Za-z]+)*"', "", checked)
            if '"' not in checked and not re.search(r'"\s*,\s*"', inner):
                return [inner], "recovered_scalar_quotes"
    if values and any(item and any(128 <= ord(char) <= 159 for char in item) for item in values):
        code = "legacy_encoding_review"
    return values, code


def clean_recipe(row, upper_bounds=None):
    """Make only safe scalar changes; keep raw lists and all ingredient data."""
    upper_bounds = upper_bounds or {}
    updates, metadata, issues = {}, {}, []
    fields = TEXT_FIELDS + tuple(TIME_FIELDS) + NUMBER_FIELDS + ("recipeServing",)
    for field in fields:
        original = row[field]
        value, code = original, None
        if field in TIME_FIELDS:
            minutes, code = parse_time(original)
            metadata[TIME_FIELDS[field]] = minutes
            if code:
                value = None
            elif minutes > 7 * 24 * 60:
                # Long recipes may be valid; this is only a review threshold.
                code = "long_time_review"
        elif field in NUMBER_FIELDS or field == "recipeServing":
            value, code = clean_number(original, field == "recipeServing", upper_bounds.get(field))
        elif is_missing(original):
            value, code = None, "missing"
        if value != original:
            updates[field] = value
        if code or field in updates:
            issues.append({"field": field, "code": code or "numeric_conversion",
                           "original_value": original, "cleaned_value": value,
                           "action": "changed" if field in updates else "review"})

    for field, target in LIST_FIELDS.items():
        original = row[field]
        if field == "recipeInstructions":
            values, code = parse_instructions(original)
        else:
            values, code = parse_list(original, urls=field == "images")
        metadata[target] = values
        # Missing markers become SQL NULL, but valid or unparseable raw lists stay.
        if is_missing(original) and original is not None:
            updates[field] = None
        if code:
            issues.append({"field": field, "code": code, "original_value": original,
                           "cleaned_value": None if field in updates else values,
                           "action": "changed" if field in updates else "review"})

    prep, cook, total = (metadata[key] for key in ("prep_minutes", "cook_minutes", "total_minutes"))
    if prep is not None and cook is not None and total is not None and not math.isclose(prep + cook, total, abs_tol=0.01):
        issues.append({"field": "totalTime", "code": "time_sum_mismatch", "original_value": row["totalTime"],
                       "cleaned_value": row["totalTime"], "action": "review"})
    if total is not None and any(part is not None and total < part for part in (prep, cook)):
        issues.append({"field": "totalTime", "code": "total_below_component", "original_value": row["totalTime"],
                       "cleaned_value": row["totalTime"], "action": "review"})
    instructions = str(row["recipeInstructions"] or "").lower()
    if total == 0 and re.search(r"\b(cook|bake|boil|simmer|fry|roast|grill)\b", instructions):
        issues.append({"field": "totalTime", "code": "zero_total_with_cooking_steps", "original_value": row["totalTime"],
                       "cleaned_value": row["totalTime"], "action": "review"})
    return updates, metadata, issues


def time_conflict_evidence(metadata, name=""):
    """Find explicit timed cooking that contradicts a known zero, not a missing time."""
    zero_fields = [field for field in ("cook_minutes", "total_minutes") if metadata[field] == 0]
    if not zero_fields:
        return None
    steps = metadata["instruction_list"] or []
    # Mixes/jars may include directions for later use; optional sections are uncertain.
    if re.search(r"\b(jar|mix)\b", name or "", re.IGNORECASE):
        return None
    if any(step and re.match(r"\s*optional\s*:", step, re.IGNORECASE) for step in steps):
        return None
    for step in steps:
        if not step or CONDITIONAL.search(step):
            continue
        for match in TIMED_COOKING.finditer(step):
            numbers = re.findall(r"\d+(?:\.\d+)?", match["amount"])
            negative = re.search(r"(?<![\d-])[-−]\s*$", match["context"])
            if (OTHER_ACTION.search(match["context"]) or negative
                    or not all(float(number) > 0 for number in numbers)):
                continue
            return {"zero_fields": zero_fields, "instruction": step,
                    "matched_text": match.group().strip()}
    return None


def audit_database(connection):
    """Inspect every recipe column before making the cleaned copy."""
    columns = connection.execute("PRAGMA table_info(recipes)").fetchall()
    if not columns:
        raise ValueError("The source has no recipes table")
    report = {"row_count": connection.execute("SELECT COUNT(*) FROM recipes").fetchone()[0],
              "columns": {}, "nutrition_upper_bounds": {}}
    for column in columns:
        name = column[1]
        quoted = '"' + name.replace('"', '""') + '"'
        profile = {"declared_type": column[2], "missing": 0, "invalid": 0,
                   "zero": 0, "types": {}, "problem_codes": {}}
        numbers = []
        for value, storage in connection.execute(f"SELECT {quoted}, typeof({quoted}) FROM recipes"):
            profile["types"][storage] = profile["types"].get(storage, 0) + 1
            profile["missing"] += is_missing(value)
            code, number = None, None
            if name in TIME_FIELDS:
                number, code = parse_time(value)
            elif name in NUMBER_FIELDS or name == "recipeServing":
                number, code = clean_number(value, serving=name == "recipeServing")
            elif name in LIST_FIELDS or name in ("recipeIngredientParts", "recipeIngredientQuantities"):
                if name == "recipeInstructions":
                    parsed, code = parse_instructions(value)
                else:
                    parsed, code = parse_list(value, urls=name == "images")
            if number is not None:
                profile["zero"] += number == 0
                numbers.append(number)
            if code:
                profile["problem_codes"][code] = profile["problem_codes"].get(code, 0) + 1
                profile["invalid"] += code in ("invalid_time", "invalid_number", "invalid_serving", "parse_error", "invalid_url")
        profile["distinct_values"] = connection.execute(f"SELECT COUNT(DISTINCT {quoted}) FROM recipes").fetchone()[0]
        if numbers:
            profile["minimum"], profile["maximum"] = min(numbers), max(numbers)
        if name in NUMBER_FIELDS and numbers:
            numbers.sort()
            upper = numbers[math.ceil(len(numbers) * 0.999) - 1]
            report["nutrition_upper_bounds"][name] = upper
            profile["high_tail_review"] = sum(number > upper for number in numbers)
        report["columns"][name] = profile
    report["repeated_name_groups"] = connection.execute(
        "SELECT COUNT(*) FROM (SELECT 1 FROM recipes GROUP BY lower(trim(name)) HAVING COUNT(*)>1)"
    ).fetchone()[0]
    return report


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1048576), b""):
            digest.update(block)
    return digest.hexdigest()


def clean_database(db_path, output_dir):
    """Audit read-only input, then clean a new SQLite copy with the same recipe IDs."""
    source, output = Path(db_path).resolve(), Path(output_dir).resolve()
    if not source.is_file():
        raise FileNotFoundError(f"Database not found: {source}")
    if output.exists():
        raise FileExistsError(f"Choose a new output directory: {output}")
    before = file_hash(source)
    with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as original:
        original.row_factory = sqlite3.Row
        original.execute("PRAGMA query_only=ON")
        audit = audit_database(original)
        output.mkdir(parents=True)
        (output / ".gitignore").write_text("*\n!.gitignore\n", encoding="utf-8")
        (output / "before_audit.json").write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
        summary = {"total_recipes": audit["row_count"], "processed": 0, "retained": 0, "removed": 0,
                   "removed_by_reason": {}, "zero_time_reviewed": 0, "zero_time_corrected": 0,
                   "zero_time_retained": 0, "zero_time_removed": 0, "rows_changed": 0,
                   "rows_flagged_review": 0, "issues": {}, "source_sha256_before": before,
                   "nutrition_basis": "Unverified; original values retained without serving rescaling.",
                   "outlier_rule": "Strictly above each source column's nearest-rank P99.9; review only."}
        with (
            closing(sqlite3.connect(output / "metadata_cleaned.db")) as cleaned,
            (output / "removed_recipes.jsonl").open("x", encoding="utf-8") as removal_file,
        ):
            original.backup(cleaned)
            cleaned.execute("PRAGMA foreign_keys=ON")
            cleaned.execute("CREATE TABLE recipe_metadata(recipeId INTEGER PRIMARY KEY REFERENCES recipes(recipeId), prep_minutes REAL, cook_minutes REAL, total_minutes REAL, image_urls TEXT, keyword_list TEXT, instruction_list TEXT)")
            cleaned.execute("CREATE TABLE cleaning_issues(recipeId INTEGER REFERENCES recipes(recipeId), field TEXT, code TEXT, original_value TEXT, cleaned_value TEXT, action TEXT)")
            # Removed IDs have no foreign key because their recipe rows are absent.
            cleaned.execute("CREATE TABLE removed_recipes(recipeId INTEGER PRIMARY KEY, reason TEXT, evidence TEXT, original_values TEXT)")
            for row in original.execute("SELECT * FROM recipes ORDER BY recipeId"):
                updates, metadata, issues = clean_recipe(row, audit["nutrition_upper_bounds"])
                summary["processed"] += 1
                zero_time = metadata["cook_minutes"] == 0 or metadata["total_minutes"] == 0
                summary["zero_time_reviewed"] += zero_time
                evidence = time_conflict_evidence(metadata, row["name"])
                reason = "unrecoverable_zero_time" if evidence else None
                if metadata["instruction_list"] is None:
                    reason = "instruction_parse_error"
                    evidence = {"error": "Instructions cannot be parsed reliably"}
                if reason:
                    saved = {field: row[field] for field in ("prepTime", "cookTime", "totalTime", "recipeInstructions")}
                    record = {"recipeId": row["recipeId"], "reason": reason,
                              "evidence": evidence, "original_values": saved}
                    cleaned.execute("DELETE FROM recipes WHERE recipeId=?", (row["recipeId"],))
                    cleaned.execute("INSERT INTO removed_recipes VALUES(?,?,?,?)",
                                    (row["recipeId"], reason, json.dumps(evidence), json.dumps(saved)))
                    removal_file.write(json.dumps(record, ensure_ascii=False) + "\n")
                    summary["removed"] += 1
                    summary["removed_by_reason"][reason] = summary["removed_by_reason"].get(reason, 0) + 1
                    summary["zero_time_removed"] += zero_time
                    continue
                summary["retained"] += 1
                summary["zero_time_retained"] += zero_time
                if updates:
                    assignments = ",".join('"' + name + '"=?' for name in updates)
                    cleaned.execute("UPDATE recipes SET " + assignments + " WHERE recipeId=?", list(updates.values()) + [row["recipeId"]])
                    summary["rows_changed"] += 1
                values = [metadata[key] for key in ("prep_minutes", "cook_minutes", "total_minutes")]
                values += [json.dumps(metadata[key], ensure_ascii=False) for key in ("image_urls", "keyword_list", "instruction_list")]
                cleaned.execute("INSERT INTO recipe_metadata VALUES(?,?,?,?,?,?,?)", [row["recipeId"]] + values)
                for issue in issues:
                    cleaned.execute("INSERT INTO cleaning_issues VALUES(?,?,?,?,?,?)", (row["recipeId"], issue["field"], issue["code"], json.dumps(issue["original_value"], ensure_ascii=False), json.dumps(issue["cleaned_value"], ensure_ascii=False), issue["action"]))
                    summary["issues"][issue["code"]] = summary["issues"].get(issue["code"], 0) + 1
                summary["rows_flagged_review"] += any(issue["action"] == "review" for issue in issues)
            cleaned.execute("CREATE INDEX cleaning_issues_recipe ON cleaning_issues(recipeId)")
            cleaned.commit()
            summary["output_integrity"] = cleaned.execute("PRAGMA integrity_check").fetchone()[0]
            summary["foreign_key_violations"] = cleaned.execute("PRAGMA foreign_key_check").fetchall()
            (output / "after_audit.json").write_text(json.dumps(audit_database(cleaned), indent=2) + "\n", encoding="utf-8")
    summary["source_sha256_after"] = file_hash(source)
    summary["source_unchanged"] = before == summary["source_sha256_after"]
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    if not summary["source_unchanged"]:
        raise RuntimeError("Source changed during processing; review outputs before use")
    return summary


def main():
    parser = argparse.ArgumentParser(description="Clean recipe metadata in a separate SQLite copy")
    parser.add_argument("--db", type=Path, default=DATABASE)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    output = args.output_dir or DATABASE.parent / "metadata_output.local" / datetime.now().strftime("run_%Y%m%d_%H%M%S_%f")
    print(json.dumps(clean_database(args.db, output), indent=2))
    print(f"Output directory: {output.resolve()}")


if __name__ == "__main__":
    main()
