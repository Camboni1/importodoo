package bprimport.odoo.service;

import bprimport.odoo.exception.OdooApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class XmlRpcClient {

    private static final Logger log = LoggerFactory.getLogger(XmlRpcClient.class);

    public Object call(String url, String cookie, String method, Object... params) {
        String xml = buildRequest(method, params);
        log.debug("XML-RPC POST {} method={}", url, method);
        String response = post(url, cookie, xml);
        return parseResponse(response);
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    private String buildRequest(String method, Object[] params) {
        StringBuilder sb = new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><methodCall><methodName>");
        sb.append(escape(method)).append("</methodName><params>");
        for (Object p : params) {
            sb.append("<param>").append(serializeValue(p)).append("</param>");
        }
        sb.append("</params></methodCall>");
        return sb.toString();
    }

    String serializeValue(Object v) {
        if (v == null)              return "<value><nil/></value>";
        if (v instanceof Boolean b) return "<value><boolean>" + (b ? 1 : 0) + "</boolean></value>";
        if (v instanceof Integer i) return "<value><int>" + i + "</int></value>";
        if (v instanceof Long l)    return "<value><int>" + l + "</int></value>";
        if (v instanceof Double d)  return "<value><double>" + d + "</double></value>";
        if (v instanceof String s)  return "<value><string>" + escape(s) + "</string></value>";
        if (v instanceof Object[] arr) {
            StringBuilder sb = new StringBuilder("<value><array><data>");
            for (Object item : arr) sb.append(serializeValue(item));
            return sb.append("</data></array></value>").toString();
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("<value><array><data>");
            for (Object item : list) sb.append(serializeValue(item));
            return sb.append("</data></array></value>").toString();
        }
        if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "<value><struct/></value>";
            StringBuilder sb = new StringBuilder("<value><struct>");
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append("<member><name>").append(escape(e.getKey().toString())).append("</name>")
                  .append(serializeValue(e.getValue())).append("</member>");
            }
            return sb.append("</struct></value>").toString();
        }
        return "<value><string>" + escape(v.toString()) + "</string></value>";
    }

    private String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private String post(String url, String cookie, String xml) {
        try {
            RestClient client = RestClient.builder().build();
            var spec = client.post()
                .uri(url)
                .header(HttpHeaders.CONTENT_TYPE, "text/xml; charset=UTF-8")
                .header(HttpHeaders.ACCEPT, "text/xml")
                .header(HttpHeaders.USER_AGENT, "OdooImportTool/1.0");
            if (cookie != null && !cookie.isBlank()) {
                spec = spec.header(HttpHeaders.COOKIE, cookie.trim());
            }
            return spec.body(xml)
                .retrieve()
                .onStatus(s -> !s.is2xxSuccessful(), (req, resp) -> {
                    throw new OdooApiException(
                        "HTTP " + resp.getStatusCode() +
                        " — Vérifiez l'URL et la base de données.");
                })
                .body(String.class);
        } catch (OdooApiException e) {
            throw e;
        } catch (Exception e) {
            throw new OdooApiException("Erreur HTTP: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private Object parseResponse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Node root = doc.getDocumentElement();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if ("fault".equals(child.getNodeName())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fault = (Map<String, Object>) parseValue(findFirst(child, "value"));
                    String msg = fault != null ? String.valueOf(fault.get("faultString")) : "Erreur XML-RPC";
                    throw new OdooApiException(msg);
                }
                if ("params".equals(child.getNodeName())) {
                    Node param = findFirst(child, "param");
                    if (param != null) return parseValue(findFirst(param, "value"));
                }
            }
            return null;
        } catch (OdooApiException e) {
            throw e;
        } catch (Exception e) {
            throw new OdooApiException("Impossible de parser la réponse XML-RPC: " + e.getMessage(), e);
        }
    }

    private Object parseValue(Node valueNode) {
        if (valueNode == null) return null;
        NodeList children = valueNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            return switch (n.getNodeName()) {
                case "int", "i4", "i8" -> Long.parseLong(n.getTextContent().trim());
                case "boolean"         -> !"0".equals(n.getTextContent().trim());
                case "double"          -> Double.parseDouble(n.getTextContent().trim());
                case "string"          -> n.getTextContent();
                case "nil"             -> null;
                case "array"           -> parseArray(n);
                case "struct"          -> parseStruct(n);
                default                -> n.getTextContent();
            };
        }
        return valueNode.getTextContent();
    }

    private List<Object> parseArray(Node arrayNode) {
        List<Object> list = new ArrayList<>();
        Node data = findFirst(arrayNode, "data");
        if (data == null) return list;
        NodeList children = data.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if ("value".equals(n.getNodeName())) list.add(parseValue(n));
        }
        return list;
    }

    private Map<String, Object> parseStruct(Node structNode) {
        Map<String, Object> map = new LinkedHashMap<>();
        NodeList children = structNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!"member".equals(n.getNodeName())) continue;
            String name = null;
            Object value = null;
            NodeList memberChildren = n.getChildNodes();
            for (int j = 0; j < memberChildren.getLength(); j++) {
                Node mn = memberChildren.item(j);
                if ("name".equals(mn.getNodeName()))  name  = mn.getTextContent();
                else if ("value".equals(mn.getNodeName())) value = parseValue(mn);
            }
            if (name != null) map.put(name, value);
        }
        return map;
    }

    private Node findFirst(Node parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (tagName.equals(children.item(i).getNodeName())) return children.item(i);
        }
        return null;
    }
}
