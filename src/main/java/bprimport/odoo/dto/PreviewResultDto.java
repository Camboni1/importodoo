package bprimport.odoo.dto;

import java.util.List;
import java.util.Map;

public record PreviewResultDto(
    List<String> headers,
    List<Map<String, String>> rows,
    int totalRows
) {}
