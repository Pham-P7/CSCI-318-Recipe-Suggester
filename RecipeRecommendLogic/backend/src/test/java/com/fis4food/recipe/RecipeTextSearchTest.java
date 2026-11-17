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

public class RecipeTextSearchTest {
    public static void main(String[] args) throws Exception {
        if (!RecipeTextSearchTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea.");
        }
        Path directory = Files.createTempDirectory("recipe-text-test-");
        Path database = directory.resolve("search.db");
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

                assertPage(page(client, mapper, base, query("keyword", " CHICKEN ")),
                        50, 0, false, 10, 20, 40, 50, 60, 90, 110);
                assertPage(page(client, mapper, base, query("category", " Soup ")),
                        50, 0, false, 10, 30, 50, 60, 80, 110);
                assertPage(page(client, mapper, base, query("keyword", "soup")),
                        50, 0, false, 10, 30, 40, 110);
                assertPage(page(client, mapper, base, query("keyword", "\u2003 SÓUP \u2003")),
                        50, 0, false, 70);
                assertPage(page(client, mapper, base, query("category", " MÉAL ")),
                        50, 0, false, 70);
                assertPage(page(client, mapper, base, query("keyword", "iCeD")),
                        50, 0, false, 100);
                assertPage(page(client, mapper, base, query("keyword", "%")),
                        50, 0, false, 50);
                assertPage(page(client, mapper, base, query("keyword", "_")),
                        50, 0, false, 50);
                assertPage(page(client, mapper, base, query("keyword", "' OR 1=1 --")),
                        50, 0, false, 120);
                assertPage(page(client, mapper, base, query("category", "No Such Category")),
                        50, 0, false);
                assertPage(page(client, mapper, base,
                                query("keyword", "chicken", "category", "soup")),
                        50, 0, false, 10, 50, 60, 110);

                String filters = query("keyword", "chicken", "category", "soup",
                        "minCalories", "100", "maxCalories", "200");
                assertPage(page(client, mapper, base, filters + "&limit=2"),
                        2, 0, true, 10, 50);
                assertPage(page(client, mapper, base, filters + "&limit=2&offset=2"),
                        2, 2, false, 60);
                assertPage(page(client, mapper, base, filters + "&limit=3"),
                        3, 0, false, 10, 50, 60);
                assertPage(page(client, mapper, base, query("keyword", " ", "category", "\u2003")),
                        50, 0, false, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120);

                JsonNode result = page(client, mapper, base, query("keyword", "chicken", "limit", "1"));
                assert " Chicken Soup ".equals(result.path("items").get(0).path("name").asText())
                        : "Display names must stay unchanged";
                System.out.println("PASS: text HTTP, Unicode, literal matching, exact category, AND and filtered pagination.");
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

    private static JsonNode page(HttpClient client, ObjectMapper mapper,
                                 String base, String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/recipes" + query))
                .timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assert response.statusCode() == 200 : "Valid search failed: " + query + " -> " + response.body();
        return mapper.readTree(response.body());
    }

    private static void assertPage(JsonNode page, int limit, int offset,
                                   boolean hasMore, int... ids) {
        assert page.path("limit").asInt() == limit;
        assert page.path("offset").asInt() == offset;
        assert page.path("hasMore").isBoolean();
        assert page.path("hasMore").asBoolean() == hasMore : "Wrong hasMore";
        JsonNode items = page.path("items");
        assert items.isArray() && items.size() == ids.length : "Wrong rows for text search: " + page;
        for (int i = 0; i < ids.length; i++) {
            assert items.get(i).path("recipeId").asInt() == ids[i] : "Wrong recipe/order";
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
                        (10, ' Chicken Soup ', ' Soup ', 100, 2),
                        (20, 'CHICKEN STEW', 'Main', 200, 2),
                        (30, 'Tomato Soup', ' SOUP ', 150, 2),
                        (40, 'Chicken Soups', 'Soups', 120, 2),
                        (50, '100% Chicken_Stock', 'Soup', 180, 2),
                        (60, 'Plain Chicken Stock', 'Soup', 180, 2),
                        (70, 'SÓUP Special', 'MÉAL', 175, 2),
                        (80, NULL, 'Soup', 130, 2),
                        (90, 'Chicken Mystery', NULL, NULL, 2),
                        (100, 'Iced Tea', 'Drink', 0, 2),
                        (110, 'Chicken Soup Deluxe', 'Soup', 300, 2),
                        (120, 'x'' OR 1=1 --', 'Literal', 10, 2)
                    """);
        } finally {
            source.destroy();
        }
    }
}
