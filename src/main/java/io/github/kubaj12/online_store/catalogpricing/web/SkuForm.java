package io.github.kubaj12.online_store.catalogpricing.web;

import java.math.BigDecimal;
import jakarta.validation.constraints.*;

public record SkuForm(@NotBlank @Size(max=80) String code,
        @NotNull @DecimalMin("0.00") @Digits(integer=10,fraction=2) BigDecimal baseNetPrice,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer=3,fraction=2) BigDecimal vatRate) {
    public SkuForm() { this("", BigDecimal.ZERO, new BigDecimal("23.00")); }
}
