package com.service;

import com.model.Recipe;
import com.model.RecipeIngredient;
import com.model.RecommendationResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Run with Java assertions enabled: java -ea com.service.RecommendationServiceTest. */
public class RecommendationServiceTest {
    private static final RecommendationService SERVICE = new RecommendationService();

    public static void main(String[] args) {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) {
            throw new IllegalStateException("Run this check with -ea.");
        }
        reportsMatchedAndMissingIngredients();
        normalizesAndDeduplicatesIngredientNames();
        ranksByCoverageThenRecipeId();
        excludesUnmatchedAndIncompleteRecipes();
        rejectsInvalidRequests();
        leavesInputCollectionsUnchanged();
        returnsEmptyWhenNothingMatches();
        System.out.println("PASS: 7 recommendation scenarios.");
    }

    private static void reportsMatchedAndMissingIngredients() {
        Recipe dessert = recipe(38, "blueberries", "granulated sugar", "vanilla yogurt");
        List<RecommendationResult> results = SERVICE.getRecommendations(
                List.of("blueberries", "vanilla yogurt"), List.of(dessert));
        assert results.size() == 1 : "A partially matched recipe should be recommended";
        RecommendationResult result = results.get(0);
        assert result.getRecipe() == dessert;
        assert result.getMatchedIngredients().equals(List.of("blueberries", "vanilla yogurt"));
        assert result.getMissingIngredients().equals(List.of("granulated sugar"));
        assert Math.abs(result.getMatchRatio() - 2.0 / 3.0) < 1e-9;
    }

    private static void normalizesAndDeduplicatesIngredientNames() {
        List<RecommendationResult> results = SERVICE.getRecommendations(
                List.of(" BLUEBERRIES ", "blueberries", " VANILLA YOGURT "),
                List.of(recipe(38, "Blueberries", " blueberries ", "Vanilla Yogurt", "Sugar")));
        assert results.size() == 1;
        RecommendationResult result = results.get(0);
        assert result.getMatchedIngredients().equals(List.of("blueberries", "vanilla yogurt"));
        assert result.getMissingIngredients().equals(List.of("sugar"));
        assert Math.abs(result.getMatchRatio() - 2.0 / 3.0) < 1e-9
                : "Duplicate recipe ingredients must not inflate coverage";
    }

    private static void ranksByCoverageThenRecipeId() {
        List<RecommendationResult> results = SERVICE.getRecommendations(
                List.of("cabbage"),
                List.of(recipe(9, "cabbage", "onion", "carrot"),
                        recipe(7, "cabbage"), recipe(2, "cabbage")));
        assert results.size() == 3;
        assert results.get(0).getRecipe().getRecipeId() == 2;
        assert results.get(1).getRecipe().getRecipeId() == 7;
        assert results.get(2).getRecipe().getRecipeId() == 9;
        assert results.get(0).getMatchRatio() == 1.0;
    }

    private static void excludesUnmatchedAndIncompleteRecipes() {
        Recipe missingList = recipe(3, "cabbage");
        missingList.setIngredients(null);
        Recipe invalidEntry = recipe(4, "cabbage");
        invalidEntry.getIngredients().add(null);
        Recipe blankName = recipe(5, "cabbage", " ");
        List<RecommendationResult> results = SERVICE.getRecommendations(
                List.of("cabbage"), Arrays.asList(null, recipe(1, "blueberries"),
                        recipe(2), missingList, invalidEntry, blankName, recipe(6, "cabbage")));
        assert results.size() == 1 : "Incomplete ingredient data must not appear fully matched";
        assert results.get(0).getRecipe().getRecipeId() == 6;
    }

    private static void rejectsInvalidRequests() {
        expectInvalid(() -> SERVICE.getRecommendations(null, List.of()));
        expectInvalid(() -> SERVICE.getRecommendations(List.of(), List.of()));
        expectInvalid(() -> SERVICE.getRecommendations(List.of(" "), List.of()));
        expectInvalid(() -> SERVICE.getRecommendations(Arrays.asList("cabbage", null), List.of()));
        expectInvalid(() -> SERVICE.getRecommendations(List.of("cabbage"), null));
    }

    private static void leavesInputCollectionsUnchanged() {
        Recipe soup = recipe(42, " Cabbage ", "Onion");
        List<Recipe> recipes = new ArrayList<>(List.of(soup));
        List<String> pantry = new ArrayList<>(List.of(" CABBAGE ", "cabbage"));
        SERVICE.getRecommendations(pantry, recipes);
        assert pantry.equals(List.of(" CABBAGE ", "cabbage"));
        assert recipes.equals(List.of(soup));
        assert soup.getIngredients().get(0).getIngredientName().equals(" Cabbage ");
    }

    private static void returnsEmptyWhenNothingMatches() {
        assert SERVICE.getRecommendations(List.of("cabbage"), List.of()).isEmpty();
        assert SERVICE.getRecommendations(List.of("cabbage"),
                List.of(recipe(38, "blueberries"))).isEmpty();
    }

    private static Recipe recipe(int id, String... names) {
        Recipe recipe = new Recipe();
        recipe.setRecipeId(id);
        recipe.setName("Sample " + id);
        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            ingredients.add(new RecipeIngredient(i + 1, names[i], "1"));
        }
        recipe.setIngredients(ingredients);
        return recipe;
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
