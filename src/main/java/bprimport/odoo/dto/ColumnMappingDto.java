package bprimport.odoo.dto;

public record ColumnMappingDto(
    int columnIndex,
    String columnName,
    String odooField,
    String odooFieldType,
    String odooFieldLabel,
    String relatedModel,
    boolean createIfNotFound
) {}
