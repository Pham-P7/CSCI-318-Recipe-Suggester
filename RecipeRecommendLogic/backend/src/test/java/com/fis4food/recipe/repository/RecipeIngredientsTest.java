package com.fis4food.recipe.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.support.D1TestDatabase;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public class RecipeIngredientsTest {

    public static void main(String[] args) {

        if (!RecipeIngredientsTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea.");
        }

        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);

        try (var d1 = new D1TestDatabase(dataSource)) {
            var jdbc = new NamedParameterJdbcTemplate(dataSource);

            jdbc.getJdbcTemplate().execute("""
                    CREATE TABLE recipes(recipeId INTEGER PRIMARY KEY, recipeIngredientParts TEXT)
                    """);
            jdbc.getJdbcTemplate().execute("""
                    INSERT INTO recipes VALUES
                        (1, 'c("chicken", "onion", "salt")'),
                        (2, '"rice"'),
                        (3, 'character(0)'),
                        (4, NULL)
                    """);

            var mapper = new ObjectMapper();
            var repository = new RecipeRepository(d1.client(mapper), mapper);

            // Test basic ingredient mapping, order, NULL and recipe isolation.
            var items = repository.findIngredientsByRecipeId(1);

            assert items.size() == 3 : "Expected three ingredients";

            assert items.get(0).position() == 1 : "Wrong order";
            assert "chicken".equals(items.get(0).ingredientName());
            assert items.get(0).quantity() == null;
            assert "unresolved".equals(items.get(0).pairStatus());

            assert items.get(1).position() == 2 : "Wrong order";
            assert "onion".equals(items.get(1).ingredientName());
            assert items.get(1).quantity() == null
                    : "Unknown quantity must stay null";
            assert "unresolved".equals(items.get(1).pairStatus());

            assert items.get(2).position() == 3 : "Wrong order";
            assert "salt".equals(items.get(2).ingredientName());
            assert items.get(2).quantity() == null : "Do not invent a quantity from a raw vector";
            assert repository.findIngredientsByRecipeId(3).isEmpty();
            assert repository.findIngredientsByRecipeId(4).isEmpty();

            // Test recipe isolation.
            var other = repository.findIngredientsByRecipeId(2);

            assert other.size() == 1 : "Recipe data must stay separate";
            assert "rice".equals(other.get(0).ingredientName());

            assert repository.findIngredientsByRecipeId(999).isEmpty();

            System.out.println(
                    "PASS: ingredient mapping, order, NULL and recipe isolation."
            );
        } finally {
            dataSource.destroy();
        }
    }
}