package bprimport.odoo.dto;

import java.util.List;

public record ImportRequestDto(
    Long connectionId,
    String fileId,
    String sheetName,
    String odooModel,
    List<ColumnMappingDto> mappings,
    ImportOptionsDto options,
    boolean testMode
) {}
