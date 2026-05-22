package bprimport.odoo.model;

import bprimport.odoo.model.enums.LogLevel;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "import_job_logs", indexes = {
    @Index(name = "idx_log_job_id", columnList = "job_id"),
    @Index(name = "idx_log_level", columnList = "level")
})
public class ImportJobLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    private int rowNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LogLevel level;

    @Column(columnDefinition = "TEXT")
    private String message;

    /** Raw row data (JSON) for debugging */
    @Column(columnDefinition = "TEXT")
    private String rowData;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public static ImportJobLog of(Long jobId, int row, LogLevel level, String message) {
        var log = new ImportJobLog();
        log.jobId = jobId;
        log.rowNumber = row;
        log.level = level;
        log.message = message;
        return log;
    }

    public Long getId() { return id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }
    public LogLevel getLevel() { return level; }
    public void setLevel(LogLevel level) { this.level = level; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getRowData() { return rowData; }
    public void setRowData(String rowData) { this.rowData = rowData; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
