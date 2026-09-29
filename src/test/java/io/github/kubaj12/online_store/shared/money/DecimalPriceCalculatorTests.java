package io.github.kubaj12.online_store.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecimalPriceCalculatorTests {
    @Test void roundsVatHalfUpPerLineAndSumsRoundedAmounts() {
        var first = DecimalPriceCalculator.line(new BigDecimal("0.05"), new BigDecimal("10"), 1);
        assertThat(first.vat()).isEqualByComparingTo("0.01");

        var total = DecimalPriceCalculator.total(List.of(
                new DecimalPriceCalculator.LineInput(new BigDecimal("0.05"), new BigDecimal("10"), 1),
                new DecimalPriceCalculator.LineInput(new BigDecimal("0.05"), new BigDecimal("10"), 1)));
        assertThat(total.net()).isEqualByComparingTo("0.10");
        assertThat(total.vat()).isEqualByComparingTo("0.02");
        assertThat(total.gross()).isEqualByComparingTo("0.12");
    }

    @Test void calculatesLineNetFromUnitNetAndQuantity() {
        var line = DecimalPriceCalculator.line(new BigDecimal("12.34"), new BigDecimal("23.00"), 3);
        assertThat(line.net()).isEqualByComparingTo("37.02");
        assertThat(line.vat()).isEqualByComparingTo("8.51");
        assertThat(line.gross()).isEqualByComparingTo("45.53");
    }
}
