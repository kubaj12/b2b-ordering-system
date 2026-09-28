package io.github.kubaj12.online_store.catalogpricing.application;

public class CatalogException extends RuntimeException {
    public CatalogException(String message) { super(message); }
    public CatalogException(String message, Throwable cause) { super(message, cause); }
}
