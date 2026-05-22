package bprimport.odoo.service;

import bprimport.odoo.dto.OdooFieldDto;
import bprimport.odoo.dto.OdooModelDto;
import bprimport.odoo.exception.OdooApiException;
import bprimport.odoo.model.OdooConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OdooApiService {

    private static final Logger log = LoggerFactory.getLogger(OdooApiService.class);

    private final XmlRpcClient xmlRpc;

    /** Cache: connectionId → uid */
    private final Map<Long, Integer> uidCache = new ConcurrentHashMap<>();

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

    /** Invalidate cached uid for a connection */
    public void invalidateSession(Long connectionId) {
        uidCache.remove(connectionId);
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
