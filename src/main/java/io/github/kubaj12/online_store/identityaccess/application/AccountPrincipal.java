package io.github.kubaj12.online_store.identityaccess.application;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Persisted application identity held in the servlet security context. */
public final class AccountPrincipal implements UserDetails, CredentialsContainer, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private final UUID accountId;
	private final String email;
	private final String role;
	private final String status;
	private final long securityVersion;
	private final Collection<? extends GrantedAuthority> authorities;
	private String passwordHash;

	public AccountPrincipal(UUID accountId, String email, String passwordHash, String role, String status,
			long securityVersion) {
		this.accountId = accountId;
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = supportedRole(role);
		this.status = supportedStatus(status);
		this.securityVersion = securityVersion;
		this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + this.role));
	}

	private static String supportedRole(String role) {
		if (!"CUSTOMER".equals(role) && !"EMPLOYEE".equals(role) && !"ADMIN".equals(role)) {
			throw new IllegalArgumentException("unsupported account role");
		}
		return role;
	}

	private static String supportedStatus(String status) {
		if (!"ACTIVE".equals(status) && !"BLOCKED".equals(status)) {
			throw new IllegalArgumentException("unsupported account status");
		}
		return status;
	}

	public UUID accountId() { return accountId; }
	public String role() { return role; }
	public String status() { return status; }
	public long securityVersion() { return securityVersion; }
	public String email() { return email; }

	@Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
	@Override public String getPassword() { return passwordHash; }
	@Override public String getUsername() { return email; }
	@Override public boolean isAccountNonExpired() { return true; }
	@Override public boolean isAccountNonLocked() { return "ACTIVE".equals(status); }
	@Override public boolean isCredentialsNonExpired() { return true; }
	@Override public boolean isEnabled() { return "ACTIVE".equals(status); }

	@Override
	public void eraseCredentials() {
		passwordHash = null;
	}

	@Override
	public String toString() {
		return "AccountPrincipal[id=" + accountId + ", role=" + role + ", status=" + status
				+ ", securityVersion=" + securityVersion + "]";
	}
}
