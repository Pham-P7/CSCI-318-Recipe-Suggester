package com.fis4food.recipe.service;

import com.fis4food.recipe.model.Recipe;
import com.fis4food.recipe.model.RecipeIngredient;
import com.fis4food.recipe.repository.RecipeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.fis4food.recipe.dto.RecipePage;
import com.fis4food.recipe.dto.RecipeSummary;
import com.fis4food.recipe.util.SearchText;
import java.util.LinkedHashSet;

import java.util.List;


@Service
public class RecipeService {

    private final RecipeRepository repository;

    public RecipeService(RecipeRepository repository) {
        this.repository = repository;
    }

    //for pagination, limit is the number of items to return, offset is the number of items to skip
    public RecipePage list(int limit, int offset, Double minCalories, Double maxCalories,
            String keyword, String category, List<String> ingredients, List<String> excludedIngredients) {

        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be between 1 and 100");
        }

        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Offset must be non-negative");
        }

        checkCalorieBound(minCalories, "minCalories");
        checkCalorieBound(maxCalories, "maxCalories");

        if (minCalories != null && maxCalories != null && minCalories > maxCalories) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minCalories must not exceed maxCalories");
        }

        // Normalize the search parameters.
        String normalizedKeyword = SearchText.normalize(keyword);
        String normalizedCategory = SearchText.normalize(category);
        List<String> selectedNames = normalizeIngredientNames(ingredients, "ingredients");
        List<String> excludedNames = normalizeIngredientNames(excludedIngredients, "excludedIngredients");

        // Pagination also uses parameters, so include it in the D1 limit.
        long parameterCount = 2L + selectedNames.size() + excludedNames.size();

        if (!selectedNames.isEmpty()) parameterCount++;
        if (minCalories != null || maxCalories != null) parameterCount += 2;
        if (normalizedKeyword != null && !normalizedKeyword.isEmpty()) parameterCount++;
        if (normalizedCategory != null && !normalizedCategory.isEmpty()) parameterCount++;

        if (parameterCount > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many filter values for one database query");
        }

        // One extra row tells us whether there is a next page.
        List<RecipeSummary> matches = repository.findPage(limit + 1, offset, minCalories, maxCalories,
                normalizedKeyword, normalizedCategory, selectedNames, excludedNames);

        //check if there are more recipes after that
        boolean hasMore = matches.size() > limit;

        List<RecipeSummary> items = List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
        return new RecipePage(items, limit, offset, hasMore);
    }


    private List<String> normalizeIngredientNames(List<String> values, String parameterName) {

        if (values == null) {
            return List.of();
        }

        // Repeated names should count once, even with different case.
        var names = new LinkedHashSet<String>();

        for (String value : values) {
            String name = SearchText.normalize(value);

            if (name == null || name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, parameterName + " must not contain blank names");
            }

            names.add(name);
        }

        return List.copyOf(names);
    }


    public Recipe getById(int recipeId) {
        if (recipeId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipe ID must be positive"); //400
        }

        // No row means 404. A database failure is handled separately.
        Recipe recipe = repository.findById(recipeId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, recipeId + ": Recipe not found")
        );

        List<RecipeIngredient> ingredients = repository.findIngredientsByRecipeId(recipeId);

        return new Recipe(recipe.recipeId(), recipe.name(), recipe.description(), recipe.category(),
                recipe.calories(), recipe.servings(), recipe.imageUrl(), recipe.instructions(), ingredients);

    }


    private void checkCalorieBound(Double value, String name) {

        //check null first, then check if the value is finite and non-negative
        if (value != null && (!Double.isFinite(value) || value < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be finite and non-negative");
        }
    }
}
