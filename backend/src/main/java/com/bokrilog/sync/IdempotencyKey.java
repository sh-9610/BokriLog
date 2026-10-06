package com.bokrilog.sync;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 호출자 멱등 키의 규칙 <b>한 벌</b>.
 *
 * <p>루트 컨트롤러 둘({@code PlanEndController}·{@code NextPlanBaselineController}), plan의 계획 조건 확정
 * ({@code PlanConditionService}), sync의 접수 둘({@code SyncJobService}·{@code PlanPublicationService}, 뒤의 것을
 * 증분 동기화·정밀 검증 접수가 재사용)이 각자 들고 있던 규칙 사본 5벌이 이것 하나다. 키는 호출자의 것이며 서버가 지어
 * 주지 않는다 — 불변 계획을 만드는 command는 호출자의 키 없이는 안전한 재전송이 없다.
 *
 * <p>규칙: {@value #MIN_LENGTH}~{@value #MAX_LENGTH}자, {@code [A-Za-z0-9._:-]}만, {@code ..} 없음, {@code -}로 시작하지
 * 않음.
 *
 * <p><b>거절 모양은 경로마다 다르다.</b> 422 {@link #REQUIRED_CODE}와 루트 {@code ApiErrorHandler}의 메시지 한 문장으로
 * 답하는 엄격 경로는 첫 발행·증분 동기화·정밀 검증·후속 기준 준비·계획 중도 종료 확정·Bybit 연결 해제 확정이다.
 * {@code FirstSnapshotController}의 첫 스냅샷 시작·초기 수집·판정·환산은 헤더가 없으면 UUID를 지어 넣고 규칙 위반은
 * 접수 거절(403/409)로 접힌다. {@code PlanConditionController}의 조건 확정은 code 없는 422다. 첫 스냅샷
 * {@code SyncJobService.admit}의 느슨한 규칙은 여기 들어오지 않는다(별도 결정).
 *
 * <p>{@code sync} 공개 패키지에 두는 이유: 루트·{@code plan}·{@code sync} 셋이 함께 쓰는데 모듈은 루트를 import하지
 * 못하고 {@code plan → sync} 의존은 이미 있다.
 */
public final class IdempotencyKey {

    /** 키를 싣는 요청 헤더. */
    public static final String HEADER = "Idempotency-Key";

    /** 키가 없거나 규칙에 맞지 않을 때의 안정적인 machine code(422). */
    public static final String REQUIRED_CODE = "IDEMPOTENCY_KEY_REQUIRED";

    /** 허용 길이의 하한(문자 수). 짧은 키는 추측·충돌이 쉽다. */
    public static final int MIN_LENGTH = 16;

    /** 허용 길이의 상한(문자 수). 저장 열의 폭과 같다. */
    public static final int MAX_LENGTH = 128;

    private static final Pattern ALPHABET = Pattern.compile("[A-Za-z0-9._:-]+");

    private IdempotencyKey() {}

    /**
     * 키가 규칙에 맞는가. {@code null}은 "없음"이며 거짓이다.
     */
    public static boolean isValid(@Nullable String key) {
        return key != null
                && key.length() >= MIN_LENGTH
                && key.length() <= MAX_LENGTH
                && ALPHABET.matcher(key).matches()
                && !key.contains("..")
                && !key.startsWith("-");
    }
}
