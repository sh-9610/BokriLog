package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CashflowDecisionTableTest {
    @Test
    void exactUnifiedTransfersRequireMatchingNonzeroSignAndPreserveSignedMagnitude() {
        assertThat(CashflowDecisionTable.decide("UNIFIED","TRANSFER_IN","BTC","2.50"))
                .isEqualTo(new CashflowDecisionTable.Decision(CashflowDecisionTable.Verdict.EXTERNAL_IN,
                        "EXTERNAL_TRANSFER_IN","EXTERNAL_TRANSFER_IN","2.5"));
        assertThat(CashflowDecisionTable.decide("UNIFIED","TRANSFER_OUT","USDT","-7"))
                .isEqualTo(new CashflowDecisionTable.Decision(CashflowDecisionTable.Verdict.EXTERNAL_OUT,
                        "EXTERNAL_TRANSFER_OUT","EXTERNAL_TRANSFER_OUT","-7"));
    }

    @Test
    void zeroContradictoryAndUnrecognizedSignalsFailClosed() {
        assertReview("TRANSFER_IN","0","ZERO_CASH_FLOW");
        assertReview("TRANSFER_IN","-1","TYPE_SIGN_CONFLICT");
        assertReview("TRANSFER_OUT","1","TYPE_SIGN_CONFLICT");
        assertReview("OTHER_TRANSFER_IN","3","UNRECOGNIZED_EVENT_TYPE");
        assertThat(CashflowDecisionTable.decide("CONTRACT","TRANSFER_IN","USDT","3").verdict())
                .isEqualTo(CashflowDecisionTable.Verdict.REVIEW_REQUIRED);
    }

    @Test
    void exactPolicyExclusionsAndTransferLikeNamesNeverBecomeFlows() {
        for (String type : new String[] {"TRADE","SETTLEMENT","DELIVERY","LIQUIDATION","ADL","FUNDING",
                "FEE","FEE_REFUND","INTEREST","REBATE","BONUS","AIRDROP","CURRENCY_BUY",
                "CURRENCY_SELL","BORROW","REPAY","REPAYMENT","CONVERT","LOAN","EARN","STAKING",
                "TRANSFER_IN_INS_LOAN","TRANSFER_OUT_INS_LOAN","BONUS_TRANSFER_IN","BONUS_TRANSFER_OUT"}) {
            var result=CashflowDecisionTable.decide("UNIFIED",type,"USDT","5");
            assertThat(result.verdict()).isEqualTo(CashflowDecisionTable.Verdict.EXCLUDED);
            assertThat(result.ruleId()).isNotBlank();
            assertThat(result.signedAmount()).isNull();
        }
    }

    @Test
    void requiredMalformedAndZeroEvidenceNeverFallsThroughToExclusion() {
        assertReview("FEE","not-a-number","REQUIRED_EVIDENCE_MISSING");
        assertReview("FEE","0","ZERO_CASH_FLOW");
        assertThat(CashflowDecisionTable.decide("UNIFIED","TRANSFER_IN","usdt","1").reasonCode())
                .isEqualTo("REQUIRED_EVIDENCE_MISSING");
    }

    private static void assertReview(String type,String cashFlow,String reason) {
        var result=CashflowDecisionTable.decide("UNIFIED",type,"USDT",cashFlow);
        assertThat(result.verdict()).isEqualTo(CashflowDecisionTable.Verdict.REVIEW_REQUIRED);
        assertThat(result.reasonCode()).isEqualTo(reason);
        assertThat(result.signedAmount()).isNull();
    }
}
