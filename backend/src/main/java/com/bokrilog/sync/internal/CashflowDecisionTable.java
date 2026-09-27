package com.bokrilog.sync.internal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Pure, versioned Bybit UTA cash-flow decision table. */
final class CashflowDecisionTable {
    static final String POLICY_VERSION = "BYBIT-CASHFLOW-1.0";
    private static final Set<String> EXCLUDED_TYPES = Set.of(
            "TRADE", "SETTLEMENT", "DELIVERY", "LIQUIDATION", "ADL", "FUNDING", "FEE", "FEE_REFUND",
            "INTEREST", "REBATE", "BONUS", "AIRDROP", "CURRENCY_BUY", "CURRENCY_SELL", "BORROW",
            "REPAY", "REPAYMENT", "CONVERT", "LOAN", "EARN", "STAKING", "TRANSFER_IN_INS_LOAN",
            "TRANSFER_OUT_INS_LOAN", "BONUS_TRANSFER_IN", "BONUS_TRANSFER_OUT");
    private static final Map<String, String> EXCLUSION_RULES = Map.ofEntries(
            Map.entry("TRADE", "UTA_VALUE_CHANGE"), Map.entry("SETTLEMENT", "UTA_VALUE_CHANGE"),
            Map.entry("DELIVERY", "UTA_VALUE_CHANGE"), Map.entry("LIQUIDATION", "UTA_VALUE_CHANGE"),
            Map.entry("ADL", "UTA_VALUE_CHANGE"), Map.entry("FUNDING", "UTA_FUNDING"),
            Map.entry("FEE", "UTA_FEE"), Map.entry("FEE_REFUND", "UTA_FEE"),
            Map.entry("INTEREST", "UTA_INTEREST"), Map.entry("REBATE", "UTA_REBATE"),
            Map.entry("BONUS", "UTA_BONUS"), Map.entry("AIRDROP", "UTA_AIRDROP"),
            Map.entry("CURRENCY_BUY", "UTA_CONVERT"), Map.entry("CURRENCY_SELL", "UTA_CONVERT"),
            Map.entry("BORROW", "UTA_BORROW"), Map.entry("REPAY", "UTA_REPAYMENT"),
            Map.entry("REPAYMENT", "UTA_REPAYMENT"), Map.entry("CONVERT", "UTA_CONVERT"),
            Map.entry("LOAN", "UTA_LOAN"), Map.entry("EARN", "UTA_EARN"),
            Map.entry("STAKING", "UTA_STAKING"), Map.entry("TRANSFER_IN_INS_LOAN", "UTA_INS_LOAN"),
            Map.entry("TRANSFER_OUT_INS_LOAN", "UTA_INS_LOAN"), Map.entry("BONUS_TRANSFER_IN", "UTA_BONUS_TRANSFER"),
            Map.entry("BONUS_TRANSFER_OUT", "UTA_BONUS_TRANSFER"));

    enum Verdict { EXTERNAL_IN, EXTERNAL_OUT, EXCLUDED, REVIEW_REQUIRED }
    record Decision(Verdict verdict, String reasonCode, String ruleId, String signedAmount) {}

    private CashflowDecisionTable() {}

    static Decision decide(String accountType, String eventType, String currency, String cashFlow) {
        if (!"UNIFIED".equals(accountType)) return review("ACCOUNT_SCOPE_MISMATCH", "REVIEW_ACCOUNT_SCOPE");
        if (eventType == null || currency == null || !currency.matches("[A-Z0-9]{1,32}") || cashFlow == null)
            return review("REQUIRED_EVIDENCE_MISSING", "REVIEW_REQUIRED_EVIDENCE");
        final BigDecimal amount;
        try { amount = new BigDecimal(cashFlow); }
        catch (NumberFormatException invalid) { return review("REQUIRED_EVIDENCE_MISSING", "REVIEW_REQUIRED_EVIDENCE"); }
        if (amount.signum() == 0) return review("ZERO_CASH_FLOW", "REVIEW_ZERO_FLOW");
        if (EXCLUDED_TYPES.contains(eventType)) {
            return new Decision(Verdict.EXCLUDED, "UTA_VALUE_CHANGE", EXCLUSION_RULES.get(eventType), null);
        }
        if (!"TRANSFER_IN".equals(eventType) && !"TRANSFER_OUT".equals(eventType))
            return review("UNRECOGNIZED_EVENT_TYPE", "REVIEW_UNKNOWN_TYPE");
        if ("TRANSFER_IN".equals(eventType) && amount.signum() > 0)
            return new Decision(Verdict.EXTERNAL_IN, "EXTERNAL_TRANSFER_IN", "EXTERNAL_TRANSFER_IN",
                    canonicalDecimal(amount.abs()));
        if ("TRANSFER_OUT".equals(eventType) && amount.signum() < 0)
            return new Decision(Verdict.EXTERNAL_OUT, "EXTERNAL_TRANSFER_OUT", "EXTERNAL_TRANSFER_OUT",
                    canonicalDecimal(amount.abs().negate()));
        return review("TYPE_SIGN_CONFLICT", "REVIEW_TYPE_SIGN_CONFLICT");
    }

    static String canonicalDecimal(BigDecimal value) {
        BigDecimal normalized=value.stripTrailingZeros();
        return normalized.signum()==0 ? "0" : normalized.toPlainString();
    }

    private static Decision review(String reason, String rule) {
        return new Decision(Verdict.REVIEW_REQUIRED, reason, rule, null);
    }
}
