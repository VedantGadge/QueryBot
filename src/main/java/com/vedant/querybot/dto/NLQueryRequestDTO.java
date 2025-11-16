package com.vedant.querybot.dto;

public class NLQueryRequestDTO {
    private String nlQuery;
    private String targetTable; // optional hint

    public NLQueryRequestDTO() {}

    public String getNlQuery() { return nlQuery; }
    public void setNlQuery(String nlQuery) { this.nlQuery = nlQuery; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
}
