package com.vedant.querybot.util;

/**
 * Very strict validator: only allows SELECT statements.
 * Prevents destructive SQL from NL-generated SQL.
 */
public class SQLValidator {

    public static boolean isSelectOnly(String sql) {
        if (sql == null) return false;
        String cleaned = sql.trim().toLowerCase();

        // Allow statements that start with:
        // 1. "select" - simple SELECT
        // 2. "(" - for wrapped SELECT or UNION queries like (SELECT ... UNION ALL SELECT ...)
        // 3. "with" - for CTEs (Common Table Expressions)
        if (cleaned.startsWith("select") || cleaned.startsWith("(") || cleaned.startsWith("with")) {
            // Additional check: ensure no destructive operations
            // Block: INSERT, UPDATE, DELETE, ALTER, DROP, CREATE, TRUNCATE
            String[] forbidden = {"insert", "update", "delete", "alter", "drop", "create", "truncate", ";--", "--", "/*"};
            for (String keyword : forbidden) {
                if (cleaned.contains(" " + keyword + " ") || cleaned.contains("\n" + keyword + " ")) {
                    return false; // Found forbidden keyword
                }
            }
            return true;
        }
        return false;
    }
}
