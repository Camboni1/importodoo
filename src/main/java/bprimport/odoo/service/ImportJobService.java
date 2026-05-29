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
import java.nio.charset.StandardCharsets;
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

    /** Fields checked for uniqueness conflicts in test mode */
    private static final Set<String> UNIQUE_CHECK_FIELDS =
        Set.of("barcode", "default_code", "ref", "code", "ean13", "ean8");

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${app.import.batch-size:100}")
    private int defaultBatchSize;

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

        // Pre-count rows (always the full file count, even in test mode)
        try {
            int rows = xlsxParser.countDataRows(filePath, req.sheetName());
            job.setTotalRows(rows);
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
        boolean testMode = job.isTestMode();

        // TESTING status for test runs so the UI can distinguish them
        job.setStatus(testMode ? ImportStatus.TESTING : ImportStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        jobRepo.save(job);

        publishProgress(job, testMode ? "Analyse du fichier complet..." : "Démarrage de l'import...");

        try {
            OdooConnection conn = job.getConnection();
            odooApi.authenticate(conn);

            List<ColumnMappingDto> mappings = mapper.readValue(
                job.getMappingsJson(), new TypeReference<>() {});
            ImportOptionsDto options = mapper.readValue(
                job.getOptionsJson(), new TypeReference<>() {});

            List<String> headers = xlsxParser.getHeaders(filePath, job.getSheetName());
            int batchSize = options.batchSize() > 0 ? options.batchSize() : defaultBatchSize;

            // M2O cache: model -> name -> id
            Map<String, Map<String, Optional<Long>>> m2oCache = new ConcurrentHashMap<>();

            // Internal duplicate tracker for test mode: field -> value -> first row number
            Map<String, Map<Object, Integer>> seenByField = new HashMap<>();

            // Batching state
            List<Map<String, Object>> batch = new ArrayList<>();
            int[] rowCount = {0};

            xlsxParser.processRows(filePath, job.getSheetName(), headers, rawRow -> {
                if (cancelled.get()) return;
                rowCount[0]++;

                // Skip empty lines
                if (options.skipEmptyLines() && isEmptyRow(rawRow)) {
                    job.setSkippedRows(job.getSkippedRows() + 1);
                    return;
                }

                try {
                    Map<String, Object> record = buildRecord(rawRow, mappings, options, conn, m2oCache, jobId, rowCount[0], testMode);
                    if (record != null) {
                        // In test mode: detect internal duplicates within the file
                        if (testMode) {
                            detectInternalDuplicates(record, seenByField, jobId, rowCount[0]);
                        }
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
                    flushBatch(job, conn, batch, testMode, jobId, mappings);
                    batch.clear();
                    publishProgress(job, "Traitement en cours: " + job.getProcessedRows() + " / " + job.getTotalRows() + " lignes");
                }
            });

            // Flush remaining
            if (!cancelled.get() && !batch.isEmpty()) {
                flushBatch(job, conn, batch, testMode, jobId, mappings);
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
                             boolean testMode, Long jobId,
                             List<ColumnMappingDto> mappings) {
        if (testMode) {
            int baseRow = job.getProcessedRows() + 1;
            job.setProcessedRows(job.getProcessedRows() + batch.size());

            // Check each unique field against Odoo to detect conflicts
            Set<Integer> conflictIndices = new HashSet<>();
            for (ColumnMappingDto m : mappings) {
                if (!UNIQUE_CHECK_FIELDS.contains(m.odooField())) continue;

                List<String> vals = new ArrayList<>();
                for (Map<String, Object> rec : batch) {
                    Object v = rec.get(m.odooField());
                    if (v != null && !v.toString().isBlank()) vals.add(v.toString());
                }
                if (vals.isEmpty()) continue;

                try {
                    Map<String, Long> conflicts = odooApi.findExistingByField(
                        conn, job.getOdooModel(), m.odooField(), vals);
                    for (int i = 0; i < batch.size(); i++) {
                        Object val = batch.get(i).get(m.odooField());
                        if (val != null && conflicts.containsKey(val.toString())) {
                            conflictIndices.add(i);
                            saveLog(jobId, baseRow + i, LogLevel.ERROR,
                                "Conflit Odoo: " + m.odooField() + "='" + val
                                + "' existe déjà (id Odoo=" + conflicts.get(val.toString()) + ")");
                        }
                    }
                } catch (Exception e) {
                    log.debug("Odoo conflict check {}.{} failed: {}", job.getOdooModel(), m.odooField(), e.getMessage());
                }
            }

            job.setErrorRows(job.getErrorRows() + conflictIndices.size());
            job.setSuccessRows(job.getSuccessRows() + (batch.size() - conflictIndices.size()));
        } else {
            try {
                List<Long> ids = odooApi.createMany(conn, job.getOdooModel(), batch);
                job.setProcessedRows(job.getProcessedRows() + batch.size());
                job.setSuccessRows(job.getSuccessRows() + ids.size());
                if (ids.size() < batch.size()) {
                    job.setErrorRows(job.getErrorRows() + (batch.size() - ids.size()));
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

    /** Detect values that appear more than once in the file for known unique fields. */
    private void detectInternalDuplicates(Map<String, Object> record,
                                           Map<String, Map<Object, Integer>> seenByField,
                                           Long jobId, int rowNum) {
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (value == null || !UNIQUE_CHECK_FIELDS.contains(field)) continue;
            Map<Object, Integer> seen = seenByField.computeIfAbsent(field, k -> new LinkedHashMap<>());
            Integer firstRow = seen.putIfAbsent(value, rowNum);
            if (firstRow != null) {
                saveLog(jobId, rowNum, LogLevel.ERROR,
                    "Doublon dans le fichier: " + field + "='" + value + "' déjà présent ligne " + firstRow);
            }
        }
    }

    private Map<String, Object> buildRecord(Map<String, String> rawRow,
                                             List<ColumnMappingDto> mappings,
                                             ImportOptionsDto options,
                                             OdooConnection conn,
                                             Map<String, Map<String, Optional<Long>>> m2oCache,
                                             Long jobId, int rowNum, boolean testMode) {
        Map<String, Object> record = new LinkedHashMap<>();

        for (ColumnMappingDto mapping : mappings) {
            if (mapping.odooField() == null || mapping.odooField().isBlank()) continue;

            String rawValue = rawRow.getOrDefault(mapping.columnName(), "").trim();
            if (rawValue.isBlank()) continue;

            Object value = convertValue(rawValue, mapping, conn, m2oCache, jobId, rowNum, testMode);
            if (value != null) {
                record.put(mapping.odooField(), value);
            }
        }

        return record.isEmpty() ? null : record;
    }

    private Object convertValue(String rawValue, ColumnMappingDto mapping,
                                OdooConnection conn,
                                Map<String, Map<String, Optional<Long>>> m2oCache,
                                Long jobId, int rowNum, boolean testMode) {
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
                        if (testMode) {
                            // Never create records in test mode
                            saveLog(jobId, rowNum, LogLevel.WARNING,
                                "[TEST] Serait créé: '" + rawValue + "' dans " + relModel);
                        } else {
                            Long newId = odooApi.createSimple(conn, relModel, rawValue);
                            found = newId != null ? Optional.of(newId) : Optional.empty();
                            if (found.isPresent()) {
                                saveLog(jobId, rowNum, LogLevel.INFO,
                                    "Créé '" + rawValue + "' dans " + relModel);
                            }
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
    // Report export
    // -------------------------------------------------------------------------

    /** Build a UTF-8 CSV (with BOM for Excel) of all ERROR and WARNING log entries. */
    public byte[] exportReportCsv(Long jobId) {
        List<ImportJobLog> errors = logRepo.findByJobIdAndLevelOrderByRowNumberAsc(jobId, LogLevel.ERROR);
        List<ImportJobLog> warns  = logRepo.findByJobIdAndLevelOrderByRowNumberAsc(jobId, LogLevel.WARNING);
        List<ImportJobLog> all = new ArrayList<>(errors.size() + warns.size());
        all.addAll(errors);
        all.addAll(warns);
        all.sort(Comparator.comparingInt(ImportJobLog::getRowNumber));

        StringBuilder sb = new StringBuilder();
        sb.append('﻿'); // BOM — makes Excel open UTF-8 correctly
        sb.append("Ligne;Niveau;Message\r\n");
        for (ImportJobLog l : all) {
            String rowLabel = l.getRowNumber() > 0 ? String.valueOf(l.getRowNumber()) : "-";
            String msg = l.getMessage() != null ? l.getMessage().replace("\"", "\"\"") : "";
            sb.append(rowLabel).append(';')
              .append(l.getLevel()).append(';')
              .append('"').append(msg).append('"')
              .append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
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
