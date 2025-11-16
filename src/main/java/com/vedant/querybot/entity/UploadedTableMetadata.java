package com.vedant.querybot.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "uploaded_table_metadata")
public class UploadedTableMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Original filename from the user
    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    // Generated table name in the DB
    @Column(name = "table_name", nullable = false, unique = true)
    private String tableName;

    // JSON representation of columns and types for quick reference
    @Column(name = "columns_json", columnDefinition = "text")
    private String columnsJson;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    public UploadedTableMetadata() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getColumnsJson() { return columnsJson; }
    public void setColumnsJson(String columnsJson) { this.columnsJson = columnsJson; }

    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }

    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
}
