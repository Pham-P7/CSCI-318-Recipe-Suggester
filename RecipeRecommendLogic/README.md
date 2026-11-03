# Recipe Recommendation Service

A Java 21 module for searching, filtering, and ranking recipes using ingredient names and calorie values.

**Current status:** The service logic and its tests are implemented. Database integration and HTTP API endpoints are not implemented in this module. The caller must supply recipes as Java objects, including their ingredient lists.

## Requirements

- JDK 21.
- No external dependencies or test framework are required for the current service.

## Project Structure

```text
RecipeRecommendLogic/
├── README.md
├── src/com/
│   ├── model/
│   │   ├── Recipe.java
│   │   ├── RecipeIngredient.java
│   │   └── RecommendationResult.java
│   └── service/
│       └── RecommendationService.java
└── tests/com/service/
    ├── RecommendationServiceTest.java
    ├── RecipeSearchTest.java
    └── RecipePreferencesTest.java
```

## Available Methods

All methods below belong to `com.service.RecommendationService`.

| Method | Purpose | Returns |
| --- | --- | --- |
| `getRecommendations(userIngredients, recipes)` | Recommend recipes using available ingredients. | `List<RecommendationResult>` |
| `getRecommendations(userIngredients, recipes, targetCalories)` | Recommend recipes, using a calorie target to break ties in ingredient match ratio. | `List<RecommendationResult>` |
| `searchByName(keyword, recipes)` | Search recipe names using a case-insensitive substring. | `List<Recipe>` |
| `searchByDetails(minCalories, maxCalories, category, userIngredients, recipes)` | Apply the supplied calorie, category, and ingredient conditions together. | `List<Recipe>` |
| `filterByExcludedIngredients(recipes, excludedIngredients)` | Remove recipes containing any specified ingredient name. | `List<Recipe>` |
| `sortByCalorieTarget(recipes, targetCalories)` | Rank recipes by their distance from a target calorie value. | `List<Recipe>` |

### Ingredient Recommendations

Ingredient names are trimmed, converted to lowercase, and deduplicated before matching. Matching uses complete names, not ingredient IDs, substrings, or inferred synonyms.

A recipe must match at least one available ingredient to be recommended. Its match ratio is:

```text
number of distinct matched ingredients / number of distinct required ingredients
```

For example, a recipe requiring `chicken`, `onion`, and `salt` has a match ratio of `2 / 3` when the user has `chicken` and `onion`.

Each `RecommendationResult` contains:

| Getter | Value |
| --- | --- |
| `getRecipe()` | The original recipe object. |
| `getMatchedIngredients()` | Normalized ingredient names that the user has. |
| `getMissingIngredients()` | Normalized ingredient names that the user lacks. |
| `getMatchRatio()` | A number from 0 to 1; multiply by 100 when displaying a percentage. |

Without a calorie target, recommendations are ordered by **match ratio descending, then recipe ID ascending**. Passing `null` as the optional target gives the same behavior as the two-argument method.

With a calorie target, the order is:

1. Ingredient match ratio, highest first.
2. Within equal match ratios, valid calorie values before invalid values, then distance from the target, smallest first.
3. Recipe ID, smallest first.

A closer calorie value never lets a lower-match recipe outrank a higher-match recipe. The service does not calculate a weighted score.

### Recipe Search

`searchByName` searches only `Recipe.name`, not keywords. The keyword is trimmed and matched without case sensitivity. Results are ordered by recipe ID ascending.

`searchByDetails` combines all supplied conditions with **AND**:

- Calorie bounds are inclusive. Either bound can be omitted.
- Category uses a complete, case-insensitive match after trimming.
- An ingredient condition requires at least one matching ingredient, not every ingredient.

With an ingredient condition, results are ordered by match ratio descending, then ID ascending. Without an ingredient condition, results are ordered by ID ascending and do not require ingredient data.

### Excluding Ingredients

A recipe is removed if any recorded ingredient name matches an excluded name after normalization. The remaining recipes keep their original relative order.

For example, excluding `peanut` matches `" PEANUT "`, but does not automatically match `peanut oil` or `groundnut`.

When exclusions are enabled, recipes with missing, empty, or invalid ingredient data are skipped. When the exclusion list is `null` or empty, the restriction is disabled and incomplete ingredient data does not prevent a recipe from being returned.

**This is ingredient-name exclusion only. It does not provide complete vegetarian, vegan, gluten-free, or allergen identification.**

### Calorie Range vs. Calorie Target

These operations serve different purposes:

- A range in `searchByDetails` removes recipes outside the accepted calorie range.
- `sortByCalorieTarget` keeps recipes and changes their order according to `Math.abs(calories - targetCalories)`.

For ordinary calorie sorting, valid values are ordered by distance, then ID. Invalid calorie values—NaN, either infinity, or negative numbers—are retained after all valid values and ordered by ID.

A calorie range filter excludes invalid calorie values. Without calorie bounds, detail search does not validate recipe calorie values.

## Usage Examples

These snippets assume that `recipes` is an existing `List<Recipe>` supplied by the caller. Database text must already have been converted into model objects.

```java
import com.model.Recipe;
import com.model.RecommendationResult;
import com.service.RecommendationService;
import java.util.List;
```

### Search, Exclude Ingredients, and Sort by Calories

```java
RecommendationService service = new RecommendationService();

List<Recipe> found = service.searchByName("chicken", recipes);
List<Recipe> filtered = service.filterByExcludedIngredients(
        found, List.of("peanut"));
List<Recipe> sorted = service.sortByCalorieTarget(filtered, 500.0);
```

To first restrict calories to an acceptable range, use:

```java
RecommendationService service = new RecommendationService();

List<Recipe> candidates = service.searchByDetails(
        300.0, 600.0, null, null, recipes);
List<Recipe> closest = service.sortByCalorieTarget(candidates, 500.0);
```

This accepts only recipes between 300 and 600 and puts those closest to 500 first.

### Exclude Ingredients and Generate Recommendation Reports

```java
RecommendationService service = new RecommendationService();

List<Recipe> filtered = service.filterByExcludedIngredients(
        recipes, List.of("peanut"));
List<RecommendationResult> reports = service.getRecommendations(
        List.of("chicken", "onion"), filtered, 500.0);

for (RecommendationResult report : reports) {
    System.out.println(report.getRecipe().getName());
    System.out.println("Match: " + report.getMatchRatio() * 100 + "%");
    System.out.println("Available: " + report.getMatchedIngredients());
    System.out.println("Missing: " + report.getMissingIngredients());
}
```

For recommendations without a calorie target:

```java
RecommendationService service = new RecommendationService();

List<RecommendationResult> reports = service.getRecommendations(
        List.of("chicken", "onion"), recipes);
```

## Input Validation and Missing Data

Invalid requests throw `IllegalArgumentException`. Valid requests with no matches return an empty list.

- Every public service method requires a non-null recipe list. An empty recipe list is valid. Null recipe entries are always skipped.
- Recommendation methods require a nonempty user ingredient list. Null or blank names are invalid.
- Name search requires a non-null, nonblank keyword.
- Detail search requires at least one condition. Null calorie bounds, a null/blank category, and a null ingredient list mean those conditions are absent. An explicitly supplied empty ingredient list is invalid.
- Calorie bounds and targets must be finite and nonnegative. Zero is valid. A minimum calorie bound cannot exceed the maximum.
- A null or empty exclusion list disables exclusions. A nonempty list cannot contain null or blank names, even when there are no candidate recipes.
- Ingredient-based recommendations and searches skip recipes whose ingredient list is missing, empty, or contains null entries or null/blank names. Enabled exclusions apply the same completeness requirement.
- Every public method returns a new list container and does not modify input lists, recipes, or ingredients. Returned recipes are references to the original objects, not deep copies. Matched and missing ingredient lists in a recommendation report are unmodifiable.

## Data Limitations and Integration Work

- Calories are read directly from `Recipe.calories`. The service does not divide by servings or assume values are per serving.
- The primitive `double` calorie field cannot distinguish missing data from a real zero. Zero is currently treated as a valid value.
- A complete match means all **recorded** ingredient names matched. The service cannot detect ingredients omitted by the data source or guarantee that the user has everything needed to cook a recipe.
- Cuisine ranking is deferred until a reliable cuisine field or approved mapping is available. Category, recipe names, keywords, and ingredients are not used to infer cuisine.
- Cooking-time filtering is not implemented. Time meaning, missing values, and invalid records need agreed handling before integration.
- Database loading, parsing stored ingredient text, HTTP endpoints, JSON responses, and frontend integration remain separate work.

## Compile and Run Tests

Run the following commands from this module's root directory, where `src` and `tests` are located. The commands use a temporary output directory and require a POSIX shell, such as the macOS Terminal shell.

```sh
set -eu
recipe_test_output=$(mktemp -d)
trap 'rm -rf "$recipe_test_output"' EXIT

javac --release 21 -Xlint:all -d "$recipe_test_output" \
    src/com/model/*.java \
    src/com/service/*.java \
    tests/com/service/*.java

java -ea -cp "$recipe_test_output" com.service.RecommendationServiceTest
java -ea -cp "$recipe_test_output" com.service.RecipeSearchTest
java -ea -cp "$recipe_test_output" com.service.RecipePreferencesTest
```

Expected output:

```text
PASS: 7 recommendation scenarios.
PASS: 16 search scenarios.
PASS: 22 preference scenarios.
```

The tests use Java `main` methods and assertions. The `-ea` option enables assertions. The 45 scenarios cover matching, normalization, search conditions, exclusions, calorie ordering, invalid inputs, missing data, combined calls, and preservation of input data. These are service-level tests; they do not establish that database or HTTP integration works.
