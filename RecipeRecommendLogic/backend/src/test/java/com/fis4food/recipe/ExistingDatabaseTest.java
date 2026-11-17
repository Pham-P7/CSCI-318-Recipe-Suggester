package com.fis4food.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fis4food.recipe.support.D1TestDatabase;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Real SQL against the inspected two-table schema; no prepared search columns or ingredient table. */
public class ExistingDatabaseTest {
    public static void main(String[] args) throws Exception {
        if (!ExistingDatabaseTest.class.desiredAssertionStatus()) throw new IllegalStateException("Run with -ea");
        var mapper = new ObjectMapper();
        var database = Files.createTempFile("existing-recipe-", ".db");
        try {
            try (var input = ExistingDatabaseTest.class.getResourceAsStream("/user-information-sample.json");
                 var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
                var sample = mapper.readTree(input);
                for (var schema : sample.path("schema")) {
                    try (var sql = connection.createStatement()) { sql.execute(schema.path("sql").asText()); }
                }
                for (String table : List.of("recipes", "recipe_metadata")) {
                    for (var row : sample.path(table)) {
                        var names = new java.util.ArrayList<String>();
                        row.fieldNames().forEachRemaining(names::add);
                        String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
                        try (var sql = connection.prepareStatement("INSERT INTO " + table + " (" + String.join(",", names) + ") VALUES (" + placeholders + ")")) {
                            for (int i = 0; i < names.size(); i++) {
                                var value = row.get(names.get(i));
                                sql.setObject(i + 1, value.isNull() ? null : value.isNumber() ? value.numberValue() : value.textValue());
                            }
                            sql.executeUpdate();
                        }
                    }
                }
                try (var sql = connection.prepareStatement("INSERT INTO recipes(recipeId,name,recipeCategory,calories,recipeServing,recipeIngredientParts) VALUES(?,?,?,?,?,?)")) {
                    Object[][] rows = {
                            {10,"Fixture Chicken", "Main", 100, 2, "c(\"chicken\", \"onion\", \"salt\")"},
                            {11,"Fixture SÓUP", "\u2003 MÉAL \u2003", 100, null, "c(\"\u2003 SÓY \u2003\", \"salt, optional\")"},
                            {12,"Fixture Duplicate", "Main", 100, 2, "c(\"chicken\", \"chicken\")"},
                            {13,"Fixture Eggplant", "Main", 100, 2, "\"eggplant\""},
                            {14,"Fixture Empty", "Main", 100, 2, "character(0)"},
                            {15,"Fixture Unknown", "Main", 100, 2, null},
                            {16,"Fixture Partial", "Main", 100, 2, "c(\"chicken\", NA)"},
                            {17,"Fixture Rice", "Main", 100, 2, "\"rice\""},
                            {18,"Fixture Quoted", "Main", 100, 2, "c(\"chef's \\\"salt\\\"\", \"BANANA\", \"NA\")"},
                            {19,"Fixture Unicode Escape", "Main", 100, 2, "c(\"S\\u00d3Y\")"},
                            {20,"Fixture Last", "Main", 100, 2, "c(\"rice\")"},
                            {21,"Corrupt Raw", "Main", 100, 2, "c(broken)"},
                            {22,"Missing Raw", "Main", 100, 2, "NA"},
                            {23,"Blank Raw", "Main", 100, 2, "c(\" \")"},
                            {24,"Corrupt Trailing", "Main", 100, 2, "[\"rice\"] trailing"}
                    };
                    for (var row : rows) {
                        for (int i = 0; i < row.length; i++) sql.setObject(i + 1, row[i]);
                        sql.executeUpdate();
                    }
                    for (int i = 30; i < 150; i++) {
                        Object[] row = {i,"Unrelated SÓUP " + i,"Main",100,2,"\"rice\""};
                        for (int n = 0; n < row.length; n++) sql.setObject(n + 1, row[n]);
                        sql.executeUpdate();
                    }
                    for (int id : new int[]{200,201}) {
                        Object[] row = {id,"NopeMarker " + id,"Main",100,2,"\"rice\""};
                        for (int n = 0; n < row.length; n++) sql.setObject(n + 1, row[n]);
                        sql.executeUpdate();
                    }
                }
            }
            byte[] before = Files.readAllBytes(database);
            try (var d1 = new D1TestDatabase(database);
                 var context = (ServletWebServerApplicationContext) new SpringApplication(RecipeBackendApplication.class).run(
                         "--server.address=127.0.0.1", "--server.port=0", "--recipe.d1.query-url=" + d1.queryUri(),
                         "--recipe.d1.api-token=test-token", "--spring.main.banner-mode=off", "--logging.level.root=OFF");
                 var http = HttpClient.newHttpClient()) {
                String base = "http://127.0.0.1:" + context.getWebServer().getPort();
                var detail = get(http, mapper, base, "/api/recipes/2");
                assert detail.path("name").asText().equals("Biryani");
                assert detail.path("instructions").size() == 11;
                assert detail.path("ingredients").size() == 25;
                assert detail.path("ingredients").get(0).path("ingredientName").asText().equals("saffron");
                for (var ingredient : detail.path("ingredients")) {
                    assert ingredient.path("quantity").isNull() : "Raw quantities must not be paired by index";
                    assert ingredient.path("pairStatus").asText().equals("unresolved");
                }
                ids(get(http,mapper,base,"/api/recipes?keyword=biryani&minCalories=1110&maxCalories=1111&ingredients=saffron&excludedIngredients=peanut"),2);
                ids(get(http,mapper,base,"/api/recipes?ingredients=chicken&ingredients=onion"),10);
                ids(get(http,mapper,base,"/api/recipes?ingredients=chicken"),10,12,16);
                ids(get(http,mapper,base,"/api/recipes?ingredients="+enc(" SÓY ")),11,19);
                ids(get(http,mapper,base,"/api/recipes?ingredients="+enc("salt, optional")),11);
                ids(get(http,mapper,base,"/api/recipes?ingredients="+enc("chef's \"salt\"")),18);
                ids(get(http,mapper,base,"/api/recipes?ingredients=NA"),18);
                ids(get(http,mapper,base,"/api/recipes?keyword="+enc("SÓUP")+"&category="+enc("MÉAL")),11);
                ids(get(http,mapper,base,"/api/recipes?keyword=fixture&excludedIngredients="+enc("SÓY")),10,12,13,17,18,20);
                ids(get(http,mapper,base,"/api/recipes?keyword=fixture&excludedIngredients="+enc("SÓY")+"&offset=1&limit=2"),12,13);
                var first = get(http,mapper,base,"/api/recipes?keyword=NopeMarker&limit=1");
                ids(first,200); assert first.path("hasMore").asBoolean();
                var last = get(http,mapper,base,"/api/recipes?keyword=NopeMarker&limit=1&offset=1");
                ids(last,201); assert !last.path("hasMore").asBoolean();
                ids(get(http,mapper,base,"/api/recipes?keyword=NopeMarker&offset=2"));
                assert get(http,mapper,base,"/api/recipes/16").path("ingredients").get(1).path("ingredientName").isNull();
                assert get(http,mapper,base,"/api/recipes/14").path("ingredients").isEmpty();
                ids(get(http,mapper,base,"/api/recipes?keyword=Corrupt&ingredients=rice"));
                var corrupt = http.send(HttpRequest.newBuilder(URI.create(base+"/api/recipes/21")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assert corrupt.statusCode() == 500 : "Corrupt detail must not masquerade as a valid empty vector";
                var quoted = get(http,mapper,base,"/api/recipes/18").path("ingredients");
                assert quoted.get(0).path("ingredientName").asText().equals("chef's \"salt\"");
                assert quoted.get(1).path("ingredientName").asText().equals("BANANA");
                assert quoted.get(2).path("ingredientName").asText().equals("NA");
                ApiSmokeCheck.main(new String[]{base});
            }
            assert Arrays.equals(before, Files.readAllBytes(database)) : "Queries must leave the existing database unchanged";
            System.out.println("PASS: inspected existing schema, real samples, R vectors, NULL, Unicode, exact filters, pagination and read-only access.");
        } finally { Files.deleteIfExists(database); }
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static JsonNode get(HttpClient http, ObjectMapper mapper, String base, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assert response.statusCode() == 200 : "Expected existing schema to work: " + path + " -> " + response.statusCode() + " " + response.body();
        return mapper.readTree(response.body());
    }
    private static void ids(JsonNode page, int... expected) {
        var actual = new java.util.ArrayList<Integer>();
        for (var item : page.path("items")) actual.add(item.path("recipeId").asInt());
        assert actual.equals(Arrays.stream(expected).boxed().toList()) : "Wrong IDs: " + actual;
    }
}
