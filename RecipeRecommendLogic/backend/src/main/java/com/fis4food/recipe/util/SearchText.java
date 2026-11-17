package com.fis4food.recipe.util;

import java.util.Locale;

public final class SearchText {
    private SearchText() {
    }

    public static String normalize(String input) {
        return input == null ? null : input.strip().toLowerCase(Locale.ROOT);
    }
}
