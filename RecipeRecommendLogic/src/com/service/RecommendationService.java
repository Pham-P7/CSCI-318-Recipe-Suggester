package com.service;

import com.model.Recipe;
import com.model.RecipeIngredient;
import com.model.RecommendationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class RecommendationService {

    // Comparator to sort RecommendationResult by match ratio (descending) and then by recipe ID (ascending)
    private static final Comparator<RecommendationResult> RECOMMENDATION_ORDER =
            Comparator.comparingDouble(RecommendationResult::getMatchRatio).reversed()
                    .thenComparingInt(result -> result.getRecipe().getRecipeId());


    public List<RecommendationResult> getRecommendations(List<String> userIngredients, List<Recipe> recipes) {
        return getRecommendations(userIngredients, recipes, null);
    }


    // An optional calorie target only breaks ties between equal ingredient match ratios.
    public List<RecommendationResult> getRecommendations(List<String> userIngredients, List<Recipe> recipes,
                                                         Double targetCalories) {
        checkRecipes(recipes);
        checkCalorieBound(targetCalories);
        Set<String> availableIngredients = normalizeUserIngredients(userIngredients);
        List<RecommendationResult> results = new ArrayList<>();
        for (Recipe recipe : recipes) {
            RecommendationResult result = matchIngredients(recipe, availableIngredients);
            if (result != null) {
                results.add(result);
            }
        }

        Comparator<RecommendationResult> order = RECOMMENDATION_ORDER;

        if (targetCalories != null) {
            order = Comparator.comparingDouble(RecommendationResult::getMatchRatio).reversed()
                    .thenComparing(RecommendationResult::getRecipe, calorieTargetOrder(targetCalories));
        }
        results.sort(order);
        return results;
    }

    //Restriction ingredients filter function
    // Exclude complete ingredient names; this does not identify diets or allergens.
    public List<Recipe> filterByExcludedIngredients(List<Recipe> recipes, List<String> excludedIngredients) {

        checkRecipes(recipes);

        Set<String> excludedNames;

        if (excludedIngredients == null || excludedIngredients.isEmpty()) {
            excludedNames = Set.of();
        } else {
            excludedNames = normalizeUserIngredients(excludedIngredients);
        }

        List<Recipe> results = new ArrayList<>();

        for (Recipe recipe : recipes) {
            if (recipe == null) {
                continue;
            }

            // If there are no excluded ingredients, add the recipe to the results and continue to the next recipe.
            if (excludedNames.isEmpty()) {
                results.add(recipe);
                continue;
            }

            List<RecipeIngredient> ingredients = recipe.getIngredients();

            if (ingredients == null || ingredients.isEmpty()) {
                continue;
            }

            boolean keepRecipe = true;
            for (RecipeIngredient ingredient : ingredients) {
                if (ingredient == null) {
                    keepRecipe = false;
                    break;
                }

                String name = normalizeName(ingredient.getIngredientName());
                if (name.isEmpty() || excludedNames.contains(name)) {
                    keepRecipe = false;
                    break;
                }

            }

            if (keepRecipe) {
                results.add(recipe);
            }
        }

        return results;
    }


    // Sort a new list using stored calories, without dividing by servings.
    public List<Recipe> sortByCalorieTarget(List<Recipe> recipes, double targetCalories) {

        checkRecipes(recipes);
        checkCalorieBound(targetCalories);
        List<Recipe> results = new ArrayList<>();

        for (Recipe recipe : recipes) {
            if (recipe != null) {
                results.add(recipe);
            }
        }

        results.sort(calorieTargetOrder(targetCalories));
        return results;
    }


    //First search method: searchByName
    //search the recipe list by name keyword, return a list of recipes whose name contains the keyword, case-insensitive
    public List<Recipe> searchByName(String keyword, List<Recipe> recipes) {
        checkRecipes(recipes);
        String normalizedKeyword = normalizeName(keyword);

        if (normalizedKeyword.isEmpty()) {
            throw new IllegalArgumentException("A nonblank recipe name keyword is required.");
        }

        List<Recipe> results = new ArrayList<>();
        for (Recipe recipe : recipes) {
            if (recipe != null && normalizeName(recipe.getName()).contains(normalizedKeyword)) {
                results.add(recipe);
            }
        }

        results.sort(Comparator.comparingInt(Recipe::getRecipeId)); // Sort the results by recipe ID in ascending order
        return results;
    }


    //second search method: searchByDetails
    //search the recipe list by calories range, category and user ingredients,
    // return a list of recipes that match the criteria
    public List<Recipe> searchByDetails(Double minCalories, Double maxCalories, String category,
                                      List<String> userIngredients, List<Recipe> recipes) {
        checkRecipes(recipes);
        checkCalorieBound(minCalories);
        checkCalorieBound(maxCalories);
        if (minCalories != null && maxCalories != null && minCalories > maxCalories) {
            throw new IllegalArgumentException("Minimum calories must not exceed maximum calories.");
        }

        String normalizedCategory = normalizeName(category);
        // Normalize user ingredients to a set of lowercase, trimmed strings; null becomes null
        Set<String> availableIngredients = userIngredients == null
                ? null : normalizeUserIngredients(userIngredients);

        //check if all search conditions are null or empty, throw exception
        if (minCalories == null && maxCalories == null && normalizedCategory.isEmpty()
                && availableIngredients == null) {
            throw new IllegalArgumentException("At least one search condition is required.");
        }

        // Filter recipes based on the provided criteria
        List<Recipe> results = new ArrayList<>();
        List<RecommendationResult> ingredientMatches = new ArrayList<>();

        // Iterate through the recipes and apply the filters
        for (Recipe recipe : recipes) {
            if (recipe == null) {
                continue;
            }

            // Check calories range if specified
            if (minCalories != null || maxCalories != null) {
                double calories = recipe.getCalories();
                // Check if calories is finite and nonnegative, and within the specified bounds
                if (!Double.isFinite(calories) || calories < 0
                        || (minCalories != null && calories < minCalories)
                        || (maxCalories != null && calories > maxCalories)) {
                    continue;
                }
            }

            // Check category if specified
            if (!normalizedCategory.isEmpty()
                    && !normalizedCategory.equals(normalizeName(recipe.getCategory()))) {
                continue;
            }

            // Check ingredients if specified
            if (availableIngredients == null) {
                results.add(recipe);
            } else {
                RecommendationResult result = matchIngredients(recipe, availableIngredients);
                if (result != null) {
                    ingredientMatches.add(result);
                }
            }
        }

        // Sort the results based on the specified criteria
        if (availableIngredients == null) { //if no ingredient filter is applied, sort by recipe ID，ascending order
            results.sort(Comparator.comparingInt(Recipe::getRecipeId));

        } else { //if ingredient filter is applied, sort by match ratio (descending) and then by recipe ID (ascending)
            ingredientMatches.sort(RECOMMENDATION_ORDER);
            for (RecommendationResult result : ingredientMatches) {
                results.add(result.getRecipe());
            }
        }
        return results;
    }


    private Comparator<Recipe> calorieTargetOrder(double targetCalories) {
        return new Comparator<Recipe>() {
            @Override
            public int compare(Recipe firstRecipe, Recipe secondRecipe) {
                double firstDistance = calorieDistance(firstRecipe, targetCalories);
                double secondDistance = calorieDistance(secondRecipe, targetCalories);

                // Sort by distance to target calories (ascending)
                if (firstDistance < secondDistance) {
                    return -1;
                }
                if (firstDistance > secondDistance) {
                    return 1;
                }

                // If distances are equal, sort by recipe ID (ascending)
                return Integer.compare(firstRecipe.getRecipeId(), secondRecipe.getRecipeId());
            }
        };
    }

    private double calorieDistance(Recipe recipe, double targetCalories) {
        double calories = recipe.getCalories();
        if (!Double.isFinite(calories) || calories < 0) {
            // Valid calories and targets are finite and nonnegative, so their distance is finite.
            // Infinity puts all invalid calories last; the comparator then uses the recipe ID.
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(calories - targetCalories);
    }


    // Check that the list of recipes is not null
    private void checkRecipes(List<Recipe> recipes) {
        if (recipes == null) {
            throw new IllegalArgumentException("The recipe list must not be null.");
        }
    }

    // Check that the calorie bounds are finite and nonnegative
    private void checkCalorieBound(Double calories) {
        if (calories != null && (!Double.isFinite(calories) || calories < 0)) {
            throw new IllegalArgumentException("Calorie bounds must be finite and nonnegative.");
        }
    }


    // standardize ingredient names to lowercase and trim whitespace; null becomes empty string
    private String normalizeName(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }


    // standardize user ingredient names to lowercase and trim whitespace; null becomes empty string
    private Set<String> normalizeUserIngredients(List<String> userIngredients) {
        if (userIngredients == null || userIngredients.isEmpty()) {
            throw new IllegalArgumentException("A nonempty ingredient list is required.");
        }

        Set<String> names = new LinkedHashSet<>(); //return a set of unique ingredient names

        for (String ingredient : userIngredients) {
            String name = normalizeName(ingredient);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Ingredient names must not be null or blank.");
            }
            names.add(name);
        }
        return names;
    }


    // Match the ingredients of a recipe against the available ingredients and return a RecommendationResult.
    private RecommendationResult matchIngredients(Recipe recipe, Set<String> availableIngredients) {
        if (recipe == null || recipe.getIngredients() == null || recipe.getIngredients().isEmpty()) {
            return null;
        }

        Set<String> requiredIngredients = new LinkedHashSet<>();

        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            if (ingredient == null) {
                return null;
            }
            String name = normalizeName(ingredient.getIngredientName());
            if (name.isEmpty()) {
                return null;
            }
            requiredIngredients.add(name);
        }

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String name : requiredIngredients) {
            if (availableIngredients.contains(name)) {
                matched.add(name);
            } else {
                missing.add(name);
            }
        }
        if (matched.isEmpty()) {
            return null;
        }
        double matchRatio = (double) matched.size() / requiredIngredients.size();
        return new RecommendationResult(recipe, matched, missing, matchRatio);
    }
}
