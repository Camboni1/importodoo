package bprimport.odoo.service;

import bprimport.odoo.dto.OdooFieldDto;
import bprimport.odoo.dto.OdooModelDto;
import bprimport.odoo.exception.OdooApiException;
import bprimport.odoo.model.OdooConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OdooApiService {

    private static final Logger log = LoggerFactory.getLogger(OdooApiService.class);

    private final XmlRpcClient xmlRpc;

    /** Cache: connectionId → uid */
    private final Map<Long, Integer> uidCache = new ConcurrentHashMap<>();
    /** Cache: connectionId → Odoo language code (e.g. "fr_FR") */
    private final Map<Long, String> langCache = new ConcurrentHashMap<>();
    /** Cache: connectionId → full sorted model list (loaded once, filtered locally) */
    private final Map<Long, List<OdooModelDto>> modelCache = new ConcurrentHashMap<>();

    public OdooApiService(XmlRpcClient xmlRpc) {
        this.xmlRpc = xmlRpc;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Authenticate via XML-RPC and cache uid. Returns uid. */
    public int authenticate(OdooConnection conn) {
        Object result = xmlRpc.call(
            conn.getUrl() + "/xmlrpc/2/common",
            conn.getPlatformSessionCookie(),
            "authenticate",
            conn.getDatabase(), conn.getLogin(), conn.getApiKey(), Map.of()
        );

        if (result == null || Boolean.FALSE.equals(result)) {
            throw new OdooApiException(
                "Accès refusé — clé API ou login incorrect. " +
                "Vérifiez : 1) La clé API (Settings → API Keys) " +
                "2) Le login (email exact) " +
                "3) Le nom de la base de données " +
                "4) La 2FA est désactivée sur ce compte");
        }

        int uid = ((Number) result).intValue();
        uidCache.put(conn.getId(), uid);
        log.debug("Authenticated uid={} for {}", uid, conn.getName());
        return uid;
    }

    /** Test connection — returns true if OK */
    public boolean testConnection(OdooConnection conn) {
        try {
            return authenticate(conn) > 0;
        } catch (Exception e) {
            log.warn("Connection test failed for {}: {}", conn.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Search Odoo models by name or technical name fragment.
     * All models are loaded once per connection and cached; subsequent calls filter in-memory.
     */
    public List<OdooModelDto> searchModels(OdooConnection conn, String query) {
        List<OdooModelDto> all = modelCache.computeIfAbsent(conn.getId(), id -> fetchAllModels(conn));

        if (query == null || query.isBlank()) return all;

        String q = query.toLowerCase().strip();
        return all.stream()
            .filter(m -> m.model().toLowerCase().contains(q)
                      || m.name().toLowerCase().contains(q))
            .limit(50)
            .toList();
    }

    /** Load every non-transient model from Odoo (called once per connection). */
    private List<OdooModelDto> fetchAllModels(OdooConnection conn) {
        log.debug("Fetching all models for connection '{}'", conn.getName());
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("fields", List.of("name", "model", "transient"));
        kwargs.put("limit", 2000);   // no Odoo instance has more than this
        kwargs.put("order", "name");

        List<Object> emptyDomain = new ArrayList<>();
        List<Map<String, Object>> rows = callKw(conn, "ir.model", "search_read",
            List.of(emptyDomain), kwargs);

        List<OdooModelDto> result = rows.stream()
            .filter(r -> !Boolean.TRUE.equals(r.get("transient")))
            .map(r -> new OdooModelDto(
                String.valueOf(r.get("model")),
                String.valueOf(r.get("name"))
            ))
            .sorted(Comparator.comparing(OdooModelDto::name))
            .toList();

        log.debug("Loaded {} models for connection '{}'", result.size(), conn.getName());
        return result;
    }

    /** Get all importable fields for a model */
    public List<OdooFieldDto> getModelFields(OdooConnection conn, String model) {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("attributes",
            List.of("string", "type", "required", "relation", "readonly", "store"));

        Map<String, Object> result = callKwRaw(conn, model, "fields_get", List.of(), kwargs);

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
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("fields", fields);
        kwargs.put("limit", limit);
        return callKw(conn, model, "search_read", List.of(domain), kwargs);
    }

    /** Write (update) records by ID */
    public boolean writeMany(OdooConnection conn, String model, List<Long> ids, Map<String, Object> values) {
        if (ids.isEmpty()) return true;
        Object result = callKwObject(conn, model, "write", List.of(ids, values), Map.of());
        return Boolean.TRUE.equals(result);
    }

    /** Find M2O record ID by name. Tries multiple strategies to handle encoding and hierarchy. */
    public Optional<Long> findByName(OdooConnection conn, String model, String name) {
        // NFC normalization fixes Excel NFD encoding vs Odoo NFC (é as precomposed char)
        String n = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);

        Optional<Long> found = searchByField(conn, model, "name", "=ilike", n);
        if (found.isPresent()) return found;

        // Hierarchical models (e.g. product.category) expose path in complete_name
        if (n.contains("/")) {
            found = searchByField(conn, model, "complete_name", "=ilike", n);
            if (found.isPresent()) return found;
        }

        // display_name fallback (computed, present on all records)
        found = searchByField(conn, model, "display_name", "=ilike", n);
        if (found.isPresent()) return found;

        // Broad contains-search as last resort (handles partial encoding mismatches)
        return searchByField(conn, model, "name", "ilike", n);
    }

    private Optional<Long> searchByField(OdooConnection conn, String model,
                                          String field, String operator, String value) {
        try {
            List<Object> domain = new ArrayList<>();
            domain.add(new Object[]{field, operator, value});
            Map<String, Object> kwargs = new LinkedHashMap<>();
            kwargs.put("fields", List.of("id"));
            kwargs.put("limit", 1);
            kwargs.put("context", Map.of("lang", getUserLang(conn)));
            List<Map<String, Object>> rows = callKw(conn, model, "search_read",
                List.of(domain), kwargs);
            if (!rows.isEmpty()) return Optional.of(((Number) rows.get(0).get("id")).longValue());
        } catch (Exception e) {
            log.debug("searchByField {}[{} {} '{}'] failed: {}", model, field, operator, value, e.getMessage());
        }
        return Optional.empty();
    }

    private String getUserLang(OdooConnection conn) {
        return langCache.computeIfAbsent(conn.getId(), id -> {
            try {
                int uid = getUid(conn);
                List<Object> domain = new ArrayList<>();
                domain.add(new Object[]{"id", "=", uid});
                Map<String, Object> kwargs = new LinkedHashMap<>();
                kwargs.put("fields", List.of("lang"));
                kwargs.put("limit", 1);
                List<Map<String, Object>> rows = callKw(conn, "res.users", "search_read",
                    List.of(domain), kwargs);
                if (!rows.isEmpty()) {
                    Object lang = rows.get(0).get("lang");
                    if (lang != null && !lang.toString().isBlank()) {
                        log.debug("Detected Odoo lang={} for connection {}", lang, conn.getName());
                        return lang.toString();
                    }
                }
            } catch (Exception e) {
                log.debug("Could not detect Odoo lang: {}", e.getMessage());
            }
            return "en_US";
        });
    }

    /** Create a M2O record with just a name, return its ID */
    public Long createSimple(OdooConnection conn, String model, String name) {
        Map<String, Object> vals = new LinkedHashMap<>();
        vals.put("name", name);
        List<Long> ids = createMany(conn, model, List.of(vals));
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

    /**
     * Batch-check which values already exist in Odoo for a given field.
     * Returns value → existing record id. Used in test mode to detect conflicts.
     */
    public Map<String, Long> findExistingByField(OdooConnection conn, String model,
                                                   String field, List<String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        try {
            List<Object> domain = new ArrayList<>();
            domain.add(new Object[]{field, "in", values});
            Map<String, Object> kwargs = new LinkedHashMap<>();
            kwargs.put("fields", List.of("id", field));
            kwargs.put("limit", values.size() + 100);
            List<Map<String, Object>> rows = callKw(conn, model, "search_read", List.of(domain), kwargs);
            Map<String, Long> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Object val = row.get(field);
                Object id  = row.get("id");
                if (val != null && id != null && !val.toString().isBlank()) {
                    result.put(val.toString(), ((Number) id).longValue());
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("findExistingByField {}.{} failed: {}", model, field, e.getMessage());
            return Map.of();
        }
    }

    /** Invalidate all caches for a connection (uid, lang, models). */
    public void invalidateSession(Long connectionId) {
        uidCache.remove(connectionId);
        langCache.remove(connectionId);
        modelCache.remove(connectionId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private int getUid(OdooConnection conn) {
        Integer uid = uidCache.get(conn.getId());
        if (uid == null) uid = authenticate(conn);
        return uid;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callKw(OdooConnection conn, String model,
                                              String method, List<Object> args,
                                              Map<String, Object> kwargs) {
        Object result = callKwObject(conn, model, method, args, kwargs);
        if (result instanceof List<?> list) return (List<Map<String, Object>>) list;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callKwRaw(OdooConnection conn, String model,
                                           String method, List<Object> args,
                                           Map<String, Object> kwargs) {
        Object result = callKwObject(conn, model, method, args, kwargs);
        if (result instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return Map.of();
    }

    private Object callKwObject(OdooConnection conn, String model,
                                 String method, List<Object> args,
                                 Map<String, Object> kwargs) {
        int uid = getUid(conn);
        return xmlRpc.call(
            conn.getUrl() + "/xmlrpc/2/object",
            conn.getPlatformSessionCookie(),
            "execute_kw",
            conn.getDatabase(), uid, conn.getApiKey(),
            model, method, args, kwargs
        );
    }

    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(o -> ((Number) o).longValue()).toList();
        }
        if (obj instanceof Number n) return List.of(n.longValue());
        return List.of();
    }
}
