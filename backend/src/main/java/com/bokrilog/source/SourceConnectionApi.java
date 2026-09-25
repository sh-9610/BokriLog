package com.bokrilog.source;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 금융 source 연결의 검증·저장·조회·삭제.
 *
 * <p><b>검증과 저장이 갈라져 있다.</b> {@link #verify}는 외부 호출만 하고 데이터베이스에 닿지
 * 않으며, {@link #store}는 데이터베이스만 쓰고 외부로 나가지 않는다. 한 메서드로 합치면 제공자
 * 왕복 동안 트랜잭션이나 계정 잠금이 열려 있게 되고, 그것이 AD-21과 이 스토리의 Always가 함께
 * 금지한 모양이다. 경계는 허가 포트가 소유하므로({@code ActionGrantService}) 호출자가 그 순서를
 * 지키는 것이 배선의 조건이다 — 먼저 열면 포트가 거절한다.
 *
 * <p>거부는 값이다. 예외로 알리지 않는 이유는 거절이 정상 경로이기 때문이며, 실패(구성 오류·
 * 저장소 장애)는 그대로 예외로 올라간다.
 */
public interface SourceConnectionApi {

    /** 활성 연결 하나의 요약. 자격정보도 식별자도 담지 않는다. */
    record ActiveConnection(UUID connectionId, Instant verifiedAt, String policyVersion) {}

    /** 저장의 결과. */
    sealed interface StoreOutcome {

        /**
         * 자격정보·연결·제공자 계정이 저장됐다.
         *
         * @param connectionId 이번에 만들어진 연결
         * @param providerAccountId 재사용됐거나 새로 만들어진 <b>안정 식별자</b>
         */
        record Stored(UUID connectionId, UUID providerAccountId) implements StoreOutcome {}

        /** 저장되지 않았다. 호출자는 이 값을 받으면 자기 트랜잭션을 되돌려야 한다. */
        record Rejected(SourceRejectionReason reason) implements StoreOutcome {}
    }

    /**
     * 저장까지 갈 수 있는 구성인가. <b>제공자에 닿기 전에 답한다.</b>
     *
     * <p>이 판정이 admission 게이트에 있어야 하는 이유는 순서다. 암호화 key ring이나 제공자
     * base URL·운영 reserved IP가 비면 {@link #store}가 {@link SourceRejectionReason#UNAVAILABLE}로
     * 끝나는데, 그 자리는 이미 제공자 왕복 <b>뒤</b>다 — 사용자의 진짜 자격정보가 서명된 요청
     * 세 번으로 거래소에 도착한 다음에야 "지금은 연결할 수 없다"를 알리는 셈이 된다. 제공자 등록의
     * fail-closed는 제공자에 닿기 전에 닫는다({@code AuthSecurityConfig})가 이 스토리가 따르기로
     * 한 관용이므로 같은 자리에서 닫는다.
     *
     * <p>어느 값이 비었는지는 이 값에서도 응답에서도 로그에서도 복원되지 않는다.
     */
    boolean isConnectable();

    /**
     * 그 사용자에게 활성 연결이 있는가.
     *
     * <p>admission의 사전 확인이며 <b>권위가 아니다</b>. 권위는 부분 유일 색인
     * ({@code source_connection_active_user_key})이고, 이 조회는 외부 호출 0건으로 끝낼 수 있는
     * 요청을 먼저 걸러 낸다.
     */
    boolean hasActiveConnection(@Nullable UUID userId);

    /** 그 사용자의 활성 연결. 없으면 빈 값이며 미존재·삭제 계정도 같은 값이다. */
    Optional<ActiveConnection> findActiveConnection(@Nullable UUID userId);

    /**
     * 자격정보를 제공자에게 검증한다. <b>데이터베이스에 닿지 않는다.</b>
     *
     * <p>자동 재시도하지 않는다. 시간초과·rate limit·5xx를 포함해 한 번 실패하면 그대로 거절이며,
     * 다시 시작하는 것은 사용자의 조작이다(AC-5·AD-21).
     */
    SourceVerification verify(SourceProvider provider, SourceCredentials credentials);

    /**
     * 검증 결과와 자격정보를 저장한다. <b>호출자의 트랜잭션에 참여한다.</b>
     *
     * <p>세 행을 함께 만든다 — 제공자 계정(재사용 가능), 연결, 암호화된 자격정보. 소유권·삭제
     * 상태를 여기서 <b>다시</b> 판정하며, 동시 요청은 부분 유일 색인이 갈라 낸다.
     *
     * @param credentials 평문. 이 호출 안에서 암호화되고 그 뒤 어디에도 남지 않는다
     */
    StoreOutcome store(
            @Nullable UUID userId,
            SourceProvider provider,
            SourceVerification.Verified verified,
            SourceCredentials credentials);

    /**
     * 이 사용자의 source 데이터를 지운다. <b>다른 사용자 것은 남는다.</b>
     *
     * <p>{@code identity}의 payload 삭제 <b>앞에</b> 돌아야 한다. 소유 테이블이
     * {@code identity_account}를 {@code ON DELETE RESTRICT}로 가리키므로 순서가 뒤집히면 외래키
     * 위반으로 소리 나게 실패한다 — 부분 삭제를 완료로 보고하지 않는다는 계약이 그 선택의 근거다
     * ({@code V4__source_connection.sql}).
     *
     * <p>멱등하다. 지울 것이 없는 계정에 다시 요청해도 실패가 아니다.
     */
    void eraseSourceData(@Nullable UUID userId);
}
