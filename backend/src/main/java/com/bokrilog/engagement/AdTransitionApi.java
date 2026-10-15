package com.bokrilog.engagement;

import java.util.UUID;

/**
 * 중립 전환 화면의 transition 소비(Story 4.3).
 *
 * <p><b>참조는 Story 4.2가 자격(ELIGIBLE)과 함께 발급한 불투명 transition이다.</b> 화면은 그 값 하나만 서버에 보내고, 서버는 그
 * 사용자의 자격 행에서만 찾는다 — 남의 참조·없는 참조·삭제가 시작된 계정은 모두 {@link Claim.NotFound}이고 아무것도 쓰지 않는다.
 *
 * <p><b>소비는 트랜잭션 하나다.</b> 사용자 advisory 잠금(자격 판정과 같은 이름) → {@code AccountAccessApi.lockWriteAccess}(거짓이면
 * {@link Claim.NotFound}) → 그 자격 행 {@code FOR UPDATE} → 판정 → 소비 시각 기록. 판정은 이미 소비됐으면 {@link SkipReason#CONSUMED},
 * 결정 뒤 {@link #CLAIM_WINDOW_SECONDS}초를 넘었으면 {@link SkipReason#EXPIRED}(DB 시각), 그 밖에는 자격 판정과 같은 관문을 같은
 * 순서로 다시 본다(동의 → 전역 {@code ADVERTISING} 운영 제한 → 공급자 활성). 광고를 시도하든 건너뛰든 소비 시각을 쓴다 — 이미 소비된
 * 행은 그대로 둔다. 그래서 같은 참조로는 광고가 한 번만 시도된다(자격 재전송이 같은 참조를 돌려줘도 그렇다).
 *
 * <p><b>판독 실패는 예외다(fail-closed).</b> 동의·운영 제한 판독이 실패하면 트랜잭션 전체가 롤백되고(소비도 없다) 호출자가 오류로
 * 답한다 — 화면은 광고 없이 결과로 간다.
 *
 * <p>이 모듈은 HTTP·세션을 모른다. 호출자(루트)가 인증·계정 접근·본문 모양을 먼저 본다.
 */
public interface AdTransitionApi {

    /** 소비 창(초). 자격 결정 뒤 이 시간을 넘긴 참조는 광고 없이 건너뛴다. */
    long CLAIM_WINDOW_SECONDS = 120;

    /** 건너뜀의 사유. */
    enum SkipReason {
        /** 이 참조는 이미 소비됐다(새로고침·뒤로 가기·재전송). */
        CONSUMED,
        /** 자격 결정 뒤 소비 창을 넘었다. */
        EXPIRED,
        /** 지금 정책 버전의 광고 동의가 없다(판정 뒤 철회 포함). */
        CONSENT_REQUIRED,
        /** 전역 {@code ADVERTISING} 운영 제한이 효력 중이다. */
        RESTRICTED,
        /** 광고 공급자가 활성이 아니다(플래그 또는 ad unit 경로). */
        PROVIDER_DISABLED
    }

    /** 소비의 결말. */
    sealed interface Claim {

        /**
         * 광고를 시도한다. 이 소비로 참조는 다시 쓸 수 없다.
         *
         * @param adUnitPath GPT ad unit 경로(구성 값). 사용자·결과를 식별하지 않는다
         */
        record Show(String adUnitPath) implements Claim {}

        /** 광고 없이 결과로 간다. 참조는 (처음 소비였으면 이번에) 소비됐다. */
        record Skip(SkipReason reason) implements Claim {}

        /** 그 사용자의 참조가 아니거나(남의 것·없는 것) 계정이 쓰기를 받지 않는다. 아무것도 쓰지 않았다. */
        record NotFound() implements Claim {}
    }

    /** 그 사용자의 transition 참조를 소비한다. */
    Claim claim(UUID userId, String transition);
}
