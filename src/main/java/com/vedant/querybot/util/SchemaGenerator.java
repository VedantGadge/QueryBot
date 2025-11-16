package com.vedant.querybot.util;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a CREATE TABLE statement from inferred column types.
 * Very simple inference: if all values parse as integer -> INTEGER,
 * if all parse as double -> DOUBLE PRECISION, if value looks like date -> TIMESTAMP,
 * otherwise TEXT.
 */
public class SchemaGenerator {

    // sanitize identifier (very basic)
    public static String sanitizeIdentifier(String name) {
        return name.trim().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }

    public static String generateCreateTableSql(String tableName, LinkedHashMap<String, List<String>> sampleValuesByColumn) {
        String t = sanitizeIdentifier(tableName);
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(t).append(" (");
        List<String> columns = new ArrayList<>();

        for (Map.Entry<String, List<String>> e : sampleValuesByColumn.entrySet()) {
            String col = sanitizeIdentifier(e.getKey());
            String type = inferType(e.getValue());
            columns.add(col + " " + type);
        }
        sb.append(String.join(", ", columns));
        sb.append(");");
        return sb.toString();
    }

    // infer basic SQL type
    private static String inferType(List<String> samples) {
        boolean allInt = true;
        boolean allDouble = true;
        for (String s : samples) {
            if (s == null || s.isEmpty()) continue;
            if (allInt) {
                try { Integer.parseInt(s); } catch (Exception ex) { allInt = false; }
            }
            if (allDouble) {
                try { Double.parseDouble(s); } catch (Exception ex) { allDouble = false; }
            }
        }
        if (allInt && !samples.isEmpty()) return "INTEGER";
        if (allDouble && !samples.isEmpty()) return "DOUBLE PRECISION";
        // naive date check
        for (String s : samples) {
            if (s != null && s.matches("^\\d{4}-\\d{2}-\\d{2}.*")) return "TIMESTAMP";
        }
        return "TEXT";
    }

    // prepare parameterized insert SQL
    public static String prepareInsertSql(String tableName, List<String> columns) {
        String t = sanitizeIdentifier(tableName);
        String cols = columns.stream().map(SchemaGenerator::sanitizeIdentifier).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + t + " (" + cols + ") VALUES (" + placeholders + ")";
    }
}
