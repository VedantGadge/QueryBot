package com.vedant.querybot.dto;

public class UploadResponseDTO {
    private String tableName;
    private Integer rowCount;
    private String message;

    public UploadResponseDTO() {}

    public UploadResponseDTO(String tableName, Integer rowCount, String message) {
        this.tableName = tableName;
        this.rowCount = rowCount;
        this.message = message;
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
