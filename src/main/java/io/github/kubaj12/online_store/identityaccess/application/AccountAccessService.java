package io.github.kubaj12.online_store.identityaccess.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AccountAccessService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountAccessService.class);
	private final AuthenticationAccountStore accountStore;

	public AccountAccessService(AuthenticationAccountStore accountStore) {
		this.accountStore = accountStore;
	}

	public boolean isCurrent(AccountPrincipal principal) {
		try {
			return accountStore.findAccessById(principal.accountId()).map(current ->
					current.id().equals(principal.accountId())
							&& current.email().equals(principal.email())
							&& current.role().equals(principal.role())
							&& "ACTIVE".equals(current.status())
							&& current.securityVersion() == principal.securityVersion()
				).orElse(false);
		}
		catch (RuntimeException exception) {
			String reference = UUID.randomUUID().toString();
			LOGGER.error("Account access lookup failed; reference={}, exceptionType={}", reference, exception.getClass().getName());
			throw new AccountAccessFailure("account access lookup failed; reference=" + reference);
		}
	}

	public static final class AccountAccessFailure extends IllegalStateException {
		public AccountAccessFailure(String message) { super(message); }
	}
}
