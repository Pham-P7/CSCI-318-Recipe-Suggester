package com.fis4food.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.support.D1TestDatabase;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class RecipeD1IntegrationTest {
    public static void main(String[] args) throws Exception {
        if (!RecipeD1IntegrationTest.class.desiredAssertionStatus()) throw new IllegalStateException("Run with -ea");
        var database = Files.createTempFile("d1-integration-", ".db");
        try {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE recipes(recipeId INTEGER PRIMARY KEY,name TEXT,description TEXT,recipeCategory TEXT,calories REAL,recipeServing INTEGER,name_match_text TEXT,category_match_name TEXT)");
                sql.execute("CREATE TABLE recipe_metadata(recipeId INTEGER PRIMARY KEY,image_urls TEXT,instruction_list TEXT)");
                sql.execute("CREATE TABLE recipe_ingredients(recipeId INTEGER,position INTEGER,ingredient_name TEXT,quantity_text TEXT,pair_status TEXT,ingredient_match_name TEXT)");
                sql.execute("INSERT INTO recipes VALUES(2,'Biryani',NULL,'Main',150.5,NULL,'biryani','main')");
                sql.execute("INSERT INTO recipe_metadata VALUES(2,'[null,\"https://example.test/image.jpg\"]','[\"Cook\",null]')");
                sql.execute("INSERT INTO recipe_ingredients VALUES(2,1,'rice',NULL,'unresolved','rice')");
            }
            try (var d1 = D1TestDatabase.existingSchema(database);
                 var context = (ServletWebServerApplicationContext) new SpringApplication(RecipeBackendApplication.class).run(
                         "--server.address=127.0.0.1", "--server.port=0",
                         "--spring.datasource.url=jdbc:sqlite::memory:",
                         "--recipe.d1.query-url=" + d1.queryUri(), "--recipe.d1.api-token=test-token",
                         "--recipe.d1.request-timeout=PT2S", "--spring.main.banner-mode=off", "--logging.level.root=OFF");
                 var client = HttpClient.newHttpClient()) {
                String base = "http://127.0.0.1:" + context.getWebServer().getPort();
                var mapper = new ObjectMapper();
                var detail = get(client, base, "/api/recipes/2");
                assert detail.statusCode() == 200 : "Detail must come from D1, not the empty local datasource: " + detail.statusCode();
                var recipe = mapper.readTree(detail.body());
                assert recipe.path("servings").isNull();
                assert recipe.path("instructions").get(1).isNull();
                assert recipe.path("ingredients").get(0).path("quantity").isNull();
                assert "https://example.test/image.jpg".equals(recipe.path("imageUrl").asText());
                assert get(client, base, "/api/recipes?ingredients=rice&minCalories=150&maxCalories=151").statusCode() == 200;

                String selected97 = conditions("ingredients", 97);
                assert get(client, base, "/api/recipes?" + selected97).statusCode() == 200 : "Exactly 100 bound positions must be accepted";
                assertError(client, mapper, base, "/api/recipes?" + conditions("ingredients", 98), 400);
                assert get(client, base, "/api/recipes?" + conditions("excludedIngredients", 98)).statusCode() == 200;
                assertError(client, mapper, base, "/api/recipes?" + conditions("excludedIngredients", 99), 400);
                assert get(client, base, "/api/recipes?keyword=x&category=main&minCalories=0&maxCalories=200&"
                        + conditions("ingredients", 47) + "&" + conditions("excludedIngredients", 46)).statusCode() == 200;
                String duplicates = String.join("&", java.util.Collections.nCopies(120, "ingredients=rice"));
                var deduplicated = get(client, base, "/api/recipes?" + duplicates);
                assert deduplicated.statusCode() == 200;
                assert mapper.readTree(deduplicated.body()).path("items").size() == 1;
                assertError(client, mapper, base, "/api/recipes?keyword=x&category=main&minCalories=0&maxCalories=200&"
                        + conditions("ingredients", 47) + "&" + conditions("excludedIngredients", 47), 400);

                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                     var sql = connection.createStatement()) {
                    sql.execute("UPDATE recipe_metadata SET instruction_list='private invalid JSON' WHERE recipeId=2");
                }
                assertError(client, mapper, base, "/api/recipes/2", 500);
                assert get(client, base, "/api/recipes/999").statusCode() == 404;
                assert get(client, base, "/api/no-such-route").statusCode() == 404;
                d1.fail(403, "private-token-and-SQL");
                assertError(client, mapper, base, "/api/recipes", 502);
                d1.fail(200, "{\"success\":true,\"result\":[{\"success\":false,\"results\":[]}]}");
                assertError(client, mapper, base, "/api/recipes", 502);
                d1.delay(2500);
                assertError(client, mapper, base, "/api/recipes", 504);
                System.out.println("PASS: Spring uses D1, nullable detail, parameter budget, 400/404/500/502/504 and sanitized error JSON.");
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static String conditions(String key, int count) {
        return IntStream.range(0, count).mapToObj(i -> key + "=ingredient-" + i).collect(Collectors.joining("&"));
    }

    private static HttpResponse<String> get(HttpClient client, String base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertError(HttpClient client, ObjectMapper mapper, String base, String path, int expected) throws Exception {
        var response = get(client, base, path);
        assert response.statusCode() == expected : "Expected " + expected + ", actual " + response.statusCode();
        var json = mapper.readTree(response.body());
        assert json.size() == 1 && json.path("message").isTextual();
        assert !response.body().contains("private") && !response.body().contains("test-token");
    }
}
