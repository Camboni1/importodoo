package bprimport.odoo.dto;

public record ImportOptionsDto(
    /** "id", "external_id", or "name" */
    String matchBy,
    /** "update" or "skip" on duplicate */
    String onConflict,
    int batchSize,
    boolean skipEmptyLines,
    boolean stopOnError
) {
    public static ImportOptionsDto defaults() {
        return new ImportOptionsDto("name", "update", 100, true, false);
    }
}
