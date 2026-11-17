package com.fis4food.recipe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.support.D1TestDatabase;
import com.fis4food.recipe.repository.RecipeRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;

public class RecipeServiceTest {
    public static void main(String[] args) {
        if (!RecipeServiceTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea.");
        }

        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try (var d1 = new D1TestDatabase(dataSource)) {
            var jdbc = new NamedParameterJdbcTemplate(dataSource);
            jdbc.getJdbcTemplate().execute("""
                    CREATE TABLE recipes (
                        recipeId INTEGER PRIMARY KEY,
                        name TEXT, description TEXT, recipeCategory TEXT,
                        calories REAL, recipeServing INTEGER
                    )
                    """);
            jdbc.getJdbcTemplate().execute("""
                    CREATE TABLE recipe_metadata (
                        recipeId INTEGER PRIMARY KEY,
                        image_urls TEXT, instruction_list TEXT
                    )
                    """);
            jdbc.getJdbcTemplate().execute("""
                    CREATE TABLE recipe_ingredients (
                        recipeId INTEGER NOT NULL, position INTEGER NOT NULL,
                        ingredient_name TEXT, quantity_text TEXT,
                        pair_status TEXT NOT NULL,
                        PRIMARY KEY (recipeId, position)
                    )
                    """);
            jdbc.getJdbcTemplate().execute("""
                    INSERT INTO recipes VALUES
                        (1, 'Test Soup', 'Test description', 'Soup', 170.9, 4),
                        (2, 'No Ingredients', NULL, NULL, NULL, NULL)
                    """);
            jdbc.getJdbcTemplate().execute("""
                    INSERT INTO recipe_metadata VALUES
                        (1, '["https://example.com/cover.jpg"]', '["Cook.", null]')
                    """);
            jdbc.getJdbcTemplate().execute("""
                    INSERT INTO recipe_ingredients VALUES
                        (1, 2, 'onion', NULL, 'unresolved'),
                        (1, 1, 'chicken', '200 g', 'verified')
                    """);

            D1TestDatabase.prepareExistingSchema(dataSource);
            var mapper = new ObjectMapper();
            var service = new RecipeService(new RecipeRepository(d1.client(mapper), mapper));
            var recipe = service.getById(1);
            var json = mapper.readTree(mapper.writeValueAsString(recipe));

            assert json.path("ingredients").isArray() : "Missing ingredients array";
            assert recipe.recipeId() == 1;
            assert "Test Soup".equals(recipe.name());
            assert "Test description".equals(recipe.description());
            assert "Soup".equals(recipe.category());
            assert Double.valueOf(170.9).equals(recipe.calories());
            assert Integer.valueOf(4).equals(recipe.servings());
            assert "https://example.com/cover.jpg".equals(recipe.imageUrl());
            assert recipe.instructions().size() == 2;
            assert "Cook.".equals(recipe.instructions().get(0));
            assert recipe.instructions().get(1) == null;

            var items = json.path("ingredients");
            assert items.size() == 2;
            assert items.get(0).path("position").asInt() == 1;
            assert "chicken".equals(items.get(0).path("ingredientName").asText());
            assert items.get(0).path("quantity").isNull() : "Raw shared data has no verified quantity pairing";
            assert "unresolved".equals(items.get(0).path("pairStatus").asText());
            assert items.get(1).path("position").asInt() == 2;
            assert "onion".equals(items.get(1).path("ingredientName").asText());
            assert items.get(1).path("quantity").isNull() : "Unknown quantity must stay null";
            assert "unresolved".equals(items.get(1).path("pairStatus").asText());

            var empty = mapper.readTree(mapper.writeValueAsString(service.getById(2)));
            assert empty.path("ingredients").isArray();
            assert empty.path("ingredients").isEmpty();
            assert empty.path("servings").isNull();
            assert empty.path("instructions").isNull();

            // These requests must finish before trying the ingredients query.
            jdbc.getJdbcTemplate().execute("ALTER TABLE recipes DROP COLUMN recipeIngredientParts");
            expectStatus(service, 0, 400);
            expectStatus(service, -1, 400);
            expectStatus(service, 999, 404);
            System.out.println("PASS: detail composition, JSON, NULL, empty ingredients, 400 and 404.");
        } catch (java.io.IOException e) {
            throw new AssertionError("JSON conversion failed", e);
        } finally {
            dataSource.destroy();
        }
    }

    private static void expectStatus(RecipeService service, int id, int expected) {
        try {
            service.getById(id);
            throw new AssertionError("Expected HTTP status " + expected);
        } catch (ResponseStatusException e) {
            assert e.getStatusCode().value() == expected : "Wrong HTTP status";
        }
    }
}
