package com.fis4food.recipe.dto;

public record RecipeSummary(
        int recipeId,
        String name,
        String category,
        Double calories,
        Integer servings,
        String imageUrl
) {
}
