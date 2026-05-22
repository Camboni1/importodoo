package bprimport.odoo.dto;

public record ProgressDto(
    Long jobId,
    String status,
    int totalRows,
    int processedRows,
    int successRows,
    int errorRows,
    int skippedRows,
    int percent,
    String message,
    boolean done
) {}
