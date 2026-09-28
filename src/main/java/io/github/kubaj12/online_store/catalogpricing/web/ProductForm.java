package io.github.kubaj12.online_store.catalogpricing.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductForm(@NotBlank @Size(max=255) String name, @Size(max=10000) String description,
        @NotBlank @Size(max=120) String category) {
    public ProductForm() { this("", "", ""); }
}
