 package com.vedant.querybot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedant.querybot.entity.UploadedTableMetadata;
import com.vedant.querybot.repository.UploadedTableMetadataRepository;
import com.vedant.querybot.util.FileParser;
import com.vedant.querybot.util.SchemaGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class FileService {

    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    private final JdbcTemplate jdbcTemplate;
    private final UploadedTableMetadataRepository metadataRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileService(JdbcTemplate jdbcTemplate, UploadedTableMetadataRepository metadataRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.metadataRepository = metadataRepository;
    }

    public UploadedTableMetadata processUpload(MultipartFile file) throws IOException {
        final List<Map<String, String>> rows = Optional.ofNullable(FileParser.parse(file))
                .orElse(Collections.emptyList());

        String base = Optional.ofNullable(file.getOriginalFilename()).orElse("upload");
        base = base.replaceAll("\\.[^.]*$", "");
        String tableName = SchemaGenerator.sanitizeIdentifier(base + "_" + System.currentTimeMillis());

        // Build samples from original headers (preserve insertion order)
        LinkedHashMap<String, List<String>> samplesByOriginal = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            for (Map.Entry<String, String> e : r.entrySet()) {
                samplesByOriginal.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
        }
        if (samplesByOriginal.isEmpty()) {
            samplesByOriginal.put("col1", Collections.singletonList(""));
        }

        // Map original header -> sanitized column name (safe for SQL)
        List<String> originalOrdered = new ArrayList<>(samplesByOriginal.keySet());
        List<String> safeColumns = new ArrayList<>(originalOrdered.size());
        Map<String, String> originalToSafe = new LinkedHashMap<>();
        for (String orig : originalOrdered) {
            String safe = sanitizeColumnName(orig);
            // ensure uniqueness
            String candidate = safe;
            int suffix = 1;
            while (safeColumns.contains(candidate)) {
                candidate = safe + "_" + (++suffix);
            }
            safeColumns.add(candidate);
            originalToSafe.put(orig, candidate);
        }

        // Build samples map keyed by safe column names for schema inference
        LinkedHashMap<String, List<String>> samplesBySafe = new LinkedHashMap<>();
        for (String orig : originalOrdered) {
            samplesBySafe.put(originalToSafe.get(orig), samplesByOriginal.get(orig));
        }

        // infer SQL types per column
        List<String> inferredTypes = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : samplesBySafe.entrySet()) {
            inferredTypes.add(inferSqlType(e.getValue()));
        }

        // Build and execute CREATE TABLE using quoted identifiers (so exact names match INSERT)
        String createSql = buildCreateTableSql(tableName, samplesBySafe);
        logger.debug("CREATE SQL: {}", createSql);
        try {
            jdbcTemplate.execute(createSql);
        } catch (Exception ex) {
            logger.error("CREATE TABLE failed", ex);
            throw new IOException("Upload failed: CREATE TABLE error: " + ex.getMessage(), ex);
        }

        // Build INSERT SQL with explicit quoting to ensure identifiers match exactly
        String insertSql = buildInsertSql(tableName, safeColumns);
        logger.debug("INSERT SQL: {}", insertSql);

        final int rowsCount = rows.size();
        if (rowsCount > 0) {
            // validate table column count before attempting insert
            try {
                int createdColCount = fetchTableColumnCount(tableName);
                if (createdColCount != safeColumns.size()) {
                    String msg = String.format("created table columns=%d but insert columns=%d",
                            createdColCount, safeColumns.size());
                    logger.error(msg + " - table: {}", tableName);
                    throw new IOException("Upload failed: CREATE/INSERT column count mismatch: " + msg);
                }
            } catch (IOException ioe) {
                throw ioe;
            } catch (Exception ex) {
                logger.error("Failed to validate created table columns", ex);
                throw new IOException("Upload failed: unable to validate created table columns: " + ex.getMessage(), ex);
            }

            final List<String> finalOriginalOrdered = Collections.unmodifiableList(originalOrdered);
            final List<String> finalInferredTypes = Collections.unmodifiableList(inferredTypes);

            try {
                jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Map<String, String> row = rows.get(i);
                        for (int c = 0; c < finalOriginalOrdered.size(); c++) {
                            String origHeader = finalOriginalOrdered.get(c);
                            String val = row.get(origHeader);
                            setPreparedValue(ps, c + 1, val, finalInferredTypes.get(c));
                        }
                    }

                    @Override
                    public int getBatchSize() {
                        return rowsCount;
                    }
                });
            } catch (Exception ex) {
                logger.error("INSERT batch failed", ex);
                throw new IOException("Upload failed: INSERT error: " + ex.getMessage(), ex);
            }
        } else {
            logger.info("No rows to insert for upload {}", file.getOriginalFilename());
        }

        UploadedTableMetadata meta = new UploadedTableMetadata();
        meta.setOriginalFilename(file.getOriginalFilename());
        meta.setTableName(tableName);
        meta.setRowCount(rowsCount);
        meta.setColumnsJson(mapper.writeValueAsString(originalToSafe)); // store mapping original->safe
        return metadataRepository.save(meta);
    }

    // Build CREATE TABLE with quoted identifiers and simple type inference
    private String buildCreateTableSql(String table, Map<String, List<String>> samplesBySafe) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(quoteIdentifier(table)).append(" (");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : samplesBySafe.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            String col = e.getKey();
            String type = inferSqlType(e.getValue());
            sb.append(quoteIdentifier(col)).append(" ").append(type);
        }
        sb.append(")");
        return sb.toString();
    }

    // Simple heuristics to infer SQL column type from sample values
    private String inferSqlType(List<String> samples) {
        boolean anyNonEmpty = false;
        boolean allInt = true;
        boolean allDouble = true;
        boolean allDate = true;
        boolean allTimestamp = true;

        Pattern intPat = Pattern.compile("^-?\\d+$");
        Pattern doublePat = Pattern.compile("^-?\\d+(\\.\\d+)?$");
        Pattern datePat = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
        Pattern tsPat = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2})?.*");

        for (String s : samples) {
            if (s == null || s.isBlank()) continue;
            anyNonEmpty = true;
            String v = s.trim();
            if (!intPat.matcher(v).matches()) allInt = false;
            if (!doublePat.matcher(v).matches()) allDouble = false;
            if (!datePat.matcher(v).matches()) allDate = false;
            if (!tsPat.matcher(v).matches()) allTimestamp = false;
            if (!allInt && !allDouble && !allDate && !allTimestamp) break;
        }

        if (!anyNonEmpty) return "text";
        if (allInt) return "bigint";
        if (allDouble) return "double precision";
        if (allTimestamp) return "timestamp";
        if (allDate) return "date";
        return "text";
    }

    // Build INSERT SQL and quote identifiers (Postgres-style double quotes).
    private String buildInsertSql(String table, List<String> cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(quoteIdentifier(table)).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIdentifier(cols.get(i)));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        sb.append(")");
        return sb.toString();
    }

    private String quoteIdentifier(String id) {
        if (id == null) return "\"\"";
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    // Query information_schema to confirm created table column count
    private int fetchTableColumnCount(String table) {
        String sql = "SELECT count(*) FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name ILIKE ?";
        Integer cnt = jdbcTemplate.queryForObject(sql, Integer.class, table);
        return cnt == null ? 0 : cnt;
    }

    // Set PreparedStatement value using inferred SQL type
    private void setPreparedValue(PreparedStatement ps, int idx, String val, String sqlType) throws SQLException {
        if (val == null || val.isBlank()) {
            ps.setObject(idx, null);
            return;
        }
        String v = val.trim();
        try {
            if (sqlType != null) {
                String t = sqlType.toLowerCase(Locale.ROOT);
                if (t.contains("bigint") || t.contains("int")) {
                    ps.setLong(idx, Long.parseLong(v));
                    return;
                } else if (t.contains("double") || t.contains("numeric") || t.contains("real")) {
                    ps.setDouble(idx, Double.parseDouble(v));
                    return;
                } else if (t.contains("date") && !t.contains("timestamp")) {
                    ps.setDate(idx, Date.valueOf(v)); // expects yyyy-MM-dd
                    return;
                } else if (t.contains("timestamp")) {
                    // try parse commonly used timestamp formats acceptable by Timestamp.valueOf
                    ps.setTimestamp(idx, Timestamp.valueOf(v));
                    return;
                }
            }
        } catch (Exception e) {
            logger.debug("Type conversion failed for value='{}' to type='{}' - falling back to string", v, sqlType, e);
        }
        ps.setString(idx, v);
    }

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]");
    private static final Set<String> RESERVED = Set.of(
            "select", "insert", "update", "delete", "table", "date", "user", "order", "group", "value"
    );

    private String sanitizeColumnName(String header) {
        if (header == null || header.isBlank()) return "col";
        String candidate = NON_ALNUM.matcher(header.trim().toLowerCase()).replaceAll("_");
        if (candidate.isEmpty()) candidate = "col";
        if (Character.isDigit(candidate.charAt(0))) candidate = "c_" + candidate;
        if (RESERVED.contains(candidate)) candidate = candidate + "_col";
        candidate = candidate.replaceAll("_+", "_");
        candidate = candidate.replaceAll("^_+|_+$", "");
        if (candidate.isEmpty()) candidate = "col";
        return SchemaGenerator.sanitizeIdentifier(candidate);
    }
}
