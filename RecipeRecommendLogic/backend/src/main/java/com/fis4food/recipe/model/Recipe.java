package com.fis4food.recipe.model;

import java.util.List;

public record Recipe(
        int recipeId,
        String name,
        String description,
        String category,
        Double calories,
        Integer servings,
        String imageUrl,
        List<String> instructions,
        List<RecipeIngredient> ingredients
) {
}
