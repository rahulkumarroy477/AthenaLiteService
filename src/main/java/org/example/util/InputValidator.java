package org.example.util;

import java.util.regex.Pattern;

public class InputValidator {

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9@.+_-]+$");
    private static final Pattern QUERY_ID_PATTERN = Pattern.compile("^qr_[0-9]+$");

    public static String sanitizeTableName(String name) {
        if (name == null) return null;
        return name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }

    public static boolean isValidTableName(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.length() > 128) return false;
        return TABLE_NAME_PATTERN.matcher(name).matches();
    }

    public static boolean isValidUserId(String userId) {
        if (userId == null || userId.isBlank()) return false;
        if (userId.length() > 256) return false;
        return USER_ID_PATTERN.matcher(userId).matches();
    }

    public static boolean isValidQueryId(String queryId) {
        if (queryId == null || queryId.isBlank()) return false;
        if (queryId.length() > 64) return false;
        return QUERY_ID_PATTERN.matcher(queryId).matches();
    }
}
