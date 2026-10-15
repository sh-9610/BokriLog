package com.bokrilog.source;

/**
 * 연결이 거절된 이유.
 *
 * <p>{@code AccessDecision}과 달리 값이 여럿이다. 이유는 무엇이 노출되는가다 — 여기서 갈리는 것은
 * <b>사용자 자신이 제공자 콘솔에서 고칠 수 있는 사실</b>이지 남의 계정의 존재가 아니고, 구분하지
 * 않으면 사용자는 읽기 전용 키를 다시 발급해야 하는지 IP를 등록해야 하는지 UTA를 전환해야 하는지
 * 알 수 없다. 각 값은 화면에서 <b>다음 행동 하나</b>와 함께 나간다.
 *
 * <p>예외 둘이 그 원칙을 되짚는다. {@link #NOT_PERMITTED}는 소유권·삭제 상태·운영 제한·허가
 * 부재·이미 연결됨을 <b>하나로</b> 접고, {@link #PROVIDER_ACCOUNT_TAKEN}은 원인만 알릴 뿐 어느
 * 계정이 그 UID를 쓰고 있는지 말하지 않는다.
 *
 * <p>모르는 값과 새 필드는 여기 없다. 허용 목록 밖은 전부 fail-closed이며 낙관적 해석을 하지
 * 않는다 — 그 결과는 값에 따라 {@link #INSUFFICIENT_PERMISSION}이거나
 * {@link #UNSUPPORTED_ACCOUNT_MODE}이거나 {@link #RESPONSE_FORMAT}이다.
 */
public enum SourceRejectionReason {

    /**
     * 이 요청은 성립하지 않는다.
     *
     * <p>타 사용자·미존재 대상·삭제 상태·운영 제한·이미 있는 활성 연결이 구분되지 않는 하나다.
     * 호출자는 이 값에서 대상 계정의 존재나 상태를 복원할 수 없다.
     */
    NOT_PERMITTED,

    /** 그 메인 UID는 이미 다른 BokriLog 계정에 연결돼 있다. 상대 계정을 드러내지 않는다. */
    PROVIDER_ACCOUNT_TAKEN,

    /**
     * 검증한 main UID가 그 사용자의 활성 계획이 쓰는 Bybit 계정과 다르다(Story 2.17, {@code RECONNECT-1.0}).
     *
     * <p>사용자 자신의 사실이라 구분해 알린다 — 다른 계정을 쓰려면 기존 계획을 먼저 종료해야 한다. 어느 UID인지,
     * 계획이 무엇인지는 말하지 않는다.
     */
    ACCOUNT_MISMATCH,

    /** 입력의 모양이 아니다. 빈 값·공백·상한을 넘는 길이가 여기다. */
    INVALID_FORMAT,

    /** 제공자가 자격정보를 거절했다. */
    AUTHENTICATION_REJECTED,

    /** 쓰기 성격 permission이 붙어 있다. 읽기 전용 키를 다시 발급해야 한다. */
    WRITE_PERMISSION,

    /** 조회에 필요한 permission이 없다. */
    INSUFFICIENT_PERMISSION,

    /** 메인 UID가 아니다. 서브 계정 키로는 연결하지 않는다. */
    SUB_ACCOUNT,

    /** 운영 reserved IP가 그 키에 묶여 있지 않다. */
    IP_NOT_BOUND,

    /** 지원하지 않는 계정 모드다. UTA 2.0 전환이 필요하며 대행 업그레이드는 하지 않는다. */
    UNSUPPORTED_ACCOUNT_MODE,

    /** 지갑 조회가 성립하지 않았다. <b>0으로 대체하지 않는다.</b> */
    WALLET_UNAVAILABLE,

    /** 제공자 응답이 제때 오지 않았다. <b>자동 재시도하지 않는다.</b> */
    TIMEOUT,

    /** 제공자가 호출 빈도를 제한했다. 마찬가지로 자동 재시도하지 않는다. */
    RATE_LIMITED,

    /** 제공자가 응답하지 못했다. */
    PROVIDER_UNAVAILABLE,

    /** 응답의 스키마가 우리가 아는 모양이 아니다. 원문을 화면·로그에 싣지 않는다. */
    RESPONSE_FORMAT,

    /**
     * 연결 기능 자체가 구성되지 않았다.
     *
     * <p>암호화 key ring이나 제공자 base URL·운영 reserved IP가 비면 기동이 아니라 <b>이 기능</b>이
     * fail-closed다(제공자 등록과 같은 관용, {@code AuthSecurityConfig}). 어느 값이 비었는지는
     * 응답에도 로그에도 남기지 않는다.
     */
    UNAVAILABLE
}
