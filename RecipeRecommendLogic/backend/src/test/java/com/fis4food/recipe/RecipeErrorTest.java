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

public class RecipeErrorTest {
    public static void main(String[] args) throws Exception {
        if (!RecipeErrorTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea.");
        }
        Path database = Files.createTempFile("recipe-error-test-", ".db");
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

                assertError(client, mapper, base, "/api/recipes?limit=0", 400);
                assertError(client, mapper, base, "/api/recipes?offset=-1", 400);
                assertError(client, mapper, base, "/api/recipes?minCalories=-1", 400);
                assertError(client, mapper, base, "/api/recipes?minCalories=NaN", 400);
                assertError(client, mapper, base,
                        "/api/recipes?minCalories=200&maxCalories=100", 400);
                assertError(client, mapper, base, "/api/recipes/0", 400);
                assertError(client, mapper, base, "/api/recipes/999", 404);

                JsonNode typeError = assertError(client, mapper, base,
                        "/api/recipes?limit=abc", 400);
                assert typeError.path("message").asText().contains("limit")
                        : "Type error must identify the invalid parameter";
                assertError(client, mapper, base, "/api/recipes?offset=abc", 400);
                assertError(client, mapper, base, "/api/recipes?maxCalories=abc", 400);
                assertError(client, mapper, base, "/api/recipes/abc", 400);

                var detail = get(client, base, "/api/recipes/1");
                assert detail.statusCode() == 200 : "Valid detail must still work";
                assert mapper.readTree(detail.body()).path("recipeId").asInt() == 1;
                var empty = get(client, base, "/api/recipes?minCalories=500");
                assert empty.statusCode() == 200 : "Empty search must stay 200";
                assert mapper.readTree(empty.body()).path("items").isEmpty();
                assert get(client, base, "/api/no-such-route").statusCode() == 404;
                System.out.println("PASS: business errors, type errors, message JSON, 400/404 and valid responses.");
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static JsonNode assertError(HttpClient client, ObjectMapper mapper,
                                        String base, String path, int status) throws Exception {
        var response = get(client, base, path);
        assert response.statusCode() == status : "Wrong status for " + path;
        assert response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/json");
        JsonNode body = mapper.readTree(response.body());
        assert body.path("message").isTextual()
                : "Expected message JSON, actual body=" + response.body();
        assert !body.path("message").asText().isBlank();
        assert body.size() == 1 : "Error body must contain only message";
        return body;
    }

    private static HttpResponse<String> get(HttpClient client, String base, String path)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void createFixture(Path database) {
        var source = new SingleConnectionDataSource("jdbc:sqlite:" + database, true);
        try {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE recipes (
                        recipeId INTEGER PRIMARY KEY, name TEXT,
                        description TEXT, recipeCategory TEXT,
                        calories REAL, recipeServing INTEGER
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE recipe_metadata (
                        recipeId INTEGER PRIMARY KEY,
                        image_urls TEXT, instruction_list TEXT
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE recipe_ingredients (
                        recipeId INTEGER, position INTEGER, ingredient_name TEXT,
                        quantity_text TEXT, pair_status TEXT
                    )
                    """);
            jdbc.execute("INSERT INTO recipes VALUES (1, 'Soup', NULL, 'Soup', 100, 2)");
        } finally {
            source.destroy();
        }
    }
}
