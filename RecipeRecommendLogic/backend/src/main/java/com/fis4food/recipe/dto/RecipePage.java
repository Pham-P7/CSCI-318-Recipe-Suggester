package com.fis4food.recipe.dto;

import java.util.List;

public record RecipePage(
        List<RecipeSummary> items,
        int limit,
        int offset,
        boolean hasMore //tells if there are more items to fetch after this page
) {
}
