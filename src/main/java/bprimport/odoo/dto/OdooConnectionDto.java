package bprimport.odoo.dto;

import jakarta.validation.constraints.NotBlank;

public record OdooConnectionDto(
    @NotBlank String name,
    @NotBlank String url,
    @NotBlank String database,
    @NotBlank String login,
    @NotBlank String apiKey
) {}
