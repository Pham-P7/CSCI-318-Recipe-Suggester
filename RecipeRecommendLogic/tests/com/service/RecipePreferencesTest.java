package com.service;

import com.model.Recipe;
import com.model.RecipeIngredient;
import com.model.RecommendationResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Run with Java assertions enabled. No test framework is required. */
public class RecipePreferencesTest {
    private static final RecommendationService SERVICE = new RecommendationService();

    public static void main(String[] args) {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) {
            throw new IllegalStateException("Run this check with -ea.");
        }
        excludesNormalizedNames();
        excludesAnySpecifiedName();
        matchesCompleteNamesWithoutIdsOrInference();
        skipsIncompleteIngredientsWhenRestricted();
        keepsIncompleteIngredientsWhenUnrestricted();
        rejectsInvalidExclusionsEvenWithoutCandidates();
        returnsNewFilteredListsInInputOrder();
        normalizesIndependentlyOfDefaultLocale();
        sortsByDistanceThenId();
        acceptsZeroAndDefaultCalories();
        putsInvalidCaloriesAfterAllValidCalories();
        rejectsInvalidTargetsEvenWithoutCandidates();
        sortsEmptyListsAndSkipsNullRecipes();
        usesStoredCaloriesWithoutDividingByServings();
        keepsMatchRatioAsTheFirstPriority();
        breaksRecommendationTiesByCaloriesThenId();
        preservesMatchingAndReportsWithATarget();
        preservesOriginalRecommendationsWithNullTarget();
        validatesRecommendationInputsWithATarget();
        filtersNameSearchBeforeSorting();
        filtersBeforeGeneratingRecommendations();
        leavesAllInputsAndModelFieldsUnchanged();
        System.out.println("PASS: 22 preference scenarios.");
    }

    private static void excludesNormalizedNames() {
        Recipe excluded = recipe(1, 100, "\u2003PeAnUt\u2003", "rice");
        Recipe retained = recipe(2, 100, "rice");
        assertIds(SERVICE.filterByExcludedIngredients(List.of(excluded, retained),
                List.of(" PEANUT ", "peanut", "\tPeAnUt\n")), 2);
    }

    private static void excludesAnySpecifiedName() {
        List<Recipe> recipes = List.of(recipe(9, 100, "rice"), recipe(2, 100, "milk"),
                recipe(7, 100, "peanut"), recipe(4, 100, "milk", "peanut"),
                recipe(3, 100, "onion", "rice"));
        assertIds(SERVICE.filterByExcludedIngredients(recipes, List.of("milk", "peanut")), 9, 3);
        assert SERVICE.filterByExcludedIngredients(recipes, List.of("rice", "milk", "peanut")).isEmpty();
    }

    private static void matchesCompleteNamesWithoutIdsOrInference() {
        Recipe sameId = recipe(9, 100, "peanut oil");
        Recipe differentId = recipe(2, 100, "peanut");
        differentId.getIngredients().get(0).setIngredientId(800);
        Recipe synonym = recipe(7, 100, "groundnut");
        assertIds(SERVICE.filterByExcludedIngredients(List.of(sameId, differentId, synonym),
                List.of("peanut")), 9, 7);
        assertIds(SERVICE.filterByExcludedIngredients(List.of(sameId, differentId, synonym),
                List.of("nut")), 9, 2, 7);
    }

    private static void skipsIncompleteIngredientsWhenRestricted() {
        Recipe missingList = recipe(1, 100, "rice");
        missingList.setIngredients(null);
        Recipe emptyList = recipe(2, 100);
        Recipe nullEntry = recipe(3, 100, "rice");
        nullEntry.getIngredients().add(null);
        Recipe nullName = recipe(4, 100, "rice", (String) null);
        Recipe blankName = recipe(5, 100, "rice", "\u2003\t");
        Recipe valid = recipe(6, 100, "rice", " RICE ");
        assertIds(SERVICE.filterByExcludedIngredients(
                Arrays.asList(null, missingList, emptyList, nullEntry, nullName, blankName, valid),
                List.of("peanut")), 6);
    }

    private static void keepsIncompleteIngredientsWhenUnrestricted() {
        Recipe missingList = recipe(9, 100);
        missingList.setIngredients(null);
        Recipe emptyList = recipe(2, 100);
        Recipe nullEntry = recipe(7, 100);
        nullEntry.getIngredients().add(null);
        Recipe invalidNames = recipe(3, 100, null, " ");
        List<Recipe> recipes = Arrays.asList(null, missingList, emptyList, nullEntry, invalidNames);
        assertIds(SERVICE.filterByExcludedIngredients(recipes, null), 9, 2, 7, 3);
        assertIds(SERVICE.filterByExcludedIngredients(recipes, List.of()), 9, 2, 7, 3);
    }

    private static void rejectsInvalidExclusionsEvenWithoutCandidates() {
        expectInvalid(() -> SERVICE.filterByExcludedIngredients(null, null));
        expectInvalid(() -> SERVICE.filterByExcludedIngredients(null, List.of()));
        expectInvalid(() -> SERVICE.filterByExcludedIngredients(null, List.of("milk")));
        List<List<String>> invalidLists = List.of(Arrays.asList((String) null), List.of(""),
                List.of("\u2003\t\n"), Arrays.asList("milk", null), List.of("milk", " "));
        for (List<String> invalid : invalidLists) {
            expectInvalid(() -> SERVICE.filterByExcludedIngredients(List.of(), invalid));
            expectInvalid(() -> SERVICE.filterByExcludedIngredients(List.of(recipe(1, 100, "milk")), invalid));
        }
    }

    private static void returnsNewFilteredListsInInputOrder() {
        List<Recipe> recipes = new ArrayList<>(List.of(recipe(9, 100, "rice"), recipe(2, 100, "rice")));
        List<Recipe> filtered = SERVICE.filterByExcludedIngredients(recipes, List.of("milk"));
        assertIds(filtered, 9, 2);
        assert filtered != recipes;
        filtered.clear();
        assertIds(recipes, 9, 2);
        List<Recipe> unrestricted = SERVICE.filterByExcludedIngredients(recipes, null);
        assert unrestricted != recipes;
        unrestricted.clear();
        assertIds(recipes, 9, 2);
        List<Recipe> empty = new ArrayList<>();
        assert SERVICE.filterByExcludedIngredients(empty, List.of()) != empty;
        assert SERVICE.filterByExcludedIngredients(empty, List.of("milk")) != empty;
    }

    private static void normalizesIndependentlyOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertIds(SERVICE.filterByExcludedIngredients(
                    List.of(recipe(1, 100, "RICE"), recipe(2, 100, "milk")), List.of("rice")), 2);
        } finally {
            Locale.setDefault(original);
        }
    }

    private static void sortsByDistanceThenId() {
        List<Recipe> recipes = List.of(recipe(1, 600), recipe(9, 490), recipe(2, 510),
                recipe(4, 450), recipe(8, 500));
        assertIds(SERVICE.sortByCalorieTarget(recipes, 500), 8, 2, 9, 4, 1);
    }

    private static void acceptsZeroAndDefaultCalories() {
        Recipe defaultCalories = new Recipe();
        defaultCalories.setRecipeId(9);
        List<Recipe> recipes = List.of(recipe(1, 10, "rice"), defaultCalories, recipe(2, 0, "rice"));
        assertIds(SERVICE.sortByCalorieTarget(recipes, 0), 2, 9, 1);
        assertRecommendationIds(SERVICE.getRecommendations(List.of("rice"),
                List.of(recipe(1, 10, "rice"), recipe(2, 0, "rice")), 0.0), 2, 1);
    }

    private static void putsInvalidCaloriesAfterAllValidCalories() {
        List<Recipe> recipes = Arrays.asList(recipe(4, Double.NaN), recipe(2, Double.POSITIVE_INFINITY),
                recipe(3, -1), recipe(1, Double.NEGATIVE_INFINITY), recipe(9, Double.MAX_VALUE),
                recipe(7, 0), null);
        assertIds(SERVICE.sortByCalorieTarget(recipes, 0), 7, 9, 1, 2, 3, 4);
        assertIds(SERVICE.sortByCalorieTarget(recipes, Double.MAX_VALUE), 9, 7, 1, 2, 3, 4);
    }

    private static void rejectsInvalidTargetsEvenWithoutCandidates() {
        for (double invalid : new double[]{-1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            expectInvalid(() -> SERVICE.sortByCalorieTarget(List.of(), invalid));
            expectInvalid(() -> SERVICE.sortByCalorieTarget(List.of(recipe(1, 100)), invalid));
            expectInvalid(() -> SERVICE.getRecommendations(List.of("rice"), List.of(), invalid));
            expectInvalid(() -> SERVICE.getRecommendations(List.of("rice"),
                    List.of(recipe(1, 100, "rice")), invalid));
        }
        expectInvalid(() -> SERVICE.sortByCalorieTarget(null, 0));
    }

    private static void sortsEmptyListsAndSkipsNullRecipes() {
        List<Recipe> empty = new ArrayList<>();
        List<Recipe> sorted = SERVICE.sortByCalorieTarget(empty, 0);
        assert sorted.isEmpty();
        assert sorted != empty;
        assert SERVICE.sortByCalorieTarget(Arrays.asList((Recipe) null), 100).isEmpty();
        assertIds(SERVICE.sortByCalorieTarget(Arrays.asList(null, recipe(2, 100), null), 100), 2);
    }

    private static void usesStoredCaloriesWithoutDividingByServings() {
        Recipe highCalories = recipe(1, 1000, "rice");
        highCalories.setServings(4);
        Recipe nearTarget = recipe(9, 300, "rice");
        List<Recipe> recipes = List.of(highCalories, nearTarget);
        assertIds(SERVICE.sortByCalorieTarget(recipes, 250), 9, 1);
        assertRecommendationIds(SERVICE.getRecommendations(List.of("rice"), recipes, 250.0), 9, 1);
    }

    private static void keepsMatchRatioAsTheFirstPriority() {
        List<Recipe> recipes = List.of(recipe(1, 500, "rice", "onion"),
                recipe(9, Double.NaN, "rice"), recipe(7, 2000, "rice"));
        List<RecommendationResult> results = SERVICE.getRecommendations(List.of("rice"), recipes, 500.0);
        assertRecommendationIds(results, 7, 9, 1);
        assert results.get(0).getMatchRatio() == 1.0;
        assert results.get(1).getMatchRatio() == 1.0;
        assert results.get(2).getMatchRatio() == 0.5;
    }

    private static void breaksRecommendationTiesByCaloriesThenId() {
        List<Recipe> recipes = List.of(recipe(1, Double.NaN, "rice"), recipe(9, 490, "rice"),
                recipe(2, 510, "rice"), recipe(8, 500, "rice"), recipe(4, 700, "rice"),
                recipe(3, -1, "rice"), recipe(5, Double.POSITIVE_INFINITY, "rice"),
                recipe(6, Double.NEGATIVE_INFINITY, "rice"));
        assertRecommendationIds(SERVICE.getRecommendations(List.of("rice"), recipes, 500.0),
                8, 2, 9, 4, 1, 3, 5, 6);
        assertRecommendationIds(SERVICE.getRecommendations(List.of("rice"),
                List.of(recipe(1, Double.NaN, "rice"), recipe(9, Double.MAX_VALUE, "rice")), 0.0), 9, 1);
    }

    private static void preservesMatchingAndReportsWithATarget() {
        Recipe partial = recipe(8, 300, " RICE ", "rice", "Onion", "Salt");
        Recipe missingList = recipe(2, 300, "rice");
        missingList.setIngredients(null);
        Recipe nullEntry = recipe(3, 300, "rice");
        nullEntry.getIngredients().add(null);
        List<RecommendationResult> results = SERVICE.getRecommendations(
                List.of(" RICE ", "rice", " ONION "),
                Arrays.asList(null, missingList, nullEntry, recipe(4, 300, "rice", " "),
                        recipe(5, 300), recipe(6, 300, "rice", (String) null),
                        recipe(7, 300, "milk"), partial), 300.0);
        assertRecommendationIds(results, 8);
        assert results.get(0).getRecipe() == partial;
        assert results.get(0).getMatchedIngredients().equals(List.of("rice", "onion"));
        assert results.get(0).getMissingIngredients().equals(List.of("salt"));
        assert Math.abs(results.get(0).getMatchRatio() - 2.0 / 3.0) < 1e-9;
    }

    private static void preservesOriginalRecommendationsWithNullTarget() {
        List<Recipe> recipes = Arrays.asList(recipe(9, 500, "rice"), recipe(2, Double.NaN, "rice"),
                recipe(1, 100, "rice", "salt"), null, recipe(3, 500, "milk"));
        List<String> pantry = List.of(" RICE ", "rice");
        List<RecommendationResult> original = SERVICE.getRecommendations(pantry, recipes);
        List<RecommendationResult> withNull = SERVICE.getRecommendations(pantry, recipes, null);
        assertRecommendationIds(withNull, 2, 9, 1);
        assert withNull != original;
        for (int i = 0; i < original.size(); i++) {
            assert withNull.get(i).getRecipe() == original.get(i).getRecipe();
            assert withNull.get(i).getMatchRatio() == original.get(i).getMatchRatio();
            assert withNull.get(i).getMatchedIngredients().equals(original.get(i).getMatchedIngredients());
            assert withNull.get(i).getMissingIngredients().equals(original.get(i).getMissingIngredients());
        }
    }

    private static void validatesRecommendationInputsWithATarget() {
        for (Double target : Arrays.asList(null, 100.0)) {
            expectInvalid(() -> SERVICE.getRecommendations(null, List.of(), target));
            expectInvalid(() -> SERVICE.getRecommendations(List.of(), List.of(), target));
            expectInvalid(() -> SERVICE.getRecommendations(List.of(" "), List.of(), target));
            expectInvalid(() -> SERVICE.getRecommendations(Arrays.asList("rice", null), List.of(), target));
            expectInvalid(() -> SERVICE.getRecommendations(List.of("rice"), null, target));
            assert SERVICE.getRecommendations(List.of("rice"), List.of(), target).isEmpty();
            assert SERVICE.getRecommendations(List.of("rice"), List.of(recipe(1, 100, "milk")), target).isEmpty();
        }
    }

    private static void filtersNameSearchBeforeSorting() {
        Recipe milk = recipe(2, 500, "milk");
        Recipe far = recipe(1, 700, "rice");
        Recipe close = recipe(9, 510, "rice");
        Recipe incomplete = recipe(3, 500);
        List<Recipe> searched = SERVICE.searchByName("sample", List.of(close, milk, far, incomplete));
        assertIds(searched, 1, 2, 3, 9);
        List<Recipe> filtered = SERVICE.filterByExcludedIngredients(searched, List.of("milk"));
        assertIds(filtered, 1, 9);
        assertIds(SERVICE.sortByCalorieTarget(filtered, 500), 9, 1);
        assertIds(searched, 1, 2, 3, 9);
        assertIds(filtered, 1, 9);
    }

    private static void filtersBeforeGeneratingRecommendations() {
        List<Recipe> recipes = List.of(recipe(1, 500, "rice", "milk"),
                recipe(2, 500, "rice", "salt"), recipe(9, 510, "rice"), recipe(7, 800, "rice"));
        List<Recipe> filtered = SERVICE.filterByExcludedIngredients(recipes, List.of("milk"));
        assertIds(filtered, 2, 9, 7);
        List<RecommendationResult> results = SERVICE.getRecommendations(List.of("rice"), filtered, 500.0);
        assertRecommendationIds(results, 9, 7, 2);
        assert results.get(2).getMissingIngredients().equals(List.of("salt"));
    }

    private static void leavesAllInputsAndModelFieldsUnchanged() {
        Recipe high = recipe(9, 900, " RICE ", "Salt");
        Recipe low = recipe(2, 100, "rice");
        high.setDescription("description");
        high.setImageUrl("image");
        high.setCategory(" Main ");
        high.setKeywords("keywords");
        high.setProtein(10);
        high.setFat(20);
        high.setCarbohydrates(30);
        high.setPrepTime("prep");
        high.setCookTime("cook");
        high.setServings(4);
        high.setInstructions("instructions");
        List<Recipe> recipes = new ArrayList<>(List.of(high, low));
        List<String> excluded = new ArrayList<>(List.of(" MILK ", "milk"));
        List<String> pantry = new ArrayList<>(List.of(" RICE ", "rice"));
        List<RecipeIngredient> ingredients = high.getIngredients();
        List<RecipeIngredient> ingredientEntries = new ArrayList<>(ingredients);
        List<Recipe> filtered = SERVICE.filterByExcludedIngredients(recipes, excluded);
        List<Recipe> sorted = SERVICE.sortByCalorieTarget(recipes, 100);
        List<RecommendationResult> results = SERVICE.getRecommendations(pantry, recipes, 100.0);
        List<RecommendationResult> withoutTarget = SERVICE.getRecommendations(pantry, recipes, null);
        assertIds(filtered, 9, 2);
        assertIds(sorted, 2, 9);
        assertRecommendationIds(results, 2, 9);
        assert results.get(1).getRecipe() == high;
        assert filtered != recipes && sorted != recipes;
        assert (Object) results != recipes && (Object) withoutTarget != recipes;
        filtered.clear();
        sorted.clear();
        results.clear();
        withoutTarget.clear();
        assertIds(recipes, 9, 2);
        assert recipes.get(0) == high && recipes.get(1) == low;
        assert pantry.equals(List.of(" RICE ", "rice"));
        assert excluded.equals(List.of(" MILK ", "milk"));
        assert high.getRecipeId() == 9 && high.getName().equals("Sample 9");
        assert high.getDescription().equals("description") && high.getImageUrl().equals("image");
        assert high.getCategory().equals(" Main ") && high.getKeywords().equals("keywords");
        assert high.getCalories() == 900 && low.getCalories() == 100;
        assert high.getProtein() == 10 && high.getFat() == 20 && high.getCarbohydrates() == 30;
        assert high.getPrepTime().equals("prep") && high.getCookTime().equals("cook");
        assert high.getServings() == 4 && high.getInstructions().equals("instructions");
        assert high.getIngredients() == ingredients && ingredients.equals(ingredientEntries);
        assert ingredients.get(0).getIngredientName().equals(" RICE ");
        assert ingredients.get(1).getIngredientName().equals("Salt");
        for (int i = 0; i < ingredients.size(); i++) {
            assert ingredients.get(i).getIngredientId() == i + 1;
            assert ingredients.get(i).getQuantity().equals("1");
        }
    }

    private static Recipe recipe(int id, double calories, String... names) {
        Recipe recipe = new Recipe();
        recipe.setRecipeId(id);
        recipe.setName("Sample " + id);
        recipe.setCalories(calories);
        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            ingredients.add(new RecipeIngredient(i + 1, names[i], "1"));
        }
        recipe.setIngredients(ingredients);
        return recipe;
    }

    private static void assertIds(List<Recipe> recipes, int... expectedIds) {
        assert recipes.size() == expectedIds.length : "Unexpected number of recipes: " + recipes.size();
        for (int i = 0; i < expectedIds.length; i++) {
            assert recipes.get(i).getRecipeId() == expectedIds[i] : "Wrong recipe order at " + i;
        }
    }

    private static void assertRecommendationIds(List<RecommendationResult> results, int... expectedIds) {
        List<Recipe> recipes = new ArrayList<>();
        for (RecommendationResult result : results) {
            recipes.add(result.getRecipe());
        }
        assertIds(recipes, expectedIds);
    }

    private static void expectInvalid(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid input should raise IllegalArgumentException");
    }
}
