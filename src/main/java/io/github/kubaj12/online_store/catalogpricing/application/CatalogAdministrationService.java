package io.github.kubaj12.online_store.catalogpricing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.util.Iterator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.shared.auditing.AuditActor;
import io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder;
import io.github.kubaj12.online_store.shared.money.DecimalPriceCalculator;

@Service
public class CatalogAdministrationService {
    private final CatalogStore store;
    private final Clock clock;
    private final AuditEventRecorder audit;
    private final ImageStorage imageStorage;
    public CatalogAdministrationService(CatalogStore store, Clock clock, AuditEventRecorder audit, ImageStorage imageStorage) {
        this.store = store; this.clock = clock; this.audit = audit; this.imageStorage = imageStorage;
    }
    public record ImageView(String contentType, byte[] bytes) { }
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_IMAGE_SIDE = 8000;
    private static final long MAX_IMAGE_PIXELS = 24_000_000L;
    @Transactional(readOnly = true) public ImageView image(UUID skuId, boolean thumbnail) {
        UUID id=requiredId(skuId);
        var metadata=store.image(id);
        if(metadata==null) throw new CatalogException("image not found");
        var loaded=imageStorage.load(metadata.reference());
        if(loaded.isEmpty()) {
            // A replacement may commit and clean the previous file between metadata lookup
            // and storage read. Retry once using the now-current opaque reference.
            var current=store.image(id);
            if(current==null || current.reference().equals(metadata.reference())) throw new CatalogException("image not found");
            metadata=current;
            loaded=imageStorage.load(metadata.reference());
        }
        var content=loaded.orElseThrow(() -> new CatalogException("image not found"));
        if (!thumbnail) return new ImageView(content.contentType(), content.bytes());
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(content.bytes()));
            if (source == null) throw new CatalogException("invalid stored image");
            double scale = Math.min(1d, Math.min(320d / source.getWidth(), 240d / source.getHeight()));
            int width = Math.max(1, (int)Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int)Math.round(source.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = scaled.createGraphics();
            graphics.drawImage(source, 0, 0, width, height, java.awt.Color.WHITE, null); graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(scaled, "jpeg", output);
            return new ImageView("image/jpeg", output.toByteArray());
        } catch (java.io.IOException ex) { throw new CatalogException("image processing failed", ex); }
    }
    @Transactional public void uploadImage(UUID skuId, byte[] bytes) {
        UUID id = requiredId(skuId);
        ImageContent safe = sanitize(bytes);
        ImageReference next = imageStorage.store(safe);
        try {
            store.replaceImage(id, next, safe.contentType(), safe.size(), dimensions(safe.bytes())[0], dimensions(safe.bytes())[1], now());
        } catch (RuntimeException ex) { cleanupUncommittedImage(next); throw ex; }
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) cleanupUncommittedImage(next);
            }
        });
        afterCommit(this::retryImageCleanup);
    }
    @Transactional public void removeImage(UUID skuId) {
        store.removeImage(requiredId(skuId),now());
        afterCommit(this::retryImageCleanup);
    }
    public void retryImageCleanup() {
        try {
            for(ImageReference reference:store.pendingImageCleanup(100)) {
                try { imageStorage.delete(reference); store.completeImageCleanup(reference); }
                catch(RuntimeException failure) {
                    try { store.markImageCleanupAttempt(reference,now()); } catch(RuntimeException ignored) { }
                    logCleanupFailure();
                }
            }
        } catch(RuntimeException failure) { logCleanupFailure(); }
    }
    private void cleanupUncommittedImage(ImageReference reference) {
        boolean queued=false;
        try { store.enqueueImageCleanup(reference,now()); queued=true; }
        catch(RuntimeException failure) { logCleanupFailure(); }
        try {
            imageStorage.delete(reference);
            if(queued) store.completeImageCleanup(reference);
        } catch(RuntimeException failure) {
            if(queued) try { store.markImageCleanupAttempt(reference,now()); } catch(RuntimeException ignored) { }
            logCleanupFailure();
        }
    }
    private static void logCleanupFailure() { org.slf4j.LoggerFactory.getLogger(CatalogAdministrationService.class)
            .warn("Could not remove retired catalog image; cleanup will retry"); }
    private ImageContent sanitize(byte[] input) {
        if (input == null || input.length == 0 || input.length > MAX_IMAGE_BYTES) throw new CatalogException("image must be at most 5 MiB");
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            if (stream == null) throw new CatalogException("unsupported image");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new CatalogException("unsupported image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("jpg") && !format.equals("png")) throw new CatalogException("only JPEG and PNG are accepted");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE || (long)width * height > MAX_IMAGE_PIXELS) throw new CatalogException("image dimensions are too large");
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw new CatalogException("invalid image data");
                boolean png = format.equals("png");
                BufferedImage clean = png ? new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB) : new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
                var g = clean.createGraphics(); if (!png) { g.setColor(java.awt.Color.WHITE); g.fillRect(0,0,width,height); }
                g.drawImage(decoded,0,0,null); g.dispose();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(clean, png ? "png" : "jpeg", output) || output.size() > MAX_IMAGE_BYTES) throw new CatalogException("image is too large");
                return new ImageContent(png ? "image/png" : "image/jpeg", output.toByteArray());
            } finally { reader.dispose(); }
        } catch (java.io.IOException ex) { throw new CatalogException("invalid image data", ex); }
    }
    private static int[] dimensions(byte[] bytes) { try { var image = ImageIO.read(new ByteArrayInputStream(bytes)); return new int[]{image.getWidth(), image.getHeight()}; } catch (java.io.IOException ex) { throw new CatalogException("invalid image",ex); } }
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { action.run(); } });
        else action.run();
    }
    public record ReferenceAmounts(BigDecimal net, BigDecimal vat, BigDecimal gross) { }
    @Transactional(readOnly = true) public List<CatalogStore.ProductRow> products() { return store.products(); }
    @Transactional(readOnly = true) public CatalogStore.ProductDetail product(UUID id) { return store.product(requiredId(id)); }
    public static ReferenceAmounts referenceAmounts(BigDecimal net, BigDecimal vatRate) {
        if (net == null || vatRate == null) throw new CatalogException("price and VAT required");
        var amounts = DecimalPriceCalculator.line(net, vatRate, 1);
        return new ReferenceAmounts(amounts.net(), amounts.vat(), amounts.gross());
    }

    @Transactional public UUID createProduct(String name, String description, String category) {
        String n = required(name, 255, "product name"), c = required(category, 120, "category");
        String d = description(description);
        UUID id = UUID.randomUUID(); store.createProduct(id, n, d, c, now()); return id;
    }
    @Transactional public void updateProduct(UUID id, String name, String description, String category) {
        store.updateProduct(requiredId(id), required(name,255,"product name"), description(description),
                required(category,120,"category"), now());
    }
    @Transactional public void activateProduct(UUID id) {
        if (!store.activateProduct(requiredId(id), now())) throw new CatalogException("product must have at least one SKU before activation");
    }
    /** Retains product and SKU rows so order references and audit/history remain valid. */
    @Transactional public void deactivateProduct(UUID id) { store.deactivateProduct(requiredId(id), now()); }
    @Transactional public UUID createSku(UUID productId, String code, BigDecimal baseNetPrice, BigDecimal vatRate, UUID actorId) {
        UUID id = UUID.randomUUID();
        BigDecimal price = money(baseNetPrice), rate = vat(vatRate);
        store.createSku(id, requiredId(productId), required(code,80,"SKU code"), price, rate, now());
        audit.record(CatalogAudit.SKU_PRICING_CHANGED.event(id, new AuditActor(requiredId(actorId)),
                CatalogAudit.BASE_NET_PRICE.change(null, price), CatalogAudit.VAT_RATE.change(null, rate)));
        return id;
    }
    @Transactional public void updateSku(UUID id, String code) { store.updateSku(requiredId(id), required(code,80,"SKU code"), now()); }
    @Transactional public void activateSku(UUID id) { store.setSkuActivity(requiredId(id), true, now()); }
    @Transactional public void deactivateSku(UUID id) { store.setSkuActivity(requiredId(id), false, now()); }
    @Transactional public void changeBasePriceAndVat(UUID id, BigDecimal price, BigDecimal vat, UUID actorId) {
        UUID skuId = requiredId(id);
        BigDecimal nextPrice = money(price), nextVat = vat(vat);
        CatalogStore.PriceVat previous = store.setPriceAndVat(skuId, nextPrice, nextVat, now());
        var actor = new AuditActor(requiredId(actorId));
        if (previous.price().compareTo(nextPrice) != 0 || previous.vat().compareTo(nextVat) != 0) {
            var priceChange = previous.price().compareTo(nextPrice) == 0 ? null : CatalogAudit.BASE_NET_PRICE.change(previous.price(), nextPrice);
            var vatChange = previous.vat().compareTo(nextVat) == 0 ? null : CatalogAudit.VAT_RATE.change(previous.vat(), nextVat);
            if (priceChange != null && vatChange != null) audit.record(CatalogAudit.SKU_PRICING_CHANGED.event(skuId, actor, priceChange, vatChange));
            else if (priceChange != null) audit.record(CatalogAudit.SKU_PRICING_CHANGED.event(skuId, actor, priceChange));
            else audit.record(CatalogAudit.SKU_PRICING_CHANGED.event(skuId, actor, vatChange));
        }
    }
    @Transactional public UUID createAttributeDefinition(String name) { return store.createDefinition(required(name,120,"attribute name"), now()); }
    @Transactional public UUID createAttributeValue(UUID definitionId, String value) {
        return store.createAttributeValue(requiredId(definitionId), required(value,120,"attribute value"), now());
    }
    @Transactional public void assignVariantAttribute(UUID skuId, UUID definitionId, UUID valueId) {
        store.assignAttribute(requiredId(skuId), requiredId(definitionId), requiredId(valueId), now());
    }
    @Transactional public void removeVariantAttribute(UUID skuId, UUID definitionId) { store.removeAttribute(requiredId(skuId), requiredId(definitionId)); }
    @Transactional(readOnly = true) public void requireSellable(UUID skuId) {
        CatalogStore.Availability state = store.availability(requiredId(skuId));
        CatalogAvailability.requireSellable(state.productActive(), state.skuActive());
    }

    private static String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new CatalogException("invalid " + label);
        return value.trim();
    }
    private static String description(String value) {
        String result = value == null ? "" : value;
        if (result.length() > 10000) throw new CatalogException("description is too long");
        return result;
    }
    private static UUID requiredId(UUID id) { if (id == null) throw new CatalogException("identifier required"); return id; }
    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new CatalogException("base net price must be non-negative");
        try { BigDecimal scaled = value.setScale(2, RoundingMode.UNNECESSARY); if (scaled.precision() > 12) throw new ArithmeticException(); return scaled; }
        catch (ArithmeticException ex) { throw new CatalogException("base net price must fit NUMERIC(12,2)", ex); }
    }
    private static BigDecimal vat(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) throw new CatalogException("VAT must be between 0 and 100");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new CatalogException("VAT supports at most two decimals", ex); }
    }
    private java.time.Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
