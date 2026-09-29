package io.github.kubaj12.online_store.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Shared PLN line and document amount calculation using decimal HALF_UP VAT rounding. */
public final class DecimalPriceCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DecimalPriceCalculator() { }

    public record LineInput(BigDecimal unitNetPrice, BigDecimal vatRate, int quantity) { }
    public record Amounts(BigDecimal net, BigDecimal vat, BigDecimal gross) { }

    public static Amounts line(BigDecimal unitNetPrice, BigDecimal vatRate, int quantity) {
        if (unitNetPrice == null || vatRate == null || unitNetPrice.signum() < 0
                || vatRate.signum() < 0 || quantity <= 0)
            throw new IllegalArgumentException("Price, VAT and positive quantity are required");
        BigDecimal net = unitNetPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = net.multiply(vatRate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return new Amounts(net, vat, net.add(vat).setScale(2, RoundingMode.UNNECESSARY));
    }

    public static Amounts total(List<LineInput> lines) {
        if (lines == null) throw new IllegalArgumentException("Lines are required");
        BigDecimal net = BigDecimal.ZERO.setScale(2);
        BigDecimal vat = BigDecimal.ZERO.setScale(2);
        BigDecimal gross = BigDecimal.ZERO.setScale(2);
        for (LineInput line : lines) {
            if (line == null) throw new IllegalArgumentException("Line is required");
            Amounts amount = line(line.unitNetPrice(), line.vatRate(), line.quantity());
            net = net.add(amount.net());
            vat = vat.add(amount.vat());
            gross = gross.add(amount.gross());
        }
        return new Amounts(net, vat, gross);
    }
}
