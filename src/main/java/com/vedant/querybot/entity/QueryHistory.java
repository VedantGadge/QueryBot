package com.vedant.querybot.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "query_history")
public class QueryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="nl_query", columnDefinition = "text", nullable = false)
    private String nlQuery;

    @Column(name="generated_sql", columnDefinition = "text", nullable = false)
    private String generatedSql;

    // Small preview or JSON of results (could be truncated)
    @Column(name="result_preview", columnDefinition = "text")
    private String resultPreview;

    @Column(name="executed_at", nullable = false)
    private Instant executedAt = Instant.now();

    public QueryHistory() {}

    // Getters / setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNlQuery() { return nlQuery; }
    public void setNlQuery(String nlQuery) { this.nlQuery = nlQuery; }

    public String getGeneratedSql() { return generatedSql; }
    public void setGeneratedSql(String generatedSql) { this.generatedSql = generatedSql; }

    public String getResultPreview() { return resultPreview; }
    public void setResultPreview(String resultPreview) { this.resultPreview = resultPreview; }

    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
}
