package io.github.kubaj12.online_store.catalogpricing.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries durable image cleanup work after transient filesystem failures or restarts. */
@Component
public class ImageCleanupWorker {
    private final CatalogAdministrationService catalog;
    public ImageCleanupWorker(CatalogAdministrationService catalog) { this.catalog=catalog; }

    @Scheduled(fixedDelay=60_000, initialDelay=60_000)
    public void retryPendingCleanup() { catalog.retryImageCleanup(); }
}
