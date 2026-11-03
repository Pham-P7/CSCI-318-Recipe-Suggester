package com.model;

import java.util.List;


public class RecommendationResult {
    private final Recipe recipe;
    private final List<String> matchedIngredients;
    private final List<String> missingIngredients;
    private final double matchRatio;

    public RecommendationResult(Recipe recipe, List<String> matchedIngredients,
                                List<String> missingIngredients, double matchRatio) {
        this.recipe = recipe;
        this.matchedIngredients = List.copyOf(matchedIngredients);
        this.missingIngredients = List.copyOf(missingIngredients);
        this.matchRatio = matchRatio;
    }

    public Recipe getRecipe() {
        return recipe;
    }

    public List<String> getMatchedIngredients() {
        return matchedIngredients;
    }

    public List<String> getMissingIngredients() {
        return missingIngredients;
    }

    public double getMatchRatio() {
        return matchRatio;
    }
}
