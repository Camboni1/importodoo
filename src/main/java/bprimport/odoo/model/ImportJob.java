package bprimport.odoo.model;

import bprimport.odoo.model.enums.ImportStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "import_jobs")
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String sheetName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private OdooConnection connection;

    @Column(nullable = false)
    private String odooModel;

    private String odooModelLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportStatus status = ImportStatus.PENDING;

    private int totalRows;
    private int processedRows;
    private int successRows;
    private int errorRows;
    private int skippedRows;

    private boolean testMode;

    /** JSON column mappings */
    @Column(columnDefinition = "TEXT")
    private String mappingsJson;

    /** JSON import options */
    @Column(columnDefinition = "TEXT")
    private String optionsJson;

    /** Temporary file path for the uploaded XLSX */
    @Column(length = 512)
    private String tempFilePath;

    @Column(columnDefinition = "TEXT")
    private String errorSummary;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    /** Duration in seconds, computed on the fly */
    @Transient
    public Long getDurationSeconds() {
        if (startedAt == null) return null;
        var end = completedAt != null ? completedAt : LocalDateTime.now();
        return java.time.Duration.between(startedAt, end).getSeconds();
    }

    @Transient
    public int getProgressPercent() {
        if (totalRows <= 0) return 0;
        return (int) Math.min(100, (processedRows * 100L / totalRows));
    }

    // --- Getters / Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getSheetName() { return sheetName; }
    public void setSheetName(String sheetName) { this.sheetName = sheetName; }

    public OdooConnection getConnection() { return connection; }
    public void setConnection(OdooConnection connection) { this.connection = connection; }

    public String getOdooModel() { return odooModel; }
    public void setOdooModel(String odooModel) { this.odooModel = odooModel; }

    public String getOdooModelLabel() { return odooModelLabel; }
    public void setOdooModelLabel(String odooModelLabel) { this.odooModelLabel = odooModelLabel; }

    public ImportStatus getStatus() { return status; }
    public void setStatus(ImportStatus status) { this.status = status; }

    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }

    public int getProcessedRows() { return processedRows; }
    public void setProcessedRows(int processedRows) { this.processedRows = processedRows; }

    public int getSuccessRows() { return successRows; }
    public void setSuccessRows(int successRows) { this.successRows = successRows; }

    public int getErrorRows() { return errorRows; }
    public void setErrorRows(int errorRows) { this.errorRows = errorRows; }

    public int getSkippedRows() { return skippedRows; }
    public void setSkippedRows(int skippedRows) { this.skippedRows = skippedRows; }

    public boolean isTestMode() { return testMode; }
    public void setTestMode(boolean testMode) { this.testMode = testMode; }

    public String getMappingsJson() { return mappingsJson; }
    public void setMappingsJson(String mappingsJson) { this.mappingsJson = mappingsJson; }

    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }

    public String getTempFilePath() { return tempFilePath; }
    public void setTempFilePath(String tempFilePath) { this.tempFilePath = tempFilePath; }

    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
