package com.service;

import com.model.Recipe;
import com.model.RecipeIngredient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Run with Java assertions enabled. No test framework is required. */
public class RecipeSearchTest {
    private static final RecommendationService SERVICE = new RecommendationService();

    public static void main(String[] args) {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) {
            throw new IllegalStateException("Run this check with -ea.");
        }
        searchesNamesOnlyInIdOrder();
        rejectsInvalidNameRequests();
        searchesCaloriesWithoutIngredients();
        includesCalorieBoundariesAndZero();
        supportsOneSidedCalorieRanges();
        rejectsInvalidCalorieRanges();
        matchesCompleteCategories();
        searchesAndRanksIngredients();
        combinesConditionsWithAnd();
        checksDetailsBeforeIngredients();
        distinguishesAbsentAndInvalidIngredients();
        rejectsMissingConditionsAndRecipeLists();
        returnsEmptyForValidRequestsWithoutMatches();
        handlesMissingRecipeData();
        leavesSearchInputsUnchanged();
        normalizesIndependentlyOfDefaultLocale();
        System.out.println("PASS: 16 search scenarios.");
    }

    private static void searchesNamesOnlyInIdOrder() {
        Recipe soup = recipe(9, "Chicken Soup", 300, "Soup", "chicken");
        Recipe roast = recipe(2, "Roast CHICKEN", 500, "Main", "chicken");
        Recipe keywordOnly = recipe(1, "Vegetable Soup", 200, "Soup", "carrot");
        keywordOnly.setKeywords("chicken");
        List<Recipe> recipes = List.of(soup, keywordOnly, roast);
        assertIds(SERVICE.searchByName("  cHiCkEn  ", recipes), 2, 9);
        assertIds(SERVICE.searchByName("Soup", recipes), 1, 9);
    }

    private static void rejectsInvalidNameRequests() {
        expectInvalid(() -> SERVICE.searchByName(null, List.of()));
        expectInvalid(() -> SERVICE.searchByName("", List.of()));
        expectInvalid(() -> SERVICE.searchByName(" \t\n", List.of()));
        expectInvalid(() -> SERVICE.searchByName("chicken", null));
    }

    private static void searchesCaloriesWithoutIngredients() {
        Recipe soup = recipe(8, "Soup", 200, null);
        soup.setIngredients(null);
        soup.setServings(4);
        assertIds(SERVICE.searchByDetails(200.0, 400.0, null, null,
                List.of(recipe(9, "Salad", 300, "Side"), soup,
                        recipe(1, "Cake", 800, "Dessert"))), 8, 9);
        assert SERVICE.searchByDetails(null, 100.0, null, null, List.of(soup)).isEmpty()
                : "Calories must not be divided by servings";
    }

    private static void includesCalorieBoundariesAndZero() {
        List<Recipe> recipes = List.of(recipe(5, "High", 300, "Main"),
                recipe(2, "Low", 100, "Main"), recipe(1, "Zero", 0, "Drink"));
        assertIds(SERVICE.searchByDetails(100.0, 300.0, null, null, recipes), 2, 5);
        assertIds(SERVICE.searchByDetails(100.0, 100.0, null, null, recipes), 2);
        assertIds(SERVICE.searchByDetails(0.0, 0.0, null, null, recipes), 1);
    }

    private static void supportsOneSidedCalorieRanges() {
        List<Recipe> recipes = List.of(recipe(3, "High", 300, "Main"),
                recipe(1, "Low", 100, "Main"), recipe(2, "Middle", 200, "Main"));
        assertIds(SERVICE.searchByDetails(200.0, null, null, null, recipes), 2, 3);
        assertIds(SERVICE.searchByDetails(null, 200.0, null, null, recipes), 1, 2);
    }

    private static void rejectsInvalidCalorieRanges() {
        for (double invalid : new double[]{-1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            expectInvalid(() -> SERVICE.searchByDetails(invalid, null, null, null, List.of()));
            expectInvalid(() -> SERVICE.searchByDetails(null, invalid, null, null, List.of()));
        }
        expectInvalid(() -> SERVICE.searchByDetails(300.0, 100.0, null, null, List.of()));
    }

    private static void matchesCompleteCategories() {
        Recipe soup = recipe(8, "Soup", 200, " Soup ");
        soup.setIngredients(null);
        assertIds(SERVICE.searchByDetails(null, null, " sOuP ", null,
                List.of(soup, recipe(2, "Soup Bread", 300, "Soup Bread"),
                        recipe(1, "Soup", 400, "Main"))), 8);
        assert SERVICE.searchByDetails(null, null, "sou", null, List.of(soup)).isEmpty();
    }

    private static void searchesAndRanksIngredients() {
        Recipe partial = recipe(1, "Partial", 100, "Main", "Chicken", "Salt", "Pepper");
        Recipe fullHighId = recipe(9, "Full", 100, "Main", "Chicken");
        Recipe fullLowId = recipe(2, "Full", 100, "Main", "Chicken", " chicken ");
        Recipe none = recipe(3, "None", 100, "Main", "carrot");
        assertIds(SERVICE.searchByDetails(null, null, null,
                List.of(" CHICKEN ", "chicken"), List.of(partial, fullHighId, none, fullLowId)),
                2, 9, 1);
    }

    private static void combinesConditionsWithAnd() {
        List<Recipe> recipes = List.of(recipe(1, "Match", 200, "Soup", "chicken", "salt"),
                recipe(2, "Too high", 500, "Soup", "chicken"),
                recipe(3, "Wrong category", 200, "Main", "chicken"),
                recipe(4, "No ingredients match", 200, "Soup", "carrot"),
                recipe(5, "Too low", 50, "Soup", "chicken"));
        assertIds(SERVICE.searchByDetails(100.0, 300.0, " soup ", List.of("chicken"), recipes), 1);
        assertIds(SERVICE.searchByDetails(100.0, 300.0, "soup", null, recipes), 1, 4);
        assertIds(SERVICE.searchByDetails(100.0, null, null, List.of("chicken"), recipes), 2, 3, 1);
    }

    private static void checksDetailsBeforeIngredients() {
        Recipe excluded = new Recipe() {
            @Override
            public List<RecipeIngredient> getIngredients() {
                throw new AssertionError("Rejected recipes must not have their ingredients read");
            }
        };
        excluded.setCalories(500);
        excluded.setCategory("Main");
        assert SERVICE.searchByDetails(null, 300.0, null, List.of("chicken"), List.of(excluded)).isEmpty();
        assert SERVICE.searchByDetails(null, null, "Soup", List.of("chicken"), List.of(excluded)).isEmpty();
    }

    private static void distinguishesAbsentAndInvalidIngredients() {
        List<Recipe> recipes = List.of(recipe(1, "Soup", 200, "Soup"));
        assertIds(SERVICE.searchByDetails(null, null, "Soup", null, recipes), 1);
        expectInvalid(() -> SERVICE.searchByDetails(null, null, "Soup", List.of(), recipes));
        expectInvalid(() -> SERVICE.searchByDetails(null, null, "Soup", List.of(" "), recipes));
        expectInvalid(() -> SERVICE.searchByDetails(null, null, "Soup", Arrays.asList((String) null), recipes));
        expectInvalid(() -> SERVICE.searchByDetails(null, null, "Soup", Arrays.asList("chicken", null), recipes));
        expectInvalid(() -> SERVICE.searchByDetails(null, null, "Soup", List.of("chicken", "\t"), recipes));
    }

    private static void rejectsMissingConditionsAndRecipeLists() {
        expectInvalid(() -> SERVICE.searchByDetails(null, null, null, null, List.of()));
        expectInvalid(() -> SERVICE.searchByDetails(null, null, " \t", null, List.of()));
        expectInvalid(() -> SERVICE.searchByDetails(0.0, null, null, null, null));
        expectInvalid(() -> SERVICE.searchByDetails(null, null, "Soup", List.of("chicken"), null));
    }

    private static void returnsEmptyForValidRequestsWithoutMatches() {
        assert SERVICE.searchByName("chicken", List.of()).isEmpty();
        assert SERVICE.searchByName("chicken", List.of(recipe(1, "Salad", 100, "Side"))).isEmpty();
        assert SERVICE.searchByDetails(0.0, null, null, null, List.of()).isEmpty();
        assert SERVICE.searchByDetails(500.0, null, null, null,
                List.of(recipe(1, "Salad", 100, "Side"))).isEmpty();
        assert SERVICE.searchByDetails(null, null, null, List.of("chicken"),
                List.of(recipe(1, "Salad", 100, "Side", "carrot"))).isEmpty();
    }

    private static void handlesMissingRecipeData() {
        Recipe noName = recipe(1, null, 100, null);
        Recipe noIngredients = recipe(2, "Soup", 100, "Soup");
        noIngredients.setIngredients(null);
        Recipe nullEntry = recipe(3, "Soup", 100, "Soup", "chicken");
        nullEntry.getIngredients().add(null);
        Recipe nullName = recipe(4, "Soup", 100, "Soup", "chicken", (String) null);
        Recipe blankName = recipe(5, "Soup", 100, "Soup", "chicken", " ");
        Recipe valid = recipe(6, "Soup", 100, "Soup", "chicken");
        List<Recipe> recipes = Arrays.asList(null, noName, noIngredients, nullEntry, nullName, blankName, valid);
        assertIds(SERVICE.searchByName("Soup", recipes), 2, 3, 4, 5, 6);
        assertIds(SERVICE.searchByDetails(null, null, "Soup", null, recipes), 2, 3, 4, 5, 6);
        assertIds(SERVICE.searchByDetails(null, null, null, List.of("chicken"), recipes), 6);

        List<Recipe> invalidCalories = List.of(recipe(1, "NaN", Double.NaN, "Main"),
                recipe(2, "Infinity", Double.POSITIVE_INFINITY, "Main"),
                recipe(3, "Negative", -1, "Main"),
                recipe(4, "Negative infinity", Double.NEGATIVE_INFINITY, "Main"));
        assert SERVICE.searchByDetails(0.0, null, null, null, invalidCalories).isEmpty();
        assert SERVICE.searchByDetails(null, 100.0, null, null, invalidCalories).isEmpty();
        assertIds(SERVICE.searchByDetails(null, null, "Main", null, invalidCalories), 1, 2, 3, 4);
        // The current primitive double model cannot distinguish unset calories from a real zero.
        assertIds(SERVICE.searchByDetails(0.0, 0.0, null, null, List.of(new Recipe())), 0);
    }

    private static void leavesSearchInputsUnchanged() {
        Recipe highId = recipe(9, " Chicken Soup ", 200, " Soup ", " Chicken ", "Salt");
        Recipe lowId = recipe(2, "Chicken", 100, "Soup", "chicken");
        List<Recipe> recipes = new ArrayList<>(List.of(highId, lowId));
        List<String> pantry = new ArrayList<>(List.of(" CHICKEN ", "chicken"));
        List<RecipeIngredient> originalIngredients = highId.getIngredients();
        RecipeIngredient originalIngredient = originalIngredients.get(0);
        SERVICE.searchByName("chicken", recipes);
        SERVICE.searchByDetails(0.0, null, "Soup", null, recipes);
        SERVICE.searchByDetails(0.0, null, "Soup", pantry, recipes);
        assert recipes.equals(List.of(highId, lowId));
        assert pantry.equals(List.of(" CHICKEN ", "chicken"));
        assert highId.getName().equals(" Chicken Soup ");
        assert highId.getCategory().equals(" Soup ");
        assert highId.getCalories() == 200;
        assert highId.getIngredients() == originalIngredients;
        assert originalIngredients.size() == 2;
        assert originalIngredients.get(0) == originalIngredient;
        assert originalIngredient.getIngredientName().equals(" Chicken ");
        assert originalIngredient.getIngredientId() == 1;
        assert originalIngredient.getQuantity().equals("1");
    }

    private static void normalizesIndependentlyOfDefaultLocale() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Recipe rice = recipe(1, "RICE", 100, "SIDE", "RICE");
            assertIds(SERVICE.searchByName("rice", List.of(rice)), 1);
            assertIds(SERVICE.searchByDetails(null, null, "side", List.of("rice"), List.of(rice)), 1);
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    private static Recipe recipe(int id, String name, double calories, String category, String... names) {
        Recipe recipe = new Recipe();
        recipe.setRecipeId(id);
        recipe.setName(name);
        recipe.setCalories(calories);
        recipe.setCategory(category);
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

    private static void expectInvalid(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid input should raise IllegalArgumentException");
    }
}
