package bprimport.odoo.service;

import bprimport.odoo.dto.OdooFieldDto;
import bprimport.odoo.dto.OdooModelDto;
import bprimport.odoo.exception.OdooApiException;
import bprimport.odoo.model.OdooConnection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OdooApiService {

    private static final Logger log = LoggerFactory.getLogger(OdooApiService.class);
    private static final AtomicLong requestId = new AtomicLong(1);

    private final ObjectMapper mapper;

    /** Cache: connectionId -> sessionId */
    private final Map<Long, String> sessionCache = new ConcurrentHashMap<>();

    public OdooApiService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Authenticate and cache session. Returns uid. */
    public int authenticate(OdooConnection conn) {
        Map<String, Object> authParams = new LinkedHashMap<>();
        authParams.put("db", conn.getDatabase());
        authParams.put("login", conn.getLogin());
        authParams.put("password", conn.getApiKey());
        Map<String, Object> body = buildRequest(authParams);

        String raw = post(conn.getUrl(), "/web/session/authenticate", body, null);
        JsonNode result = parseResult(raw);

        if (result.isNull() || !result.has("uid")) {
            throw new OdooApiException("Authentication failed: invalid credentials or database");
        }

        String sessionId = result.path("session_id").asText(null);
        if (sessionId != null) {
            sessionCache.put(conn.getId(), sessionId);
        }
        return result.path("uid").asInt();
    }

    /** Test connection — returns true if OK */
    public boolean testConnection(OdooConnection conn) {
        try {
            int uid = authenticate(conn);
            return uid > 0;
        } catch (Exception e) {
            log.warn("Connection test failed for {}: {}", conn.getName(), e.getMessage());
            return false;
        }
    }

    /** Search Odoo models by name fragment */
    public List<OdooModelDto> searchModels(OdooConnection conn, String query) {
        List<Object> domain = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            domain.add(new Object[]{"name", "ilike", query});
        }
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("fields", List.of("name", "model", "transient"));
        kwargs.put("limit", 100);
        kwargs.put("order", "name");
        List<Map<String, Object>> rows = callKw(conn, "ir.model", "search_read",
            List.of(domain), kwargs);

        return rows.stream()
            .filter(r -> !Boolean.TRUE.equals(r.get("transient")))
            .map(r -> new OdooModelDto(
                String.valueOf(r.get("model")),
                String.valueOf(r.get("name"))
            ))
            .toList();
    }

    /** Get all importable fields for a model */
    public List<OdooFieldDto> getModelFields(OdooConnection conn, String model) {
        Map<String, Object> result = callKwRaw(conn, model, "fields_get",
            List.of(),
            Map.of("attributes", List.of("string", "type", "required", "relation", "readonly", "store")));

        List<OdooFieldDto> fields = new ArrayList<>();
        result.forEach((fieldName, obj) -> {
            if (obj instanceof Map<?, ?> m) {
                Object tv = m.get("type");
                String type = tv != null ? tv.toString() : "char";
                boolean readonly = Boolean.TRUE.equals(m.get("readonly"));
                if (readonly && !List.of("id", "name").contains(fieldName)) return;
                Object sv = m.get("string");
                String label = sv != null ? sv.toString() : fieldName;
                Object rv = m.get("relation");
                String relation = rv != null ? rv.toString() : "";
                fields.add(new OdooFieldDto(
                    fieldName, label, type, relation,
                    Boolean.TRUE.equals(m.get("required")), readonly
                ));
            }
        });
        fields.sort(Comparator.comparing(OdooFieldDto::label));
        return fields;
    }

    /** Batch create records. Returns list of created IDs. */
    public List<Long> createMany(OdooConnection conn, String model, List<Map<String, Object>> records) {
        if (records.isEmpty()) return List.of();
        Object result = callKwObject(conn, model, "create", List.of(records), Map.of());
        return toLongList(result);
    }

    /** Search and read records */
    public List<Map<String, Object>> searchRead(OdooConnection conn, String model,
                                                  List<Object> domain,
                                                  List<String> fields,
                                                  int limit) {
        Map<String, Object> srKwargs = new LinkedHashMap<>();
        srKwargs.put("fields", fields);
        srKwargs.put("limit", limit);
        return callKw(conn, model, "search_read", List.of(domain), srKwargs);
    }

    /** Write (update) records by ID */
    public boolean writeMany(OdooConnection conn, String model, List<Long> ids, Map<String, Object> values) {
        if (ids.isEmpty()) return true;
        Object result = callKwObject(conn, model, "write", List.of(ids, values), Map.of());
        return Boolean.TRUE.equals(result);
    }

    /** Find M2O record ID by name (case-insensitive). Returns empty if not found. */
    public Optional<Long> findByName(OdooConnection conn, String model, String name) {
        List<Object> nameDomain = new ArrayList<>();
        nameDomain.add(new Object[]{"name", "=ilike", name});
        List<Map<String, Object>> rows = searchRead(conn, model,
            nameDomain, List.of("id", "name"), 1);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(((Number) rows.get(0).get("id")).longValue());
    }

    /** Create a M2O record with just a name, return its ID */
    public Long createSimple(OdooConnection conn, String model, String name) {
        List<Long> ids = createMany(conn, model, List.of(Map.of("name", name)));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Search by external ID (xmlid). Returns record ID or -1 */
    public long findByExternalId(OdooConnection conn, String externalId) {
        List<Object> xidDomain = new ArrayList<>();
        xidDomain.add(new Object[]{"complete_name", "=", externalId});
        Map<String, Object> xidKwargs = new LinkedHashMap<>();
        xidKwargs.put("fields", List.of("res_id", "model"));
        xidKwargs.put("limit", 1);
        List<Map<String, Object>> rows = callKw(conn, "ir.model.data", "search_read",
            List.of(xidDomain), xidKwargs);
        if (rows.isEmpty()) return -1L;
        return ((Number) rows.get(0).get("res_id")).longValue();
    }

    /** Invalidate cached session for a connection */
    public void invalidateSession(Long connectionId) {
        sessionCache.remove(connectionId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String getSession(OdooConnection conn) {
        String session = sessionCache.get(conn.getId());
        if (session == null) {
            authenticate(conn);
            session = sessionCache.get(conn.getId());
        }
        return session;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callKw(OdooConnection conn, String model,
                                              String method, List<Object> args,
                                              Map<String, Object> kwargs) {
        Object result = callKwObject(conn, model, method, args, kwargs);
        if (result instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callKwRaw(OdooConnection conn, String model,
                                           String method, List<Object> args,
                                           Map<String, Object> kwargs) {
        Object result = callKwObject(conn, model, method, args, kwargs);
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Object callKwObject(OdooConnection conn, String model,
                                 String method, List<Object> args,
                                 Map<String, Object> kwargs) {
        String session = getSession(conn);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", model);
        params.put("method", method);
        params.put("args", args);
        params.put("kwargs", kwargs);

        Map<String, Object> body = buildRequest(params);
        String raw = post(conn.getUrl(), "/web/dataset/call_kw", body, session);

        JsonNode resultNode = parseResult(raw);

        try {
            return mapper.treeToValue(resultNode, Object.class);
        } catch (Exception e) {
            throw new OdooApiException("Failed to parse result: " + e.getMessage(), e);
        }
    }

    private String post(String baseUrl, String path, Map<String, Object> body, String sessionId) {
        try {
            String cleanBase = baseUrl.stripTrailing();
            String cleanPath = path.startsWith("/") ? path.substring(1) : path;
            String url = cleanBase + "/" + cleanPath;
            String jsonBody = mapper.writeValueAsString(body);

            RestClient client = RestClient.builder().build();
            RestClient.RequestBodySpec spec = client.post()
                .uri(url)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

            if (sessionId != null) {
                spec = spec.header("Cookie", "session_id=" + sessionId);
            }

            return spec.body(jsonBody)
                .retrieve()
                .body(String.class);

        } catch (Exception e) {
            throw new OdooApiException("HTTP call failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildRequest(Map<String, Object> params) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("method", "call");
        req.put("id", requestId.getAndIncrement());
        req.put("params", params);
        return req;
    }

    private JsonNode parseResult(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            if (root.has("error")) {
                JsonNode err = root.get("error");
                String msg = err.path("data").path("message").asText(
                    err.path("message").asText("Unknown Odoo error"));
                int code = err.path("code").asInt(-1);
                throw new OdooApiException(msg, code);
            }
            return root.get("result");
        } catch (OdooApiException e) {
            throw e;
        } catch (Exception e) {
            throw new OdooApiException("Failed to parse Odoo response: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(o -> ((Number) o).longValue()).toList();
        }
        if (obj instanceof Number n) {
            return List.of(n.longValue());
        }
        return List.of();
    }
}
