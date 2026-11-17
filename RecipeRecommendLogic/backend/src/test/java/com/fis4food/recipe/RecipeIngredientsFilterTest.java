package com.fis4food.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.support.D1TestDatabase;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class RecipeIngredientsFilterTest {
    public static void main(String[] args) throws Exception {
        if (!RecipeIngredientsFilterTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea.");
        }
        Path directory = Files.createTempDirectory("recipe-required-test-");
        Path database = directory.resolve("ingredients.db");
        try {
            createFixture(database);
            var application = new SpringApplication(RecipeBackendApplication.class);
            try (var d1 = D1TestDatabase.existingSchema(database);
                 var context = (ServletWebServerApplicationContext) application.run(
                    "--server.address=127.0.0.1", "--server.port=0",
                    "--recipe.d1.query-url=" + d1.queryUri(), "--recipe.d1.api-token=test-token",
                    "--spring.main.banner-mode=off", "--logging.level.root=ERROR"
            ); var client = HttpClient.newHttpClient()) {
                String base = "http://127.0.0.1:" + context.getWebServer().getPort();
                var mapper = new ObjectMapper();
                String both = query("ingredients", "chicken", "ingredients", "onion");

                // Extra ingredients are allowed. Duplicate chicken rows cannot stand in for onion.
                assertPage(page(client, mapper, base, both), 50, 0, false, 1, 10);
                assertPage(page(client, mapper, base, query("ingredients", " CHICKEN ",
                                "ingredients", "chicken", "ingredients", " ONION ")),
                        50, 0, false, 1, 10);
                assertPage(page(client, mapper, base, query("ingredients", "chicken")),
                        50, 0, false, 1, 2, 5, 6, 9, 10, 11, 12);
                assertPage(page(client, mapper, base, query("ingredients", "egg")), 50, 0, false);
                assertPage(page(client, mapper, base, query("ingredients", "eggplant")), 50, 0, false, 4);
                assertPage(page(client, mapper, base, query("ingredients", "carrot")), 50, 0, false, 12);
                assertPage(page(client, mapper, base, query("ingredients", "carrots")), 50, 0, false, 11);
                assertPage(page(client, mapper, base, query("ingredients", "salt, optional")),
                        50, 0, false, 6);
                assertPage(page(client, mapper, base, query("ingredients", "\u2003 SÓY \u2003")),
                        50, 0, false, 7);
                assertPage(page(client, mapper, base, query("ingredients", "not-recorded")),
                        50, 0, false);

                assertPage(page(client, mapper, base, both + "&excludedIngredients=salt"),
                        50, 0, false, 10);
                assertPage(page(client, mapper, base, both + "&excludedIngredients=tomato"),
                        50, 0, false, 1);
                assertPage(page(client, mapper, base,
                                both + "&excludedIngredients=salt&excludedIngredients=tomato"),
                        50, 0, false);
                assertPage(page(client, mapper, base,
                                query("ingredients", "chicken", "excludedIngredients", "chicken")),
                        50, 0, false);
                assertPage(page(client, mapper, base,
                                query("ingredients", "chicken", "excludedIngredients", "egg")),
                        50, 0, false, 1, 2, 5, 6, 10, 11, 12);

                String combined = both + "&keyword=CHICKEN&category=Soup&minCalories=0&maxCalories=200";
                assertPage(page(client, mapper, base, combined + "&limit=1"), 1, 0, true, 1);
                assertPage(page(client, mapper, base, combined + "&limit=1&offset=1"), 1, 1, false, 10);
                assertPage(page(client, mapper, base, combined + "&offset=100"), 50, 100, false);
                assertPage(page(client, mapper, base, both + "&maxCalories=100"), 50, 0, false, 1);
                assertPage(page(client, mapper, base, ""),
                        50, 0, false, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

                for (String value : new String[]{"", " ", "\u2003"}) {
                    var response = get(client, base, query("ingredients", "chicken", "ingredients", value));
                    assert response.statusCode() == 400 : "Blank selected ingredient must return 400";
                    assert mapper.readTree(response.body()).path("message").isTextual();
                }
                System.out.println("PASS: all selected ingredients, DISTINCT, extra ingredients, exclusions, pagination and 400.");
            }
        } finally {
            Files.deleteIfExists(database);
            Files.deleteIfExists(directory);
        }
    }

    private static String query(String... pairs) {
        var parts = new java.util.ArrayList<String>();
        for (int i = 0; i < pairs.length; i += 2) {
            parts.add(pairs[i] + "=" + URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return "?" + String.join("&", parts);
    }

    private static HttpResponse<String> get(HttpClient client, String base, String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/recipes" + query))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode page(HttpClient client, ObjectMapper mapper,
                                 String base, String query) throws Exception {
        var response = get(client, base, query);
        assert response.statusCode() == 200 : "Valid ingredients query failed: " + response.body();
        return mapper.readTree(response.body());
    }

    private static void assertPage(JsonNode page, int limit, int offset, boolean hasMore, int... ids) {
        assert page.path("limit").asInt() == limit;
        assert page.path("offset").asInt() == offset;
        assert page.path("hasMore").isBoolean();
        assert page.path("hasMore").asBoolean() == hasMore : "Wrong hasMore";
        JsonNode items = page.path("items");
        assert items.isArray() && items.size() == ids.length : "Wrong required ingredient rows: " + page;
        for (int i = 0; i < ids.length; i++) {
            assert items.get(i).path("recipeId").asInt() == ids[i] : "Wrong required recipe/order";
        }
    }

    private static void createFixture(Path database) {
        var source = new SingleConnectionDataSource("jdbc:sqlite:" + database, true);
        try {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE recipes (
                        recipeId INTEGER PRIMARY KEY, name TEXT, recipeCategory TEXT,
                        calories REAL, recipeServing INTEGER
                    )
                    """);
            jdbc.execute("CREATE TABLE recipe_metadata(recipeId INTEGER PRIMARY KEY, image_urls TEXT)");
            jdbc.execute("""
                    CREATE TABLE recipe_ingredients (
                        recipeId INTEGER, position INTEGER, ingredient_name TEXT,
                        PRIMARY KEY(recipeId, position)
                    )
                    """);
            jdbc.execute("""
                    INSERT INTO recipes VALUES
                        (1, 'Chicken Onion Soup', 'Soup', 100, 2),
                        (2, 'Chicken Rice', 'Main', 200, 2),
                        (3, 'Onion Soup', 'Soup', 150, 2),
                        (4, 'Eggplant', 'Main', 120, 2),
                        (5, 'Duplicate Chicken', 'Soup', 100, 2),
                        (6, 'Comma Dish', 'Main', 100, 2),
                        (7, 'Unicode Dish', 'Main', 100, 2),
                        (8, 'No Ingredients', 'Main', 100, 2),
                        (9, 'Unknown Other Ingredient', 'Soup', 120, 2),
                        (10, 'Chicken Onion Tomato Soup', 'Soup', 150, 2),
                        (11, 'Carrots Dish', 'Main', 100, 2),
                        (12, 'Carrot Dish', 'Main', 100, 2)
                    """);
            jdbc.execute("""
                    INSERT INTO recipe_ingredients VALUES
                        (1,1,'chicken'), (1,2,'onion'), (1,3,'salt'),
                        (2,1,'chicken'), (2,2,'rice'),
                        (3,1,'onion'), (3,2,'salt'), (4,1,'eggplant'),
                        (5,1,'chicken'), (5,2,'chicken'),
                        (6,1,'salt, optional'), (6,2,'chicken'),
                        (7,1,'SÓY'), (7,2,'onion'),
                        (9,1,'chicken'), (9,2,NULL),
                        (10,1,'chicken'), (10,2,'onion'), (10,3,'tomato'),
                        (11,1,'carrots'), (11,2,'chicken'),
                        (12,1,'carrot'), (12,2,'chicken')
                    """);
        } finally {
            source.destroy();
        }
    }
}
