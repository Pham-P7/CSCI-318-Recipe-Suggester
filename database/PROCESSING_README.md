## What the Scripts Do

- `ingredient_processing.py` reads R-style lists and creates ingredient matching keys.
  It uses lowercase, removes extra spaces, and applies a few aliases, like `eggs` to `egg`.
- Original ingredient names and positions are kept. Missing values, duplicates,
  parsing errors, and ingredient-quantity count differences are reported.
- `ingredient_quantity_matching.py` adds a display table to a new database copy.
  It uses reviewed source quantities and leaves uncertain amounts as `NULL`.

## Output and Backend Use

The ingredient cleaning folder contains:

- `ingredients.jsonl`: original names, positions, and matching keys.
- `recipes.jsonl`: raw and parsed ingredient lists, quantities, and recipe issues.
- `validation_issues.jsonl`: data problems found during processing.
- `summary.json`: basic counts. Each JSONL file stores one record per line.

The quantity matching folder contains `metadata_with_ingredients.db` and `summary.json`.
The database keeps the metadata tables and adds `recipe_ingredients` for display:

```sql
SELECT ingredient_name, quantity_text, pair_status, alternative_group
FROM recipe_ingredients
WHERE recipeId = ?
ORDER BY COALESCE(source_line, position), position;
```

Show rows with the same non-NULL `alternative_group` as OR choices.
Keep unresolved ingredients visible with an unavailable-quantity label.
For recommendations, count repeated matching keys once.

## Important Notes

- Most quantities cannot be reliably matched: units are missing and lists often differ.
  Equal list lengths do not prove a correct pair. Never guess amounts or units.
- Reviewed quantity references currently cover Recipes 38, 39, and 42.
  Unverified amounts stay `NULL` with `pair_status = 'unresolved'`.
- Verified source additions use `source_added = 1`; original names and positions stay intact.
  `source_line` gives the source ingredient order for display.
- Use the cleaned metadata input so previously removed recipes stay excluded.

