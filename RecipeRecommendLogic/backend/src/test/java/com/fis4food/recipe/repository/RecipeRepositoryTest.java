package com.fis4food.recipe.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.support.D1TestDatabase;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public class RecipeRepositoryTest {

    public static void main(String[] args) {
        if (!RecipeRepositoryTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea.");
        }

        var dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);

        try (var d1 = new D1TestDatabase(dataSource)) {
            var jdbc = new NamedParameterJdbcTemplate(dataSource);

            jdbc.getJdbcTemplate().execute("""
                    CREATE TABLE recipes (
                        recipeId INTEGER PRIMARY KEY,
                        name TEXT,
                        description TEXT,
                        recipeCategory TEXT,
                        calories REAL,
                        recipeServing INTEGER
                    )
                    """);

            jdbc.getJdbcTemplate().execute("""
                    CREATE TABLE recipe_metadata (
                        recipeId INTEGER PRIMARY KEY,
                        image_urls TEXT,
                        instruction_list TEXT
                    )
                    """);

            jdbc.getJdbcTemplate().execute("""
                    INSERT INTO recipes (
                        recipeId, name, description,
                        recipeCategory, calories, recipeServing
                    )
                    VALUES
                        (1, 'Example Soup', 'Simple test recipe', 'Soup', 170.9, 4),
                        (2, 'Unknown Values', NULL, NULL, NULL, NULL),
                        (3, 'Zero Values', '', '', 0, 0),
                        (4, 'No Metadata', NULL, NULL, 100, 1),
                        (5, NULL, NULL, NULL, NULL, NULL)
                    """);

            jdbc.getJdbcTemplate().execute("""
                    INSERT INTO recipe_metadata (
                        recipeId, image_urls, instruction_list
                    )
                    VALUES
                        (1,
                         '[null, "", "https://example.com/cover.jpg", "https://example.com/second.jpg"]',
                         '["First step.", null, "Third step."]'),
                        (2, NULL, NULL),
                        (3, '[]', '[]')
                    """);

            var mapper = new ObjectMapper();
            var repository = new RecipeRepository(d1.client(mapper), mapper);

            // Test basic fields, metadata, NULL, zero and missing ID.
            var recipe = repository.findById(1).orElseThrow();

            assert recipe.recipeId() == 1 : "Wrong recipe ID";
            assert "Example Soup".equals(recipe.name()) : "Wrong name";
            assert "Simple test recipe".equals(recipe.description())
                    : "Wrong description";
            assert "Soup".equals(recipe.category()) : "Wrong category";
            assert Double.valueOf(170.9).equals(recipe.calories())
                    : "Wrong calories";
            assert Integer.valueOf(4).equals(recipe.servings())
                    : "Wrong servings";

            assert "https://example.com/cover.jpg".equals(recipe.imageUrl())
                    : "Wrong cover image";
            assert recipe.instructions() != null
                    && recipe.instructions().size() == 3
                    : "Wrong steps";
            assert "First step.".equals(recipe.instructions().get(0));
            assert recipe.instructions().get(1) == null
                    : "Missing step must stay null";
            assert "Third step.".equals(recipe.instructions().get(2));

            // Test unknown values.
            var unknown = repository.findById(2).orElseThrow();

            assert "Unknown Values".equals(unknown.name())
                    : "Wrong name for recipe 2";
            assert unknown.description() == null;
            assert unknown.category() == null;
            assert unknown.calories() == null;
            assert unknown.servings() == null;
            assert unknown.imageUrl() == null;
            assert unknown.instructions() == null;

            assert repository.findById(5).orElseThrow().name() == null : "NULL name must stay null";

            // Test zero values.
            var zero = repository.findById(3).orElseThrow();

            assert Double.valueOf(0.0).equals(zero.calories());
            assert Integer.valueOf(0).equals(zero.servings());
            assert zero.imageUrl() == null;
            assert zero.instructions() != null && zero.instructions().isEmpty();

            // Test no metadata and missing ID.
            var noMetadata = repository.findById(4).orElseThrow();

            assert noMetadata.imageUrl() == null;
            assert noMetadata.instructions() == null;

            assert repository.findById(999).isEmpty()
                    : "Missing ID must return empty";

            jdbc.getJdbcTemplate().execute(
                    "UPDATE recipe_metadata SET instruction_list = 'not-json' WHERE recipeId = 1"
            );

            try {
                repository.findById(1);
                throw new AssertionError("Invalid JSON must fail");
            } catch (IllegalStateException expected) {

            }

            System.out.println(
                    "PASS: basic fields, metadata, NULL, zero and missing ID."
            );
        } finally {
            dataSource.destroy();
        }
    }
}