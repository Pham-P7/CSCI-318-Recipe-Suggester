package com.fis4food.recipe.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fis4food.recipe.dto.RecipeSummary;
import com.fis4food.recipe.model.Recipe;
import com.fis4food.recipe.model.RecipeIngredient;
import com.fis4food.recipe.util.SearchText;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.List;
import java.util.Optional;

@Repository
public class RecipeRepository {
    private final D1Client d1;
    private final ObjectMapper objectMapper;

    public RecipeRepository(D1Client d1, ObjectMapper objectMapper) {
        this.d1 = d1;
        this.objectMapper = objectMapper;
    }

    public Optional<Recipe> findById(int recipeId) {

        String sql = """
                SELECT r.recipeId, r.name, r.description,
                       r.recipeCategory AS category,
                       r.calories, r.recipeServing,
                       m.image_urls, m.instruction_list
                FROM recipes AS r
                LEFT JOIN recipe_metadata AS m ON r.recipeId = m.recipeId
                WHERE r.recipeId = CAST(? AS INTEGER)
                """;

        // The API sends params as strings; CAST in the SQL handles this ID.
        List<JsonNode> rows = d1.query(sql, List.of(Integer.toString(recipeId)));

        return rows.stream().findFirst().map(row -> new Recipe(
                requiredInt(row, "recipeId"), text(row, "name"), text(row, "description"),
                text(row, "category"), calories(row), nullableInt(row, "recipeServing"),
                firstImage(readStringList(text(row, "image_urls"))),
                readStringList(text(row, "instruction_list")),
                List.of() ));
    }


    public List<RecipeIngredient> findIngredientsByRecipeId(int recipeId) {

        String sql = "SELECT recipeIngredientParts FROM recipes WHERE recipeId = CAST(? AS INTEGER)";
        List<JsonNode> rows = d1.query(sql, List.of(Integer.toString(recipeId)));

        if (rows.isEmpty()) return List.of();
        List<String> names = readIngredientNames(text(rows.get(0), "recipeIngredientParts"));
        if (names == null) return List.of();
        var ingredients = new ArrayList<RecipeIngredient>();
        for (int i = 0; i < names.size(); i++) {
            // The amounts do not reliably match the names. 这里先保留 null.
            ingredients.add(new RecipeIngredient(i + 1, names.get(i), null, "unresolved"));
        }
        return List.copyOf(ingredients);
    }

    // Match Java String.strip whitespace; SQLite lower() itself only folds ASCII.
    private static final String STRIP = "char(9,10,11,12,13,28,29,30,31,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)";



    public List<RecipeSummary> findPage(
            int fetchLimit, int offset, Double minCalories, Double maxCalories,
            String keyword, String category,
            List<String> selectedNames, List<String> excludedNames) {

        boolean filterText = keyword != null && !keyword.isEmpty();
        boolean filterCategory = category != null && !category.isEmpty();
        boolean filterIngredients = !selectedNames.isEmpty() || !excludedNames.isEmpty();
        boolean verifyMatches = filterText || filterCategory || filterIngredients;

        String sql = "";
        if (filterIngredients) {
            // Parse the stored R vector in the query; the shared tables stay unchanged.
            sql = """
                    WITH vectors AS (
                        SELECT *, CASE
                            WHEN trim(recipeIngredientParts) = 'character(0)' THEN '[]'
                            WHEN substr(trim(recipeIngredientParts),1,2) = 'c('
                                 AND substr(trim(recipeIngredientParts),-1) = ')'
                                THEN '[' || substr(trim(recipeIngredientParts),3,length(trim(recipeIngredientParts))-3) || ']'
                            WHEN substr(trim(recipeIngredientParts),1,1) = '[' THEN trim(recipeIngredientParts)
                            ELSE '[' || trim(recipeIngredientParts) || ']'
                        END AS raw_json FROM recipes
                    ), source AS (
                        SELECT *, CASE WHEN json_valid(raw_json) THEN
                            CASE WHEN json_type(raw_json) = 'array' THEN raw_json END END AS ingredient_json FROM vectors
                    )
                    """;
        }

        sql += """
                SELECT r.recipeId, r.name, r.recipeCategory AS category,
                       r.calories, r.recipeServing, m.image_urls
                """;

        if (filterIngredients) sql += ", r.recipeIngredientParts\n";

        sql += "FROM " + (filterIngredients ? "source" : "recipes") + " AS r\n"
                + "LEFT JOIN recipe_metadata AS m ON r.recipeId = m.recipeId\nWHERE 1 = 1\n";

        var params = new ArrayList<String>();

        if (minCalories != null || maxCalories != null) {
            sql += "AND r.calories BETWEEN CAST(? AS REAL) AND CAST(? AS REAL)\n";
            params.add(Double.toString(minCalories == null ? 0.0 : minCalories));
            params.add(Double.toString(maxCalories == null ? Double.MAX_VALUE : maxCalories));
        }

        if (filterText) {
            sql += "AND (" + nonAscii("r.name") + " OR instr(" + normalized("r.name") + ", ?) > 0)\n";
            params.add(keyword);
        }


        if (filterCategory) {
            sql += "AND (" + nonAscii("r.recipeCategory") + " OR " + normalized("r.recipeCategory") + " = ?)\n";
            params.add(category);
        }
        String unicodeIngredient = "EXISTS (SELECT 1 FROM json_each(r.ingredient_json) WHERE type = 'text' AND " + nonAscii("value") + ")";

        if (!selectedNames.isEmpty()) {
            sql += "AND (r.ingredient_json IS NULL OR " + unicodeIngredient
                    + " OR (SELECT COUNT(DISTINCT " + normalized("value") + ") FROM json_each(r.ingredient_json)"
                    + " WHERE type = 'text' AND " + normalized("value") + " IN ("
                    + placeholders(selectedNames.size()) + ")) = CAST(? AS INTEGER))\n";
            params.addAll(selectedNames);
            params.add(Integer.toString(selectedNames.size()));
        }

        if (!excludedNames.isEmpty()) {
            sql += "AND (r.ingredient_json IS NULL OR " + unicodeIngredient
                    + " OR (EXISTS (SELECT 1 FROM json_each(r.ingredient_json))"
                    + " AND NOT EXISTS (SELECT 1 FROM json_each(r.ingredient_json)"
                    + " WHERE type != 'text' OR " + normalized("value") + " = '' OR " + normalized("value")
                    + " IN (" + placeholders(excludedNames.size()) + "))))\n";
            params.addAll(excludedNames);
        }

        if (!verifyMatches) {
            params.add(Integer.toString(fetchLimit));
            params.add(Integer.toString(offset));
            return d1.query(sql + "ORDER BY r.recipeId LIMIT CAST(? AS INTEGER) OFFSET CAST(? AS INTEGER)", params)
                    .stream().map(this::summary).toList();
        }

        // SQLite lower() only handles ASCII, so Java does the final check.
        // Offset counts matches, not rejected rows. Small batches can still mean many reads.
        var result = new ArrayList<RecipeSummary>();
        long afterId = Long.MIN_VALUE;
        long toSkip = offset;

        while (result.size() < fetchLimit) {
            var pageParams = new ArrayList<>(params);
            pageParams.add(Long.toString(afterId));
            pageParams.add("101");
            var rows = d1.query(sql + "AND r.recipeId > CAST(? AS INTEGER)\nORDER BY r.recipeId LIMIT CAST(? AS INTEGER)", pageParams);
            for (JsonNode row : rows) {
                int id = requiredInt(row, "recipeId");
                if (id <= afterId) throw new IllegalStateException("Database page is not ordered");
                afterId = id;
                if (filterText && !containsNormalized(text(row, "name"), keyword)) continue;
                if (filterCategory && !Objects.equals(SearchText.normalize(text(row, "category")), category)) continue;
                if (filterIngredients && !matchesIngredients(text(row, "recipeIngredientParts"), selectedNames, excludedNames)) continue;
                if (toSkip > 0) {
                    toSkip--;
                    continue;
                }
                result.add(summary(row));
                if (result.size() == fetchLimit) break;
            }
            if (rows.size() < 101) break;
        }
        return List.copyOf(result);
    }



    private RecipeSummary summary
            (JsonNode row) {
        return new RecipeSummary(requiredInt(row, "recipeId"), text(row, "name"), text(row, "category"),
                calories(row), nullableInt(row, "recipeServing"), firstImage(readStringList(text(row, "image_urls"))));
    }

    private static String normalized(String expression) {
        return "lower(trim(" + expression + ", " + STRIP + "))";
    }

    private static String nonAscii(String expression) {
        return "length(CAST(" + expression + " AS BLOB)) != length(" + expression + ")";
    }

    private static boolean containsNormalized(String value, String keyword) {
        String normalized = SearchText.normalize(value);
        return normalized != null && normalized.contains(keyword);
    }

    private boolean matchesIngredients(String raw, List<String> selected, List<String> excluded) {
        List<String> names;
        try {
            names = readIngredientNames(raw);
        } catch (IllegalStateException invalidVector) {
            // Unknown ingredients cannot establish either a positive or an exclusion match.
            return false;
        }
        if (names == null || names.isEmpty()) return false;
        var normalizedNames = new HashSet<String>();
        for (String name : names) normalizedNames.add(SearchText.normalize(name));
        if (!normalizedNames.containsAll(selected)) return false;
        if (!excluded.isEmpty()) {
            if (normalizedNames.contains(null) || normalizedNames.contains("")) return false;
            for (String name : excluded) if (normalizedNames.contains(name)) return false;
        }
        return true;
    }


    private List<String> readIngredientNames(String raw) {
        if (raw == null) return null;
        String vector = raw.strip();
        if (vector.equals("NA") || vector.equals("NULL")) return null;
        if (vector.equals("character(0)")) return List.of();
        if (vector.startsWith("c(") && vector.endsWith(")")) vector = "[" + vector.substring(2, vector.length() - 1) + "]";
        else if (vector.startsWith("\"")) vector = "[" + vector + "]";
        if (!vector.startsWith("[")) throw new IllegalStateException("Invalid ingredient vector");
        // Only replace NA outside quotes. Otherwise a name like BANANA gets changed too.
        var json = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < vector.length(); i++) {
            char ch = vector.charAt(i);
            if (quoted) {
                json.append(ch);
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '\"') quoted = false;
            } else if (ch == '\"') {
                quoted = true;
                json.append(ch);
            } else if (vector.startsWith("NA", i)) {
                json.append("null");
                i++;
            } else json.append(ch);
        }
        return readStringList(json.toString());
    }


    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }



    //checking methods for reading JSON fields from the database rows: field, text, nullableInt, requiredInt, calories

    private static JsonNode field(JsonNode row, String name) {
        JsonNode value = row.get(name);
        if (value == null) throw new IllegalStateException("Missing database field: " + name);
        return value;
    }

    private static String text(JsonNode row, String name) {

        JsonNode value = field(row, name);

        if (value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalStateException("Invalid text database field: " + name);
        return value.textValue();
    }

    private static Integer nullableInt(JsonNode row, String name) {
        JsonNode value = field(row, name);

        // Unknown servings should stay null. Zero would mean something else.
        if (value.isNull()) return null;

        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("Invalid integer database field: " + name);
        }

        return value.intValue();
    }

    private static int requiredInt(JsonNode row, String name) {
        Integer value = nullableInt(row, name);

        if (value == null) throw new IllegalStateException("Missing integer database value: " + name);
        return value;
    }

    private static Double calories(JsonNode row) {
        JsonNode value = field(row, "calories");

        if (!value.isNumber()) return null;
        double number = value.doubleValue();
        return Double.isFinite(number) ? number : null;
    }


    private List<String> readStringList(String json) {
        if (json == null) return null;

        try {

            JsonNode root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);

            if (root == null || root.isNull()) return null;
            if (!root.isArray()) throw new IllegalStateException("Expected a JSON array in recipe metadata");

            var values = new ArrayList<String>();

            //check the elements of jsonNode array
            for (JsonNode item : root) {
                if (item.isNull()) values.add(null);
                else if (item.isTextual()) values.add(item.textValue());
                else throw new IllegalStateException("Expected text or null in recipe metadata");
            }

            return Collections.unmodifiableList(values);

        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid JSON in recipe metadata", ex);
        }
    }


    private static String firstImage(List<String> images) {
        if (images != null) {
            for (String image : images) {
                if (image != null && !image.isBlank()) return image;
            }
        }
        return null;
    }
}
