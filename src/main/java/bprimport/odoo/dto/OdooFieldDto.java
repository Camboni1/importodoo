package bprimport.odoo.dto;

public record OdooFieldDto(
    String name,
    String label,
    String type,
    String relation,
    boolean required,
    boolean readonly
) {}
