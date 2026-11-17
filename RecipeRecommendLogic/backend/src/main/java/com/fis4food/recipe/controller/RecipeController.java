package com.fis4food.recipe.controller;

import com.fis4food.recipe.model.Recipe;
import com.fis4food.recipe.service.RecipeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fis4food.recipe.dto.RecipePage;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.MultiValueMap;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService service;

    public RecipeController(RecipeService service) {
        this.service = service;
    }

    @GetMapping
    public RecipePage list(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "minCalories", required = false) Double minCalories,
            @RequestParam(name = "maxCalories", required = false) Double maxCalories,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam MultiValueMap<String, String> parameters) {
        // Keep repeated ingredient params separate. A name can contain a comma.
        return service.list(
                limit, offset, minCalories, maxCalories, keyword, category,
                parameters.get("ingredients"), parameters.get("excludedIngredients")
        );
    }

    @GetMapping("/{recipeId}")
    public Recipe getById(@PathVariable("recipeId") int recipeId) {
        return service.getById(recipeId);
    }
}
