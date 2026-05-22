package bprimport.odoo.controller;

import bprimport.odoo.dto.*;
import bprimport.odoo.model.ImportJob;
import bprimport.odoo.repository.OdooConnectionRepository;
import bprimport.odoo.service.ImportJobService;
import bprimport.odoo.service.OdooApiService;
import bprimport.odoo.service.XlsxParseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final OdooConnectionRepository connRepo;
    private final OdooApiService odooApi;
    private final XlsxParseService xlsxParser;
    private final ImportJobService jobService;

    public ApiController(OdooConnectionRepository connRepo,
                         OdooApiService odooApi,
                         XlsxParseService xlsxParser,
                         ImportJobService jobService) {
        this.connRepo = connRepo;
        this.odooApi = odooApi;
        this.xlsxParser = xlsxParser;
        this.jobService = jobService;
    }

    // -------------------------------------------------------------------------
    // Connection test
    // -------------------------------------------------------------------------

    @PostMapping("/connections/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id) {
        var conn = connRepo.findById(id).orElse(null);
        if (conn == null) return ResponseEntity.notFound().build();
        try {
            int uid = odooApi.authenticate(conn);
            return ok(Map.of("success", true, "uid", uid, "message", "Connexion réussie (uid=" + uid + ")"));
        } catch (Exception e) {
            return ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // File upload
    // -------------------------------------------------------------------------

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            String fileId = jobService.saveUploadedFile(file);
            var path = jobService.resolveFilePath(fileId);
            List<String> sheets = xlsxParser.getSheetNames(path);
            long sizeKb = path.toFile().length() / 1024;
            return ok(Map.of(
                "fileId", fileId,
                "originalName", file.getOriginalFilename(),
                "sizeKb", sizeKb,
                "sheets", sheets
            ));
        } catch (Exception e) {
            return err("Erreur upload: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Preview
    // -------------------------------------------------------------------------

    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(
        @RequestParam String fileId,
        @RequestParam String sheet,
        @RequestParam(defaultValue = "10") int limit) {
        try {
            var path = jobService.resolveFilePath(fileId);
            var result = xlsxParser.getPreview(path, sheet, limit);
            return ok(Map.of(
                "headers", result.headers(),
                "rows", result.rows(),
                "totalRows", result.totalRows()
            ));
        } catch (Exception e) {
            return err("Erreur preview: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Odoo models search
    // -------------------------------------------------------------------------

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> searchModels(
        @RequestParam Long connectionId,
        @RequestParam(defaultValue = "") String q) {
        var conn = connRepo.findById(connectionId).orElse(null);
        if (conn == null) return err("Connexion introuvable");
        try {
            List<OdooModelDto> models = odooApi.searchModels(conn, q);
            return ok(Map.of("models", models));
        } catch (Exception e) {
            return err("Erreur: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Model fields
    // -------------------------------------------------------------------------

    @GetMapping("/fields")
    public ResponseEntity<Map<String, Object>> getFields(
        @RequestParam Long connectionId,
        @RequestParam String model) {
        var conn = connRepo.findById(connectionId).orElse(null);
        if (conn == null) return err("Connexion introuvable");
        try {
            List<OdooFieldDto> fields = odooApi.getModelFields(conn, model);
            return ok(Map.of("fields", fields));
        } catch (Exception e) {
            return err("Erreur: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Start import
    // -------------------------------------------------------------------------

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> startImport(@RequestBody ImportRequestDto req) {
        try {
            ImportJob job = jobService.createJob(req);
            jobService.runAsync(job.getId());
            return ok(Map.of("jobId", job.getId(), "message", "Import démarré"));
        } catch (Exception e) {
            return err("Erreur démarrage: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Job status
    // -------------------------------------------------------------------------

    @GetMapping("/jobs/{id}/status")
    public ResponseEntity<Map<String, Object>> jobStatus(@PathVariable Long id) {
        return connRepo.findById(id)
            .map(c -> ok(Map.of()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private <T> ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> err(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
