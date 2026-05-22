package bprimport.odoo.service;

import bprimport.odoo.dto.*;
import bprimport.odoo.exception.OdooApiException;
import bprimport.odoo.model.ImportJob;
import bprimport.odoo.model.ImportJobLog;
import bprimport.odoo.model.OdooConnection;
import bprimport.odoo.model.enums.ImportStatus;
import bprimport.odoo.model.enums.LogLevel;
import bprimport.odoo.repository.ImportJobLogRepository;
import bprimport.odoo.repository.ImportJobRepository;
import bprimport.odoo.repository.OdooConnectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ImportJobService {

    private static final Logger log = LoggerFactory.getLogger(ImportJobService.class);

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${app.import.batch-size:100}")
    private int defaultBatchSize;

    @Value("${app.import.test-limit:100}")
    private int testLimit;

    private final ImportJobRepository jobRepo;
    private final ImportJobLogRepository logRepo;
    private final OdooConnectionRepository connRepo;
    private final OdooApiService odooApi;
    private final XlsxParseService xlsxParser;
    private final ImportProgressService progressService;
    private final ObjectMapper mapper;

    /** Cancellation flags keyed by job ID */
    private final Map<Long, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();

    public ImportJobService(ImportJobRepository jobRepo,
                            ImportJobLogRepository logRepo,
                            OdooConnectionRepository connRepo,
                            OdooApiService odooApi,
                            XlsxParseService xlsxParser,
                            ImportProgressService progressService,
                            ObjectMapper mapper) {
        this.jobRepo = jobRepo;
        this.logRepo = logRepo;
        this.connRepo = connRepo;
        this.odooApi = odooApi;
        this.xlsxParser = xlsxParser;
        this.progressService = progressService;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------------------
    // File upload — returns a temp file ID
    // -------------------------------------------------------------------------

    public String saveUploadedFile(MultipartFile file) throws IOException {
        Files.createDirectories(Paths.get(uploadDir));
        String fileId = UUID.randomUUID().toString();
        String ext = getExtension(Objects.requireNonNull(file.getOriginalFilename()));
        Path dest = Paths.get(uploadDir, fileId + ext);
        file.transferTo(dest.toFile());
        return fileId + ext;
    }

    public Path resolveFilePath(String fileId) {
        return Paths.get(uploadDir, fileId);
    }

    // -------------------------------------------------------------------------
    // Create & launch import job
    // -------------------------------------------------------------------------

    @Transactional
    public ImportJob createJob(ImportRequestDto req) throws Exception {
        OdooConnection conn = connRepo.findById(req.connectionId())
            .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + req.connectionId()));

        Path filePath = resolveFilePath(req.fileId());
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("Uploaded file not found: " + req.fileId());
        }

        ImportJob job = new ImportJob();
        job.setFileName(req.fileId());
        job.setSheetName(req.sheetName());
        job.setConnection(conn);
        job.setOdooModel(req.odooModel());
        job.setTestMode(req.testMode());
        job.setTempFilePath(filePath.toString());
        job.setMappingsJson(mapper.writeValueAsString(req.mappings()));
        job.setOptionsJson(mapper.writeValueAsString(req.options()));
        job.setStatus(ImportStatus.PENDING);

        // Get model label
        try {
            List<OdooModelDto> models = odooApi.searchModels(conn, req.odooModel());
            models.stream()
                .filter(m -> m.model().equals(req.odooModel()))
                .findFirst()
                .ifPresent(m -> job.setOdooModelLabel(m.name()));
        } catch (Exception ignored) {}

        // Pre-count rows
        try {
            int rows = xlsxParser.countDataRows(filePath, req.sheetName());
            job.setTotalRows(req.testMode() ? Math.min(rows, testLimit) : rows);
        } catch (Exception e) {
            log.warn("Could not pre-count rows: {}", e.getMessage());
        }

        return jobRepo.save(job);
    }

    // -------------------------------------------------------------------------
    // Run import asynchronously
    // -------------------------------------------------------------------------

    @Async
    public void runAsync(Long jobId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancellationFlags.put(jobId, cancelled);
        try {
            executeImport(jobId, cancelled);
        } finally {
            cancellationFlags.remove(jobId);
        }
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

    public void cancel(Long jobId) {
        AtomicBoolean flag = cancellationFlags.get(jobId);
        if (flag != null) {
            flag.set(true);
        }
        jobRepo.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == ImportStatus.RUNNING || job.getStatus() == ImportStatus.PENDING) {
                job.setStatus(ImportStatus.CANCELLED);
                job.setCompletedAt(LocalDateTime.now());
                jobRepo.save(job);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Core import logic
    // -------------------------------------------------------------------------

    private void executeImport(Long jobId, AtomicBoolean cancelled) {
        ImportJob job = jobRepo.findById(jobId).orElseThrow();
        Path filePath = Paths.get(job.getTempFilePath());

        job.setStatus(ImportStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        jobRepo.save(job);

        publishProgress(job, "Démarrage de l'import...");

        try {
            OdooConnection conn = job.getConnection();
            odooApi.authenticate(conn);

            List<ColumnMappingDto> mappings = mapper.readValue(
                job.getMappingsJson(), new TypeReference<>() {});
            ImportOptionsDto options = mapper.readValue(
                job.getOptionsJson(), new TypeReference<>() {});

            List<String> headers = xlsxParser.getHeaders(filePath, job.getSheetName());
            int batchSize = options.batchSize() > 0 ? options.batchSize() : defaultBatchSize;
            boolean testMode = job.isTestMode();

            // M2O cache: model -> name -> id
            Map<String, Map<String, Optional<Long>>> m2oCache = new ConcurrentHashMap<>();

            // Batching state
            List<Map<String, Object>> batch = new ArrayList<>();
            int[] rowCount = {0};
            int maxRows = testMode ? testLimit : Integer.MAX_VALUE;

            xlsxParser.processRows(filePath, job.getSheetName(), headers, rawRow -> {
                if (cancelled.get()) return;
                rowCount[0]++;
                if (rowCount[0] > maxRows) return;

                // Skip empty lines
                if (options.skipEmptyLines() && isEmptyRow(rawRow)) {
                    job.setSkippedRows(job.getSkippedRows() + 1);
                    return;
                }

                try {
                    Map<String, Object> record = buildRecord(rawRow, mappings, options, conn, m2oCache, jobId, rowCount[0]);
                    if (record != null) {
                        batch.add(record);
                    }
                } catch (Exception e) {
                    job.setErrorRows(job.getErrorRows() + 1);
                    saveLog(jobId, rowCount[0], LogLevel.ERROR, "Row " + rowCount[0] + ": " + e.getMessage());
                    if (options.stopOnError()) {
                        cancelled.set(true);
                    }
                }

                // Flush batch
                if (batch.size() >= batchSize) {
                    flushBatch(job, conn, batch, testMode, jobId);
                    batch.clear();
                    publishProgress(job, "Traitement en cours: " + job.getProcessedRows() + " / " + job.getTotalRows() + " lignes");
                }
            });

            // Flush remaining
            if (!cancelled.get() && !batch.isEmpty()) {
                flushBatch(job, conn, batch, testMode, jobId);
                batch.clear();
            }

            if (cancelled.get()) {
                job.setStatus(ImportStatus.CANCELLED);
            } else {
                job.setStatus(ImportStatus.COMPLETED);
            }

        } catch (Exception e) {
            log.error("Import job {} failed: {}", jobId, e.getMessage(), e);
            job.setStatus(ImportStatus.FAILED);
            job.setErrorSummary(e.getMessage());
            saveLog(jobId, -1, LogLevel.ERROR, "Erreur critique: " + e.getMessage());
        } finally {
            job.setCompletedAt(LocalDateTime.now());
            jobRepo.save(job);
            publishDone(job);
        }
    }

    private void flushBatch(ImportJob job, OdooConnection conn,
                             List<Map<String, Object>> batch,
                             boolean testMode, Long jobId) {
        if (testMode) {
            // In test mode: validate but don't commit
            job.setProcessedRows(job.getProcessedRows() + batch.size());
            job.setSuccessRows(job.getSuccessRows() + batch.size());
            for (int i = 0; i < batch.size(); i++) {
                saveLog(jobId, job.getProcessedRows() - batch.size() + i + 1,
                    LogLevel.INFO, "[TEST] Ligne validée: " + summarize(batch.get(i)));
            }
        } else {
            try {
                List<Long> ids = odooApi.createMany(conn, job.getOdooModel(), batch);
                job.setProcessedRows(job.getProcessedRows() + batch.size());
                job.setSuccessRows(job.getSuccessRows() + ids.size());
                if (ids.size() < batch.size()) {
                    int missing = batch.size() - ids.size();
                    job.setErrorRows(job.getErrorRows() + missing);
                }
            } catch (OdooApiException e) {
                job.setProcessedRows(job.getProcessedRows() + batch.size());
                job.setErrorRows(job.getErrorRows() + batch.size());
                saveLog(jobId, job.getProcessedRows(), LogLevel.ERROR,
                    "Erreur lot: " + e.getMessage());
            }
        }
        jobRepo.save(job);
    }

    private Map<String, Object> buildRecord(Map<String, String> rawRow,
                                             List<ColumnMappingDto> mappings,
                                             ImportOptionsDto options,
                                             OdooConnection conn,
                                             Map<String, Map<String, Optional<Long>>> m2oCache,
                                             Long jobId, int rowNum) {
        Map<String, Object> record = new LinkedHashMap<>();

        for (ColumnMappingDto mapping : mappings) {
            if (mapping.odooField() == null || mapping.odooField().isBlank()) continue;

            String rawValue = rawRow.getOrDefault(mapping.columnName(), "").trim();
            if (rawValue.isBlank()) continue;

            Object value = convertValue(rawValue, mapping, conn, m2oCache, jobId, rowNum);
            if (value != null) {
                record.put(mapping.odooField(), value);
            }
        }

        return record.isEmpty() ? null : record;
    }

    private Object convertValue(String rawValue, ColumnMappingDto mapping,
                                OdooConnection conn,
                                Map<String, Map<String, Optional<Long>>> m2oCache,
                                Long jobId, int rowNum) {
        return switch (mapping.odooFieldType()) {
            case "many2one" -> {
                String relModel = mapping.relatedModel();
                if (relModel == null || relModel.isBlank()) yield rawValue;

                Map<String, Optional<Long>> modelCache = m2oCache
                    .computeIfAbsent(relModel, k -> new ConcurrentHashMap<>());

                Optional<Long> cached = modelCache.get(rawValue.toLowerCase());
                if (cached == null) {
                    Optional<Long> found = odooApi.findByName(conn, relModel, rawValue);
                    if (found.isEmpty() && mapping.createIfNotFound()) {
                        Long newId = odooApi.createSimple(conn, relModel, rawValue);
                        found = newId != null ? Optional.of(newId) : Optional.empty();
                        if (found.isPresent()) {
                            saveLog(jobId, rowNum, LogLevel.INFO,
                                "Créé '" + rawValue + "' dans " + relModel);
                        }
                    }
                    modelCache.put(rawValue.toLowerCase(), found);
                    cached = found;
                }

                if (cached.isEmpty()) {
                    saveLog(jobId, rowNum, LogLevel.WARNING,
                        "many2one non trouvé: '" + rawValue + "' dans " + relModel);
                    yield null;
                }
                yield cached.get();
            }
            case "integer" -> {
                try { yield Long.parseLong(rawValue.replaceAll("[^0-9-]", "")); }
                catch (NumberFormatException e) { yield null; }
            }
            case "float", "monetary" -> {
                try { yield Double.parseDouble(rawValue.replace(",", ".")); }
                catch (NumberFormatException e) { yield null; }
            }
            case "boolean" -> {
                yield rawValue.equalsIgnoreCase("true") ||
                      rawValue.equalsIgnoreCase("oui") ||
                      rawValue.equalsIgnoreCase("yes") ||
                      rawValue.equals("1");
            }
            default -> rawValue;
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isEmptyRow(Map<String, String> row) {
        return row.values().stream().allMatch(String::isBlank);
    }

    private String summarize(Map<String, Object> record) {
        return record.entrySet().stream()
            .limit(3)
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((a, b) -> a + ", " + b)
            .orElse("{}");
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".xlsx";
    }

    @Transactional
    public void saveLog(Long jobId, int row, LogLevel level, String message) {
        logRepo.save(ImportJobLog.of(jobId, row, level, message));
    }

    private void publishProgress(ImportJob job, String message) {
        progressService.publish(job.getId(), new ProgressDto(
            job.getId(),
            job.getStatus().name(),
            job.getTotalRows(),
            job.getProcessedRows(),
            job.getSuccessRows(),
            job.getErrorRows(),
            job.getSkippedRows(),
            job.getProgressPercent(),
            message,
            false
        ));
    }

    private void publishDone(ImportJob job) {
        String message = switch (job.getStatus()) {
            case COMPLETED -> "Import terminé: " + job.getSuccessRows() + " succès, " + job.getErrorRows() + " erreurs";
            case FAILED -> "Import échoué: " + job.getErrorSummary();
            case CANCELLED -> "Import annulé";
            default -> "Terminé";
        };
        progressService.publish(job.getId(), new ProgressDto(
            job.getId(),
            job.getStatus().name(),
            job.getTotalRows(),
            job.getProcessedRows(),
            job.getSuccessRows(),
            job.getErrorRows(),
            job.getSkippedRows(),
            job.getProgressPercent(),
            message,
            true
        ));
    }
}
