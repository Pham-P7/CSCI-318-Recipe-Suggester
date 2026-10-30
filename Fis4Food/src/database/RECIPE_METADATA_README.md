# Recipe Metadata Cleaning

We use `recipe_metadata_cleaning.py` to clean recipe details before preparing ingredients.
It reads `db.db` and saves a separate SQLite copy. The original database is not changed.

## What the Script Does

- Checks missing values, times, servings, nutrition, images, keywords, and instructions.
- Converts supported times to minutes and uses `NULL` for missing or invalid numbers and times.
- Parses image, keyword, and instruction lists while keeping the original text where possible.
- Recovers clear instruction quote and escape problems, and records changes and warnings.
- Keeps uncertain values for review instead of guessing replacements.


## Cleaned Database Output

The output folder contains:

- `metadata_cleaned.db`: the cleaned SQLite copy.
- `removed_recipes.jsonl`: removed IDs, reasons, and original values.
- `before_audit.json` and `after_audit.json`: checks before and after cleaning.
- `summary.json`: processing counts and a check that the input database did not change.

The cleaned database has four tables:

- `recipes`: retained recipes with the original column names.
- `recipe_metadata`: times in minutes and parsed lists.
- `cleaning_issues`: changes and warnings for retained recipes.
- `removed_recipes`: records of excluded recipes.

The backend can join recipe details and parsed values using `recipeId`:

```sql
SELECT r.recipeId, r.name, m.total_minutes, m.image_urls
FROM recipes r JOIN recipe_metadata m USING (recipeId);
```

Decode `image_urls`, `keyword_list`, and `instruction_list` with `json.loads()`.
Empty lists mean no items are available. Missing scalar values are SQL `NULL`.
Parsed lists can also contain JSON `null` for missing or unreadable values.

## Important Notes

- Valid zero times are kept. Missing cooking time alone does not remove a recipe.
- A known zero cooking or total time that conflicts with timed cooking steps can cause removal.
  The script also excludes recipes whose instructions still cannot be parsed reliably.
  Optional or uncertain cooking directions are kept for review.
- Removals happen only in the new copy. Retained recipes keep their original IDs,
  which are not necessarily consecutive; use IDs rather than row numbers.
- Suspicious values still need review. Times and nutrition values are not invented,
  and image links are checked for format without downloading images.

