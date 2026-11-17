package com.fis4food.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.support.D1TestDatabase;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class RecipeCalorieTest {
    public static void main(String[] args) throws Exception {
        if (!RecipeCalorieTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea.");
        }
        Path database = Files.createTempFile("recipe-calorie-test-", ".db");
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

                // Both endpoints are included, and calories are not divided by servings.
                assertPage(page(client, mapper, base, "?minCalories=100&maxCalories=200"),
                        50, 0, false, 40, 50, 60);
                assertPage(page(client, mapper, base, "?maxCalories=200"),
                        50, 0, false, 30, 40, 50, 60);
                assertPage(page(client, mapper, base, "?minCalories=100"),
                        50, 0, false, 40, 50, 60, 70, 110);
                assertPage(page(client, mapper, base, "?minCalories=150&maxCalories=150"),
                        50, 0, false, 50);
                assertPage(page(client, mapper, base, "?maxCalories=0"),
                        50, 0, false, 30);
                assertPage(page(client, mapper, base, "?minCalories=0"),
                        50, 0, false, 30, 40, 50, 60, 70, 110);
                assertPage(page(client, mapper, base, "?minCalories=1.7976931348623157e308"),
                        50, 0, false, 110);
                assertPage(page(client, mapper, base, "?minCalories=201&maxCalories=249"),
                        50, 0, false);

                // Filter before applying limit/offset or calculating hasMore.
                assertPage(page(client, mapper, base,
                                "?minCalories=100&maxCalories=200&limit=2"),
                        2, 0, true, 40, 50);
                assertPage(page(client, mapper, base,
                                "?minCalories=100&maxCalories=200&limit=2&offset=2"),
                        2, 2, false, 60);
                assertPage(page(client, mapper, base,
                                "?minCalories=100&maxCalories=200&limit=3"),
                        3, 0, false, 40, 50, 60);
                assertPage(page(client, mapper, base,
                                "?minCalories=100&maxCalories=200&offset=100"),
                        50, 100, false);

                // Browsing without calorie bounds still includes unknown calories.
                JsonNode browse = page(client, mapper, base, "?limit=100");
                assertPage(browse, 100, 0, false,
                        10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110);
                assert browse.path("items").get(0).path("calories").isNull();
                for (var item : browse.path("items")) {
                    if (item.path("recipeId").asInt() == 80 || item.path("recipeId").asInt() == 90
                            || item.path("recipeId").asInt() == 100) {
                        assert item.path("calories").isNull() : "Invalid stored calories must not become zero";
                    }
                }

                for (String query : new String[]{
                        "?minCalories=-1", "?maxCalories=-1", "?minCalories=NaN",
                        "?maxCalories=Infinity", "?minCalories=-Infinity",
                        "?maxCalories=1e309", "?minCalories=abc",
                        "?minCalories=200&maxCalories=100"
                }) {
                    assert get(client, base, query).statusCode() == 400
                            : "Invalid bounds must return 400: " + query;
                }
                System.out.println("PASS: calorie HTTP, inclusive bounds, zero, invalid values and filtered pagination.");
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static void createFixture(Path database) {
        var source = new SingleConnectionDataSource("jdbc:sqlite:" + database, true);
        try {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE recipes (
                        recipeId INTEGER PRIMARY KEY, name TEXT,
                        recipeCategory TEXT, calories REAL, recipeServing INTEGER
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE recipe_metadata (
                        recipeId INTEGER PRIMARY KEY, image_urls TEXT
                    )
                    """);
            jdbc.execute("""
                    INSERT INTO recipes VALUES
                        (10, 'Unknown', 'Soup', NULL, 2),
                        (20, 'Negative', 'Soup', -1, 2),
                        (30, 'Zero', 'Soup', 0, 2),
                        (40, 'Lower endpoint', 'Soup', 100, 2),
                        (50, 'Middle', 'Soup', 150, 2),
                        (60, 'Upper endpoint', 'Soup', 200, 2),
                        (70, 'Above range', 'Soup', 250, 2),
                        (80, 'Positive infinity', 'Soup', 9e999, 2),
                        (90, 'Negative infinity', 'Soup', -9e999, 2),
                        (100, 'Invalid text', 'Soup', 'NA', 2),
                        (110, 'Largest finite', 'Soup', 1.7976931348623157e308, 2)
                    """);
        } finally {
            source.destroy();
        }
    }

    private static HttpResponse<String> get(HttpClient client, String base, String query)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/recipes" + query))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode page(HttpClient client, ObjectMapper mapper,
                                 String base, String query) throws Exception {
        var response = get(client, base, query);
        assert response.statusCode() == 200 : "Valid query failed: " + query;
        return mapper.readTree(response.body());
    }

    private static void assertPage(JsonNode page, int limit, int offset,
                                   boolean hasMore, int... ids) {
        assert page.path("limit").asInt() == limit;
        assert page.path("offset").asInt() == offset;
        assert page.path("hasMore").isBoolean();
        assert page.path("hasMore").asBoolean() == hasMore : "Wrong hasMore";
        JsonNode items = page.path("items");
        assert items.isArray() && items.size() == ids.length : "Calorie filter returned wrong rows";
        for (int i = 0; i < ids.length; i++) {
            assert items.get(i).path("recipeId").asInt() == ids[i] : "Wrong recipe/order";
        }
    }
}
