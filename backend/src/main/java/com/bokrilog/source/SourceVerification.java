package com.bokrilog.source;

import java.time.Instant;

/**
 * 제공자 검증의 결과. <b>provider-neutral DTO</b>다.
 *
 * <p>Bybit 응답 원문·permission 배열·지갑 값이 이 타입에 없다. 어댑터가 그것을 전부 소비해
 * 판정으로 접고, 이 패키지 밖으로 나가는 것은 "통과했는가"와 "안정 식별자가 무엇인가"뿐이다.
 * 계산 모듈이 Bybit 타입을 모른다는 계약(AD-15)이 여기서 시작된다.
 *
 * <p>총자산을 담지 않는다. 검증 조회를 <b>공식 현재가치 스냅샷으로 발행하지 않는다</b>는 것이
 * 이 스토리의 Never이고, 값을 들고 나오면 호출자가 그것을 표시하거나 저장할 자리가 생긴다.
 * 그 스냅샷은 Story 2.2가 자기 계약으로 만든다.
 */
public sealed interface SourceVerification {

    /**
     * 세 검증을 모두 통과했다.
     *
     * @param providerAccountRef 검증된 메인 계정 식별자. <b>안정 식별자</b>이며 자격정보·connection
     *     교체에도 재사용한다
     * @param verifiedAt admission이 통과한 시각
     * @param policyVersion 그때 적용된 연결 정책의 버전
     */
    record Verified(String providerAccountRef, Instant verifiedAt, String policyVersion)
            implements SourceVerification {}

    /** 하나라도 확인 불가·모호했다. 저장은 일어나지 않는다. */
    record Rejected(SourceRejectionReason reason) implements SourceVerification {}
}
