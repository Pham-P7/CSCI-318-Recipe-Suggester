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

public class RecipeExcludedIngredientsTest {
    public static void main(String[] args) throws Exception {
        if (!RecipeExcludedIngredientsTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea.");
        }
        Path directory = Files.createTempDirectory("recipe-excluded-test-");
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
                assertPage(page(client, mapper, base, query("excludedIngredients", "egg")),
                        50, 0, false, 2, 3, 4, 8, 9, 10, 11, 12);
                assertPage(page(client, mapper, base, query(
                                "excludedIngredients", "egg", "excludedIngredients", "milk")),
                        50, 0, false, 2, 4, 9, 10, 11, 12);
                assertPage(page(client, mapper, base, query(
                                "excludedIngredients", " EGG ", "excludedIngredients", "egg")),
                        50, 0, false, 2, 3, 4, 8, 9, 10, 11, 12);
                assertPage(page(client, mapper, base, query("excludedIngredients", "onion")),
                        50, 0, false, 1, 2, 3, 4, 8, 9, 10, 12);
                assertPage(page(client, mapper, base, query("excludedIngredients", "salt, optional")),
                        50, 0, false, 1, 2, 3, 4, 8, 10, 11, 12);
                assertPage(page(client, mapper, base, query("excludedIngredients", "egg,milk")),
                        50, 0, false, 1, 2, 3, 4, 8, 9, 10, 11, 12);
                assertPage(page(client, mapper, base, query("excludedIngredients", "\u2003 SÓY \u2003")),
                        50, 0, false, 1, 2, 3, 4, 8, 9, 10, 11);
                String combined = query("excludedIngredients", "egg", "keyword", "SOUP",
                        "category", "Soup", "minCalories", "100", "maxCalories", "200");
                assertPage(page(client, mapper, base, combined + "&limit=2"),
                        2, 0, true, 2, 3);
                assertPage(page(client, mapper, base, combined + "&limit=2&offset=2"),
                        2, 2, false, 8, 9);
                assertPage(page(client, mapper, base, combined + "&offset=100"), 50, 100, false);
                assertPage(page(client, mapper, base, ""),
                        50, 0, false, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
                for (String value : new String[]{"", " ", "\u2003"}) {
                    var response = get(client, base, query("excludedIngredients", value));
                    assert response.statusCode() == 400 : "Blank ingredient must return 400";
                    assert mapper.readTree(response.body()).path("message").isTextual();
                }
                System.out.println("PASS: exact exclusions, commas, NULL, AND, pagination and 400.");
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
        assert response.statusCode() == 200 : "Valid exclusion failed: " + response.body();
        return mapper.readTree(response.body());
    }

    private static void assertPage(JsonNode page, int limit, int offset, boolean hasMore, int... ids) {
        assert page.path("limit").asInt() == limit;
        assert page.path("offset").asInt() == offset;
        assert page.path("hasMore").isBoolean();
        assert page.path("hasMore").asBoolean() == hasMore;
        JsonNode items = page.path("items");
        assert items.isArray() && items.size() == ids.length : "Wrong excluded recipe rows: " + page;
        for (int i = 0; i < ids.length; i++) {
            assert items.get(i).path("recipeId").asInt() == ids[i] : "Wrong excluded recipe/order";
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
                        ingredient_key TEXT, quantity_text TEXT, pair_status TEXT,
                        PRIMARY KEY(recipeId, position)
                    )
                    """);
            jdbc.execute("""
                    INSERT INTO recipes VALUES
                        (1, 'Egg Soup', 'Soup', 100, 2),
                        (2, 'Eggplant Soup', 'Soup', 150, 2),
                        (3, 'Milk Soup', 'Soup', 200, 2),
                        (4, 'Rice Bowl', 'Main', 300, 2),
                        (5, 'Missing Ingredients Soup', 'Soup', 50, 2),
                        (6, 'Blank Ingredient Soup', 'Soup', 175, 2),
                        (7, 'Null Ingredient Soup', 'Soup', 180, 2),
                        (8, 'Mixed Soup', 'Soup', 170, 2),
                        (9, 'Salt Soup', 'Soup', 190, 2),
                        (10, 'Onions Soup', 'Soup', 210, 2),
                        (11, 'Onion Soup', 'Soup', 220, 2),
                        (12, 'Soy Soup', 'Soup', 230, 2)
                    """);
            jdbc.execute("""
                    INSERT INTO recipe_ingredients VALUES
                        (1,1,'egg','egg',NULL,'unresolved'),
                        (1,2,'salt','salt',NULL,'unresolved'),
                        (2,1,'eggplant','eggplant',NULL,'unresolved'),
                        (2,2,'salt','salt',NULL,'unresolved'),
                        (3,1,'milk','milk',NULL,'unresolved'),
                        (4,1,'rice','rice',NULL,'unresolved'),
                        (6,1,'   ','',NULL,'unresolved'),
                        (7,1,NULL,NULL,NULL,'unresolved'),
                        (8,1,'eggplant','eggplant',NULL,'unresolved'),
                        (8,2,'milk','milk',NULL,'unresolved'),
                        (9,1,'salt, optional','salt, optional',NULL,'unresolved'),
                        (10,1,'onions','onion',NULL,'unresolved'),
                        (11,1,'onion','onion',NULL,'unresolved'),
                        (12,1,'SÓY','soy',NULL,'unresolved')
                    """);
        } finally {
            source.destroy();
        }
    }
}
