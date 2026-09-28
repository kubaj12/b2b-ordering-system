package io.github.kubaj12.online_store.catalogpricing.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NamedValueForm(@NotBlank @Size(max=120) String value) { public NamedValueForm() { this(""); } }
