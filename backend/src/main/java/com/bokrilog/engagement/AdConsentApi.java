package com.bokrilog.engagement;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 문맥형 광고 동의(Story 4.1, {@code AD-CONSENT-1.0}).
 *
 * <p><b>상태는 셋이다.</b> {@link State#UNDECIDED}(결정 행이 없거나 최신 행의 정책 버전이 지금 버전과 다르다),
 * {@link State#GRANTED}, {@link State#DENIED}. 정책이 바뀌면 다시 묻는다 — 옛 버전의 동의를 새 버전으로 옮기는 자동 재동의가
 * 없다. Story 4.2가 판정할 "동의 유효"는 {@link View#granted()} 하나다(지금 정책 버전의 {@code GRANTED}).
 *
 * <p><b>결정은 추가 전용이다.</b> 동의·비동의·철회가 모두 결정 행 하나를 더한다(철회는 {@code DENIED} 행이다). 지금 상태와 같은
 * 선택은 행을 만들지 않는다. 시각의 권위는 DB 시각이고, 멱등의 권위는 {@code (user_id, 키 digest)} 유일 색인이다.
 *
 * <p><b>쓰기 순서.</b> 한 트랜잭션에서 사용자 advisory 잠금 → {@code AccountAccessApi.lockWriteAccess}(거짓이면
 * {@link Decision.Denied}, 아무것도 쓰지 않는다) → 멱등 판정 → INSERT. 같은 사용자의 동시 결정은 잠금이 줄 세우고, 최신 상태는
 * 나중에 커밋한 결정이다.
 *
 * <p>이 모듈은 HTTP·세션을 모른다. 호출자(루트)가 인증·계정 접근·멱등 키의 모양을 먼저 본다.
 */
public interface AdConsentApi {

    /** 지금 동의 정책 버전. 동의 설명(공급자·범위·전달 범주)이 바뀌면 새 버전으로 올린다 — 그 순간 모두가 미결정이 된다. */
    String POLICY_VERSION = "AD-CONSENT-1.0";

    /** 사용자의 선택. 스키마 CHECK와 같은 집합이다. */
    enum Choice {
        GRANTED,
        DENIED
    }

    /** 지금 상태. */
    enum State {
        /** 결정이 없거나 옛 정책 버전의 결정뿐이다. */
        UNDECIDED,
        GRANTED,
        DENIED
    }

    /**
     * 지금 상태.
     *
     * @param policyVersion 지금 정책 버전({@link #POLICY_VERSION}). 화면이 결정 요청에 그대로 싣는다
     * @param decidedAt 지금 상태를 만든 결정의 DB 시각. {@link State#UNDECIDED}면 {@code null}
     */
    record View(State state, String policyVersion, @Nullable Instant decidedAt) {

        /** 미결정. */
        public static View undecided() {
            return new View(State.UNDECIDED, POLICY_VERSION, null);
        }

        /**
         * 광고 동의가 유효한가 — Story 4.2의 자격 판정이 읽는 값이다. 상태 하나만 본다: 모듈은 옛 정책 버전의 결정을
         * {@link State#UNDECIDED}로 접어 돌려주므로 {@code GRANTED}인 View는 언제나 지금 정책 버전의 동의다.
         */
        public boolean granted() {
            return this.state == State.GRANTED;
        }
    }

    /** 결정 요청의 결말. */
    sealed interface Decision {

        /**
         * 결정이 반영됐다. 새 행을 썼거나, 지금 상태와 같은 선택이라 쓸 것이 없었거나, 같은 키의 앞선 결정을 돌려줬다.
         *
         * @param view 지금 상태(같은 키의 재전송이면 그 사이 다른 결정이 있었어도 지금 상태다)
         * @param replayed 같은 키·같은 입력의 앞선 결정을 돌려줬다(새 행이 없다)
         */
        record Saved(View view, boolean replayed) implements Decision {}

        /** 요청의 정책 버전이 지금 버전과 다르다. 아무것도 쓰지 않았다. */
        record PolicyChanged() implements Decision {}

        /** 같은 키가 다른 입력에 이미 쓰였다. 아무것도 쓰지 않았다. */
        record Conflict() implements Decision {}

        /** 쓰기 잠금 아래에서 계정이 쓰기를 받지 않는다(삭제 시작 등). 아무것도 쓰지 않았다. */
        record Denied() implements Decision {}
    }

    /** 그 사용자의 지금 상태. 읽기만 한다. */
    View current(UUID userId);

    /**
     * 결정한다. 멱등 키의 모양은 호출자가 먼저 본다 — 여기에는 digest만 남는다.
     *
     * @param idempotencyKey 규칙을 지난 멱등 키 원문
     * @param policyVersion 사용자가 본 설명의 정책 버전. 지금 버전과 다르면 {@link Decision.PolicyChanged}다
     */
    Decision decide(UUID userId, String idempotencyKey, Choice choice, String policyVersion);
}
