package org.example.util;

public class InputValidator {

    public static String sanitizeTableName(String name) {
        if (name == null) return null;
        return name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }

    public static boolean isValidTableName(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.contains("..") || name.contains("/") || name.contains("\\")) return false;
        if (name.length() > 128) return false;
        return name.matches("^[a-zA-Z0-9_]+$");
    }

    public static boolean isValidUserId(String userId) {
        if (userId == null || userId.isBlank()) return false;
        if (userId.contains("..") || userId.contains("/") || userId.contains("\\")) return false;
        if (userId.length() > 256) return false;
        return true;
    }
}
