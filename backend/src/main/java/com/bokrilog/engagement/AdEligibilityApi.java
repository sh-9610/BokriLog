package com.bokrilog.engagement;

import java.util.UUID;

/**
 * 수동 동기화 성공 결과의 광고 자격과 빈도 제한(Story 4.2).
 *
 * <p><b>결과 참조는 수동 동기화 attempt의 {@code jobId}다.</b> 후보는 그 사용자의 attempt 가운데 공식 결과를 원자 저장한 수동
 * 동기화뿐이다({@code SyncJobApi.publishedManualSync}) — 판정은 요청 본문이 아니라 attempt 행으로 한다. 남의 attempt·없는
 * attempt·후보가 아닌 attempt는 모두 {@link Decision.NotCandidate}이고 아무것도 쓰지 않는다.
 *
 * <p><b>판정은 트랜잭션 하나다.</b> 사용자 advisory 잠금(동의와 다른 이름) → {@code AccountAccessApi.lockWriteAccess}(거짓이면
 * {@link Decision.Denied}) → 같은 결과의 기존 결정 → 후보 → 관문(동의·운영 제한·공급자) → 빈도 제한(30분·{@code Asia/Seoul}
 * 하루 3회) → 결정 행 INSERT. 동시 탭은 잠금이 줄 세우므로 하나만 자격을 얻는다. 시각의 권위는 DB 시각이다.
 *
 * <p><b>결과 하나에 결정 하나.</b> 같은 {@code (user, jobId)}의 두 번째 요청은 행을 더하지 않고 빈도 제한도 소비하지 않는다
 * ({@code replayed}). <b>재전송은 결론을 낮추기만 한다:</b> 처음 판정이 자격이면 관문만 다시 보고, 걸리면 그 사유의 건너뜀을
 * 돌려준다(행은 그대로다) — 동의 철회와 운영 정지가 다음 요청부터 바로 적용된다. 처음 판정이 건너뜀이면 제한이 풀리거나 공급자가
 * 켜지거나 동의해도 그대로 돌려준다.
 *
 * <p><b>자격이 곧 reservation이다.</b> 자격 행은 노출 시도로 세어지고, Story 4.3의 중립 전환 화면이 쓸 불투명 transition 참조를
 * 함께 발급한다. 판정은 결과 접근을 막지 않는다 — 이 API는 결과 조회·동기화 경로가 부르지 않는다.
 *
 * <p>이 모듈은 HTTP·세션을 모른다. 호출자(루트)가 인증·계정 접근·본문 모양을 먼저 본다.
 */
public interface AdEligibilityApi {

    /** 건너뜀의 사유. 처음 걸린 관문이거나 빈도 제한이다. 스키마 CHECK와 같은 집합이다. */
    enum SkipReason {
        /** 지금 정책 버전의 광고 동의가 없다(미결정·비동의·철회). */
        CONSENT_REQUIRED,
        /** 전역 {@code ADVERTISING} 운영 제한이 효력 중이다. */
        RESTRICTED,
        /** 광고 공급자가 활성이 아니다(구성 {@code bokrilog.engagement.ad-provider-enabled}). */
        PROVIDER_DISABLED,
        /** 마지막 자격 뒤 30분이 지나지 않았다. */
        INTERVAL,
        /** {@code Asia/Seoul} 같은 날짜의 자격이 이미 3건이다. 간격과 함께 걸리면 이 사유다. */
        DAILY_CAP
    }

    /** 판정의 결말. */
    sealed interface Decision {

        /**
         * 광고를 시도해도 된다.
         *
         * @param transition 서버가 보관한 불투명 transition 참조. 사용자·결과를 식별하지 않는다
         * @param replayed 같은 결과의 앞선 자격을 돌려줬다(새 행·reservation이 없다)
         */
        record Eligible(String transition, boolean replayed) implements Decision {}

        /**
         * 광고 없이 결과로 간다.
         *
         * @param replayed 같은 결과의 앞선 결정을 돌려줬다(새 행이 없다). 앞선 결정이 자격이었어도 지금 관문에 걸리면 이 결말이다
         */
        record Skipped(SkipReason reason, boolean replayed) implements Decision {}

        /** 그 사용자의 후보 attempt가 아니다(남의 것·없는 것 포함). 아무것도 쓰지 않았다. */
        record NotCandidate() implements Decision {}

        /** 쓰기 잠금 아래에서 계정이 쓰기를 받지 않는다(삭제 시작 등). 아무것도 쓰지 않았다. */
        record Denied() implements Decision {}
    }

    /** 그 결과의 광고 자격을 판정한다. 같은 결과의 재전송은 앞선 결정이다. */
    Decision decide(UUID userId, UUID syncJobId);
}
