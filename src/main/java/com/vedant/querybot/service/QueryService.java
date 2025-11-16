package com.vedant.querybot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedant.querybot.entity.QueryHistory;
import com.vedant.querybot.entity.UploadedTableMetadata;
import com.vedant.querybot.repository.QueryHistoryRepository;
import com.vedant.querybot.repository.UploadedTableMetadataRepository;
import com.vedant.querybot.util.SQLValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final LLMService llmService;
    private final JdbcTemplate jdbcTemplate;
    private final QueryHistoryRepository historyRepository;
    private final UploadedTableMetadataRepository metadataRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    // In-memory per-session conversation memory (sessionId -> deque of last messages)
    // We store messages as simple immutable records with role(user/assistant) and content
    private final Map<String, Deque<ConvMessage>> sessionMemory = new ConcurrentHashMap<>();

    private record ConvMessage(String role, String content) {}

    public QueryService(
            LLMService llmService,
            JdbcTemplate jdbcTemplate,
            QueryHistoryRepository historyRepository,
            UploadedTableMetadataRepository metadataRepository
    ) {
        this.llmService = llmService;
        this.jdbcTemplate = jdbcTemplate;
        this.historyRepository = historyRepository;
        this.metadataRepository = metadataRepository;
    }


    /* ============================================================
       MAIN: NL → SQL → EXECUTE → SUMMARY
       ============================================================ */
    // Changed signature to accept sessionId for per-session conversational memory
    public QueryResult executeNlQueryWithSummary(String nlQuery, String requestedTable, String sessionId) {

        String latestTable = resolveLatestTableName();
        if (latestTable == null) {
            throw new IllegalStateException("No uploaded table available");
        }

        if (requestedTable != null &&
                !requestedTable.isBlank() &&
                !normalize(requestedTable).equals(normalize(latestTable))) {

            throw new IllegalArgumentException(
                    "You can only query the latest uploaded table: " + latestTable
            );
        }

        /* ------------------------------------------------------------
           Build STRONG Column Context for the LLM
           ------------------------------------------------------------ */
        Optional<UploadedTableMetadata> meta = getLatestMetadata();
        String columnsContext = "(No columns available)\n";

        List<String> availableColumns = new ArrayList<>();

        if (meta.isPresent()) {
            try {
                LinkedHashMap<String, String> colMap =
                        mapper.readValue(meta.get().getColumnsJson(),
                                new TypeReference<LinkedHashMap<String, String>>() {});

                // MUCH STRONGER SCHEMA DESCRIPTION
                StringBuilder sb = new StringBuilder();
                sb.append("This table contains the following columns:\n");

                for (String col : colMap.values()) {
                    sb.append(" - ").append(col).append("\n");
                    availableColumns.add(col);
                }

                sb.append("\nWhen answering the question, use ONLY these columns.\n");

                columnsContext = sb.toString();

            } catch (Exception e) {
                log.warn("Failed to parse columns metadata", e);
            }
        }

        /* ------------------------------------------------------------
           Conversation memory: add the user's current question to memory and include last 10 messages in the prompt
           ------------------------------------------------------------ */
        if (sessionId != null && !sessionId.isBlank()) {
            addToMemory(sessionId, "user", nlQuery);
        }

        // Build a filtered conversation context for LLM prompts that includes only user messages and explicit FACTS
        String conversationContext = buildPromptConversationContext(sessionId);

        /* ------------------------------------------------------------
           Final LLM prompt must include: table name, available columns, conversation memory, current question
           ------------------------------------------------------------ */
        String prompt =
                "Table name: " + latestTable + "\n" +
                        columnsContext +
                        "Conversation history:\n" + conversationContext + "\n" +
                        "User question: " + nlQuery + "\n";

        log.info("=== LLM PROMPT SENT ===\n{}\n========================", prompt);

        /* ------------------------------------------------------------
           Generate SQL via LLM
           ------------------------------------------------------------ */
        String sql = llmService.generateSqlFromNl(prompt, latestTable, availableColumns);

        log.info("=== SQL RECEIVED FROM LLM ===\n{}\n=====================", sql);

        if (!SQLValidator.isSelectOnly(sql)) {
            throw new IllegalArgumentException("Only SELECT queries allowed");
        }

        if (!referencesOnlyTable(sql, latestTable)) {
            throw new IllegalArgumentException("SQL references unauthorized tables");
        }

        /* ------------------------------------------------------------
           Execute SQL
           ------------------------------------------------------------ */
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        log.info("=== QUERY EXECUTED === {} rows returned", rows.size());

        /* ------------------------------------------------------------
           Build deterministic facts and summarize results using LLM
           (build fact snippet first to ground the summarizer and avoid hallucinations)
           ------------------------------------------------------------ */
        String factSnippet = buildFactSnippet(rows);
        // Decide whether the user's question expects a conversational/opinionated reply
        boolean allowFreeform = isConversational(nlQuery);
        String summary = llmService.summarizeResult(nlQuery, latestTable, rows, conversationContext, factSnippet, allowFreeform);

        /* ------------------------------------------------------------
           Save history
           ------------------------------------------------------------ */
        try {
            QueryHistory h = new QueryHistory();
            h.setNlQuery(nlQuery);
            h.setGeneratedSql(sql);
            h.setResultPreview(mapper.writeValueAsString(rows.size() > 50
                    ? rows.subList(0, 50)
                    : rows));

            historyRepository.save(h);
        } catch (Exception ignored) {}

        /* ------------------------------------------------------------
           Store assistant summary back into session memory (keep only last 10 messages)
           ------------------------------------------------------------ */
        if (sessionId != null && !sessionId.isBlank()) {
            addToMemory(sessionId, "assistant", summary == null ? "" : summary);
            // also store explicit, structured facts the assistant used (helps avoid hallucinations)
            if (factSnippet != null && !factSnippet.isBlank()) {
                addToMemory(sessionId, "assistant", "FACTS: " + factSnippet);
            }
        }

        return new QueryResult(sql, rows, summary);
    }

    /* ============================================================
       SUPPORT CLASSES
       ============================================================ */

    public record QueryResult(String sql, List<Map<String, Object>> rows, String nlAnswer) {}

    private String resolveLatestTableName() {
        return getLatestMetadata()
                .map(UploadedTableMetadata::getTableName)
                .orElse(null);
    }

    private Optional<UploadedTableMetadata> getLatestMetadata() {
        return metadataRepository.findAll()
                .stream()
                .max(Comparator.comparingLong(m -> Optional.ofNullable(m.getId()).orElse(0L)));
    }

    /* ============================================================
       Conversation memory helpers
       ============================================================ */
    // Add a message to session memory; keep only last 10 messages
    public void addToMemory(String sessionId, String role, String content) {
        if (sessionId == null) return;
        sessionMemory.compute(sessionId, (k, dq) -> {
            if (dq == null) dq = new ArrayDeque<>();
            dq.addLast(new ConvMessage(role, content));
            while (dq.size() > 10) dq.removeFirst();
            return dq;
        });
    }

    // Return formatted conversation context (oldest -> newest)
    public String buildConversationContext(String sessionId) {
        if (sessionId == null) return "";
        Deque<ConvMessage> dq = sessionMemory.get(sessionId);
        if (dq == null || dq.isEmpty()) return "(no prior messages)\n";
        StringBuilder sb = new StringBuilder();
        for (ConvMessage m : dq) {
            sb.append(m.role()).append(": ").append(m.content()).append("\n");
        }
        return sb.toString();
    }

    // Return conversation messages as a list of maps [{role:, content:}, ...] oldest->newest
    public List<Map<String, String>> getConversation(String sessionId) {
        List<Map<String, String>> out = new ArrayList<>();
        if (sessionId == null) return out;
        Deque<ConvMessage> dq = sessionMemory.get(sessionId);
        if (dq == null || dq.isEmpty()) return out;
        for (ConvMessage m : dq) {
            Map<String, String> mm = new HashMap<>();
            mm.put("role", m.role());
            mm.put("content", m.content());
            out.add(mm);
        }
        return out;
    }

    /* Validate table references */
    private boolean referencesOnlyTable(String sql, String table) {
        String target = normalize(table);
        Pattern p = Pattern.compile("(?i)\\b(from|join)\\s+\"?([a-zA-Z0-9_]+)\"?");
        Matcher m = p.matcher(sql);

        while (m.find()) {
            String found = normalize(m.group(2));
            if (!found.equals(target)) return false;
        }
        return sql.toLowerCase().contains(target.toLowerCase());
    }

    private String normalize(String id) {
        return id == null ? "" : id.replace("\"", "").trim().toLowerCase();
    }

    // Create a short, deterministic fact snippet from the result rows (based on first row)
    private String buildFactSnippet(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "(no rows)";
        StringBuilder sb = new StringBuilder();
        // include up to 5 rows, enumerated, with preferred fields first
        String[] preferred = new String[] {"product", "item", "name", "amount", "price", "cost", "quantity", "qty", "customer"};
        int limit = Math.min(rows.size(), 5);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> r = rows.get(i);
            sb.append("ROW").append(i+1).append(": ");
            // add preferred cols
            List<String> parts = new ArrayList<>();
            for (String p : preferred) {
                if (r.containsKey(p) && r.get(p) != null) {
                    parts.add(p + "=" + String.valueOf(r.get(p)));
                }
            }
            // then add up to 3 more columns per row
            int added = parts.size();
            for (Map.Entry<String, Object> e : r.entrySet()) {
                if (added >= 6) break; // cap total fields per row to avoid huge facts
                String k = e.getKey();
                Object v = e.getValue();
                if (v == null) continue;
                // skip if already included
                boolean already = false;
                for (String p : preferred) if (p.equals(k)) { already = true; break; }
                if (already) continue;
                parts.add(k + "=" + String.valueOf(v));
                added++;
            }
            sb.append(String.join("; ", parts));
            if (i < limit-1) sb.append(" | ");
        }
        return sb.toString();
    }

    // Build a filtered conversation context for LLM prompts that includes only user messages and explicit FACTS
    private String buildPromptConversationContext(String sessionId) {
        if (sessionId == null) return "";
        Deque<ConvMessage> dq = sessionMemory.get(sessionId);
        if (dq == null || dq.isEmpty()) return "(no prior messages)\n";
        StringBuilder sb = new StringBuilder();
        for (ConvMessage m : dq) {
            if (m.role.equals("user") || (m.role.equals("assistant") && m.content.startsWith("FACTS:"))) {
                sb.append(m.role()).append(": ").append(m.content()).append("\n");
            }
        }
        return sb.toString();
    }

    // Heuristic to determine if the query is conversational in nature
    private boolean isConversational(String query) {
        if (query == null) return false;
        String q = query.trim().toLowerCase();

        // Common conversational / opinion phrases (including abbreviations & slang)
        String[] convPhrases = new String[]{
                "do you think", "do u think", "what do you think", "what do u think",
                "i feel", "i think", "u think", "think abt", "think about",
                "opinion", "thoughts", "would you", "would u", "should",
                "is it overpriced", "overpriced", "pricey", "pricey",
                "what about", "what abt", "do you recommend", "recommend",
                "how about", "how abt", "your thoughts", "ur thoughts",
                "abt the", "abt ur", "about the"
        };
        for (String p : convPhrases) {
            if (q.contains(p)) return true;
        }

        // Questions are usually conversational
        if (q.endsWith("?") || q.contains("?")) return true;

        return false;
    }
}
