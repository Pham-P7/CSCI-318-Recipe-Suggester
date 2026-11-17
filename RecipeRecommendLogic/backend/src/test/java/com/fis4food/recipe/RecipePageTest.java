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

public class RecipePageTest {
    public static void main(String[] args) throws Exception {
        if (!RecipePageTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea.");
        }

        Path database = Files.createTempFile("recipe-page-test-", ".db");
        try {
            createFixture(database);
            var application = new SpringApplication(RecipeBackendApplication.class);
            try (var d1 = D1TestDatabase.existingSchema(database);
                 var context = (ServletWebServerApplicationContext) application.run(
                    "--server.address=127.0.0.1",
                    "--server.port=0",
                    "--recipe.d1.query-url=" + d1.queryUri(), "--recipe.d1.api-token=test-token",
                    "--spring.main.banner-mode=off",
                    "--logging.level.root=ERROR"
            ); var client = HttpClient.newHttpClient()) {
                String baseUrl = "http://127.0.0.1:" + context.getWebServer().getPort();
                var mapper = new ObjectMapper();

                var response = get(client, baseUrl, "/api/recipes?limit=2&offset=0");
                assert response.statusCode() == 200
                        : "List endpoint must return 200, got " + response.statusCode();
                assert response.headers().firstValue("Content-Type").orElse("")
                        .startsWith("application/json");
                JsonNode first = mapper.readTree(response.body());
                assertPage(first, 2, 0, true, 10, 20);

                var item = first.path("items").get(0);
                assert "First".equals(item.path("name").asText());
                assert "Soup".equals(item.path("category").asText());
                assert item.path("calories").asDouble() == 170.9;
                assert item.path("servings").asInt() == 4;
                assert "https://example.com/cover.jpg".equals(item.path("imageUrl").asText());
                assert !item.has("instructions") && !item.has("ingredients")
                        : "List items must be summaries";
                var unknown = first.path("items").get(1);
                assert unknown.path("category").isNull();
                assert unknown.path("calories").isNull();
                assert unknown.path("servings").isNull();
                assert unknown.path("imageUrl").isNull();

                JsonNode second = page(client, mapper, baseUrl, "?limit=2&offset=2");
                assertPage(second, 2, 2, true, 30, 40);
                assert second.path("items").get(0).path("calories").asDouble() == 0;
                assert second.path("items").get(0).path("servings").asInt() == 0;
                assert second.path("items").get(1).path("calories").isNull();
                assertPage(page(client, mapper, baseUrl, "?limit=2&offset=4"), 2, 4, false, 50);
                assertPage(page(client, mapper, baseUrl, "?limit=2&offset=3"), 2, 3, false, 40, 50);
                assertPage(page(client, mapper, baseUrl, "?limit=2&offset=1000"), 2, 1000, false);
                assertPage(page(client, mapper, baseUrl, "?limit=5"), 5, 0, false, 10, 20, 30, 40, 50);
                assertPage(page(client, mapper, baseUrl, "?limit=100"), 100, 0, false, 10, 20, 30, 40, 50);
                assertPage(page(client, mapper, baseUrl, ""), 50, 0, false, 10, 20, 30, 40, 50);

                for (String query : new String[]{
                        "?limit=0", "?limit=-1", "?limit=101", "?offset=-1",
                        "?limit=abc", "?offset=abc", "?offset=2147483648"
                }) {
                    assert get(client, baseUrl, "/api/recipes" + query).statusCode() == 400
                            : "Invalid request must return 400: " + query;
                }
                System.out.println("PASS: pagination HTTP, order, hasMore, NULL, summaries, defaults and 400.");
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static void createFixture(Path database) {
        var source = new SingleConnectionDataSource("jdbc:sqlite:" + database, true);
        try {
            var jdbc = new JdbcTemplate(source);
            // Only the columns needed for list summaries are present.
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
                        (50, 'Fifth', 'Dessert', 500, 2),
                        (20, 'Unknown', NULL, NULL, NULL),
                        (40, 'Fourth', 'Main', 9e999, 4),
                        (10, 'First', 'Soup', 170.9, 4),
                        (30, 'Zero', 'Drink', 0, 0)
                    """);
            jdbc.execute("""
                    INSERT INTO recipe_metadata VALUES
                        (10, '[null, "", "https://example.com/cover.jpg"]'),
                        (20, NULL), (30, '[]'), (40, 'null')
                    """);
        } finally {
            source.destroy();
        }
    }

    private static HttpResponse<String> get(HttpClient client, String base, String path)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode page(HttpClient client, ObjectMapper mapper,
                                 String base, String query) throws Exception {
        var response = get(client, base, "/api/recipes" + query);
        assert response.statusCode() == 200 : "List request failed: " + query;
        return mapper.readTree(response.body());
    }

    private static void assertPage(JsonNode page, int limit, int offset,
                                   boolean hasMore, int... ids) {
        assert page.path("limit").asInt() == limit;
        assert page.path("offset").asInt() == offset;
        assert page.path("hasMore").isBoolean();
        assert page.path("hasMore").asBoolean() == hasMore : "Wrong hasMore";
        JsonNode items = page.path("items");
        assert items.isArray() && items.size() == ids.length : "Wrong page size";
        for (int i = 0; i < ids.length; i++) {
            assert items.get(i).path("recipeId").asInt() == ids[i] : "Wrong page/order";
        }
    }
}
