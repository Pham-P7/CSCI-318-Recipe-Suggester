package com.fis4food.recipe.model;

public record RecipeIngredient(
        int position,
        String ingredientName,
        String quantity,
        String pairStatus
) {
}
