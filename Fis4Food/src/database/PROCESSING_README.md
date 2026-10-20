# Data Cleaning

This is the data cleaning part of our CSCI 318 Recipe Recommender project.

The Python script organizes ingredient data from our SQLite database. The goal
is to make ingredient names easier to use for recipe searching and recommendations.

## What the Code Does

The script `ingredient_processing.py`:

1. Reads recipe IDs, names, ingredients, and quantities from `db.db`.
2. Converts R-style ingredient and quantity strings into Python lists.
3. Cleans ingredient names by converting them to lowercase and removing extra spaces.
4. Uses a small dictionary to standardize names such as `eggs` and `egg`.
5. Checks for missing values, duplicate ingredients, parsing errors, and quantity mismatches.
6. Saves the results in separate files and prints a basic summary.

The database is opened read-only and is not changed. Original recipe IDs,
ingredient names, and ingredient order are preserved. Matching keys are
stored separately, with duplicates removed only from those keys.

## Example

Original ingredient data:

```text
c("Eggs", "rice", "eggplant")
```

After parsing and cleaning, the matching keys become:

```text
["egg", "rice", "eggplant"]
```

`eggs` and `egg` are treated as the same ingredient. However, `egg` and
`eggplant` remain different ingredients.

## How to Run

Python 3.10 or later is required. No additional packages are needed.

Open the project folder in VS Code. From the project root, run this command
in the terminal:

```bash
python3 Fis4Food/src/database/ingredient_processing.py
```

The script reads `db.db` beside the Python file and saves the results in a new
timestamped folder inside `Fis4Food/src/database/cleaned_output/`.

## Output Files

- `ingredients.jsonl`: Recipe IDs, positions, original ingredient names, and matching keys.
- `recipes.jsonl`: Recipe names, original and parsed ingredient/quantity data, matching keys, and issues.
- `validation_issues.jsonl`: Missing values, parsing errors, duplicates, and other data problems.
- `summary.json`: Basic statistics about the cleaning results.

Each JSONL file contains one record per line. A `.gitignore` file is also created
to exclude the large JSONL files from Git. Keep generated outputs local.

## Notes

Some recipes have different numbers of ingredients and quantities. The script
reports these mismatches without inventing quantities or pairing them with ingredients.
Recipes with parsing errors are kept with their original values and error messages.

The code focuses on ingredients and quantities. It does not clean images,
nutrition information, or cooking times.


