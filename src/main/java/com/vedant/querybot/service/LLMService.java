package com.vedant.querybot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

@Service
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    private final String apiKey;
    private final String apiUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public LLMService(
            @Value("${llm.api.key:}") String apiKey,
            @Value("${llm.api.url:https://openrouter.ai/api/v1/chat/completions}") String apiUrl
    ) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
    }

    /* ============================================================
       NATURAL LANGUAGE → SQL
       ============================================================ */
    // Modified to accept availableColumns list and use OpenRouter model + headers
    public String generateSqlFromNl(String nlWithContext, String targetTable, List<String> availableColumns) {

        log.info("=== LLM SQL REQUEST CONTEXT ===\n{}\n===============================", nlWithContext);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("API KEY missing — using fallback SQL.");
            return fallbackSql(targetTable);
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            // OpenRouter / openai model name
            payload.put("model", "openai/gpt-4o-mini");

            /* STRONG, FINAL, FUNCTIONAL SYSTEM PROMPT */
            String systemPrompt =
                    "You are an expert PostgreSQL SQL generator.\n" +
                            "You must generate EXACTLY ONE SQL SELECT query.\n" +
                            "\n" +
                            "STRICT RULES:\n" +
                            "1. Only SELECT allowed. No INSERT/UPDATE/DELETE/ALTER/DROP.\n" +
                            "2. Use ONLY the provided table + column names (never hallucinate).\n" +
                            "3. NEVER return SELECT * unless user explicitly says 'all rows', 'full table', or 'everything'.\n" +
                            "\n" +
                            "4. COMPLEX QUERIES (UNION, SUBQUERIES, JOINS):\n" +
                            "   a) MULTI-ITEM QUERIES (cheapest AND expensive, top 3 AND bottom 2):\n" +
                            "      → Use UNION ALL with subqueries or wrap in parentheses:\n" +
                            "      (SELECT product, amount FROM table ORDER BY amount ASC LIMIT 1)\n" +
                            "      UNION ALL\n" +
                            "      (SELECT product, amount FROM table ORDER BY amount DESC LIMIT 1)\n" +
                            "      \n" +
                            "      OR use subqueries with UNION:\n" +
                            "      SELECT * FROM (SELECT product, amount FROM table ORDER BY amount ASC LIMIT 1) AS cheapest\n" +
                            "      UNION ALL\n" +
                            "      SELECT * FROM (SELECT product, amount FROM table ORDER BY amount DESC LIMIT 1) AS expensive\n" +
                            "\n" +
                            "   b) NESTED QUERIES (e.g., 'show items above average price'):\n" +
                            "      → Use subqueries in WHERE clause:\n" +
                            "      SELECT product, amount FROM table\n" +
                            "      WHERE amount > (SELECT AVG(amount) FROM table)\n" +
                            "      ORDER BY amount DESC\n" +
                            "\n" +
                            "   c) MULTIPLE FILTERS (e.g., 'expensive items by category'):\n" +
                            "      → Use WHERE with multiple conditions or GROUP BY:\n" +
                            "      SELECT category, product, amount FROM table\n" +
                            "      WHERE amount > 5000\n" +
                            "      ORDER BY category, amount DESC\n" +
                            "\n" +
                            "5. RANKING & AGGREGATION:\n" +
                            "   • most, highest, max, maximum, top, expensive, largest, greatest → ORDER BY DESC LIMIT N\n" +
                            "   • least, lowest, min, minimum, cheapest, smallest → ORDER BY ASC LIMIT N\n" +
                            "   • average, avg, mean → SELECT AVG(column)\n" +
                            "   • total, sum → SELECT SUM(column)\n" +
                            "   • count, how many → SELECT COUNT(*)\n" +
                            "   • grouped by category/type → GROUP BY category\n" +
                            "\n" +
                            "6. OUTPUT FORMAT:\n" +
                            "   - Always return ONLY the SQL string. No markdown. No ``` fences. No explanations.\n" +
                            "   - Ensure query is properly formatted and executable in PostgreSQL.\n" +
                            "   - If user asks for multiple distinct queries (e.g., 'AND', 'also', 'plus'), use UNION ALL with subqueries in parentheses.\n" +
                            "   - CRITICAL: When using UNION with ORDER BY, wrap each SELECT in parentheses OR use subqueries.\n" +
                            "   - Never split into multiple SELECT statements separated by semicolons.";

            // include available columns explicitly
            String cols = String.join(", ", availableColumns);
            String userContent = nlWithContext + "\n\nAvailable columns: " + cols + "\n";

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userContent));

            payload.put("messages", messages);
            payload.put("max_tokens", 400);

            String body = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "http://localhost")
                    .header("X-Title", "QueryBot")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("=== LLM RAW RESPONSE ===\n{}\n=========================", response.body());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("LLM returned non-200 status: {}", response.statusCode());
                return fallbackSql(targetTable);
            }

            /* Parse OpenRouter response structure (similar to OpenAI) */
            JsonNode root = mapper.readTree(response.body());
            JsonNode choice = root.path("choices").get(0);
            JsonNode contentNode = choice.path("message").path("content");

            if (contentNode == null || contentNode.isMissingNode()) {
                log.error("LLM response missing 'message.content'");
                return fallbackSql(targetTable);
            }

            String sql = contentNode.asText().trim();
            sql = sql.replaceAll("```sql", "").replaceAll("```", "").trim();

            log.info("=== LLM GENERATED SQL ===\n{}\n=========================", sql);

            /* HARD GUARD: Prevent SELECT * for ranking queries */
            boolean rankingQuery =
                    nlWithContext.toLowerCase().contains("most") ||
                            nlWithContext.toLowerCase().contains("highest") ||
                            nlWithContext.toLowerCase().contains("max") ||
                            nlWithContext.toLowerCase().contains("top") ||
                            nlWithContext.toLowerCase().contains("expensive") ||
                            nlWithContext.toLowerCase().contains("largest") ||
                            nlWithContext.toLowerCase().contains("greatest");

            if (rankingQuery && sql.matches("(?i).*select\\s+\\*.*")) {
                log.warn("LLM returned SELECT * for a ranking query — enforcing fallback ORDER BY.");
                // Try to pick a numeric-looking column from availableColumns
                String fallbackCol = availableColumns.stream()
                        .filter(c -> c.toLowerCase().matches(".*(amount|price|cost|value|total|quantity|qty).*"))
                        .findFirst().orElse("amount");
                return "SELECT " + fallbackCol + " FROM " + targetTable + " ORDER BY " + fallbackCol + " DESC LIMIT 1";
            }

            /* BLOCK SELECT * unless explicitly asked */
            if (!nlWithContext.toLowerCase().contains("all rows") &&
                    sql.matches("(?i).*select\\s+\\*.*")) {
                log.warn("Blocked SELECT * (user did not ask for full table)");
                return fallbackSql(targetTable);
            }

            // Ensure only one SELECT statement
            // sanitize BOMs and control characters, then take only the first statement before any semicolon
            sql = sql.replace("\uFEFF", "").trim();
            int sem = sql.indexOf(';');
            if (sem >= 0) {
                log.warn("LLM returned multiple statements; taking first");
                sql = sql.substring(0, sem).trim();
            }

            return sql;

        } catch (Exception ex) {
            log.error("LLM SQL generation failed", ex);
            return fallbackSql(targetTable);
        }
    }

    private String fallbackSql(String table) {
        return "SELECT * FROM " + table + " LIMIT 50";
    }


    /* ============================================================
       SQL RESULT ROWS → Natural-language summary
       ============================================================ */
    // Modified to accept conversationContext to provide follow-up aware summaries
    public String summarizeResult(String originalQuestion, String tableName, List<Map<String, Object>> rows, String conversationContext, String factSnippet, boolean allowFreeform) {

        try {
            if (apiKey == null || apiKey.isBlank()) {
                if (rows.isEmpty()) return "No rows found.";
                return "Found " + rows.size() + " matching rows.";
            }

            // Build readable rows text for LLM (avoid relying on JSON which can confuse the model)
            String rowsText = "";
            if (rows != null && !rows.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rows.size(); i++) {
                    Map<String, Object> row = rows.get(i);
                    sb.append("Row ").append(i + 1).append(": ");
                    List<String> parts = new ArrayList<>();
                    for (Map.Entry<String, Object> e : row.entrySet()) {
                        parts.add(e.getKey() + "=" + String.valueOf(e.getValue()));
                    }
                    sb.append(String.join(", ", parts)).append("\n");
                }
                rowsText = sb.toString();
            } else {
                rowsText = "[no rows returned]";
            }

             Map<String, Object> payload = new HashMap<>();
             payload.put("model", "openai/gpt-4o-mini");

             String system;
             String userContent = "Conversation history:\n" + conversationContext + "\n\nUser question: " + originalQuestion + "\n\nTable: " + tableName + "\n\nRows returned (" + (rows == null ? 0 : rows.size()) + "):\n" + rowsText + "\nFacts: " + (factSnippet == null ? "" : factSnippet) + "\n";

             if (allowFreeform) {
                 system = "You are a helpful data analyst who can talk conversationally. You should reference the provided Rows and Facts when relevant, but you MAY answer in a natural, opinionated style. Do NOT invent facts not present in Rows/Facts. CRITICAL: Do NOT ask the user questions or ask them to provide more information. Just analyze and summarize the data you have been provided. Keep answers focused on insights from the data.";
                userContent += "\nInstructions: Answer conversationally using only the provided Rows and Facts. Do NOT invent data. CRITICAL: Do NOT ask the user questions back. Do NOT ask for clarifications or more information. Simply provide analysis and insights based on the data provided. Always provide a natural language summary, never just echo back the facts or raw data.";
              } else {
                 system = "You are a careful data analyst. STRICT INSTRUCTIONS: Use ONLY the provided facts and rows. Do NOT hallucinate additional rows, columns, or values. Do NOT ask the user questions. Just analyze the data factually. If data is missing or ambiguous, simply state that fact without asking for more info. Do NOT output SQL or JSON. Keep answers concise and data-focused. Always synthesize the data into natural language, never just return the raw facts.";
                userContent += "\nInstructions: Respond strictly using ONLY the Rows and Facts provided. Do NOT invent data. CRITICAL: Do NOT ask the user any questions back. Do not ask for clarifications. Simply provide analysis based on what you have. IMPORTANT: Always respond with a natural language summary or analysis. Never output the FACTS string or raw row data back to the user. Synthesize the information into a readable answer.";
              }

           log.info("=== SUMMARY PROMPT TO LLM ===\n{}\n=== SYSTEM ===\n{}", userContent, system);

             List<Map<String, String>> messages = List.of(
                     Map.of("role", "system", "content", system),
                     Map.of("role", "user", "content", userContent)
             );

             payload.put("messages", messages);
             payload.put("max_tokens", 300);

             String body = mapper.writeValueAsString(payload);

             HttpRequest req = HttpRequest.newBuilder()
                     .uri(URI.create(apiUrl))
                     .header("Authorization", "Bearer " + apiKey)
                     .header("Content-Type", "application/json")
                     .header("HTTP-Referer", "http://localhost")
                     .header("X-Title", "QueryBot")
                     .POST(HttpRequest.BodyPublishers.ofString(body))
                     .build();

             HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

             log.info("=== LLM SUMMARY RAW RESPONSE ===\n{}\n===============================", resp.body());

             if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                 return null;
             }

             JsonNode root = mapper.readTree(resp.body());
             JsonNode contentNode = root.path("choices").get(0).path("message").path("content");

             return contentNode.asText().trim();

        } catch (Exception e) {
            log.error("LLM summary generation failed", e);
            return null;
        }
    }
}
