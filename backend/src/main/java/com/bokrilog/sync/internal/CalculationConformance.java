package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * Whether this build's calculator reproduces the approved vectors (CALC-1.0 CV-01..07, 09, 10, 12).
 *
 * <p>Evaluated once when the bean is created, at startup, not lazily on the first publication: a
 * non-conformant build is visible before anyone asks it to publish. A failure is logged once at WARN
 * with no data (the vectors are constants; nothing about a user is involved), and from then on
 * admission refuses and a running attempt finishes with {@code CALCULATION_POLICY_UNVERIFIED}.
 */
@Component
class CalculationConformance {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CalculationConformance.class);

    private final boolean verified;

    CalculationConformance() {
        this(PlanCalculator::conformant);
    }

    /** For tests: the check to evaluate once instead of the calculator's own. */
    CalculationConformance(BooleanSupplier check) {
        this.verified = check.getAsBoolean();
        if (!this.verified) LOG.warn("calculation policy conformance check failed; plan publication is refused");
    }

    boolean verified() {
        return this.verified;
    }
}
