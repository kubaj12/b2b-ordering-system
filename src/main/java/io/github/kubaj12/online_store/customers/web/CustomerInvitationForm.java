package io.github.kubaj12.online_store.customers.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record CustomerInvitationForm(
    @NotBlank @Email @Size(max=254) String email,
    @NotNull @Valid CustomerProfileForm profile
) {
    public CustomerInvitationForm() { this("", new CustomerProfileForm()); }
}
