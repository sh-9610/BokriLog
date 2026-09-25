package com.bokrilog.identity.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountLifecycleApi;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 생성과 인증 매핑 연결.
 *
 * <p>매핑 연결은 잠금 → 검사 → 술어가 붙은 쓰기를 한 트랜잭션 안에서 한다. 상태 술어가 INSERT
 * 안에 있으므로 판정과 쓰기가 원자적이고, 거부된 호출은 아무것도 쓰지 않은 채 값 하나로 끝난다.
 *
 * <p>거부가 트랜잭션을 롤백으로 표시하지 않는다. 그 표시는 트랜잭션 전체에 걸리는 전역 값이라
 * 호출자가 자기 트랜잭션 안에서 이 메서드를 부르면 호출자의 커밋까지 함께 죽는다. 거부는 값이지
 * 호출자 트랜잭션에 대한 판결이 아니다.
 */
@Service
class AccountLifecycleService implements AccountLifecycleApi {

    /**
     * 제공자 이름의 길이 상한(바이트). `V1__identity_account.sql`의 CHECK와 같은 값이며 한쪽만
     * 바꾸면 이 모듈이 값으로 거부하던 입력이 데이터베이스 오류로 바뀐다.
     */
    private static final int MAX_PROVIDER_BYTES = 64;

    /**
     * {@code issuer}·{@code subject}의 길이 상한(바이트). 마찬가지로 스키마 CHECK와 한 쌍이다.
     * 둘을 합쳐도 {@code (issuer, subject)} 유일 인덱스의 btree 한계(약 2.7KB) 안이다. 실제 OIDC
     * 값은 issuer가 URL, subject가 제공자 식별자라 이 상한 근처에도 오지 않는다.
     */
    private static final int MAX_IDENTIFIER_BYTES = 512;

    private final IdentityAccountRepository repository;

    AccountLifecycleService(IdentityAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public UUID createAccount() {
        // 내부 식별자는 서버가 만든다. 이메일·표시명은 받지 않으므로 저장할 수도 없다.
        UUID userId = UUID.randomUUID();

        // 여기에는 잠금도 커밋 직전 재검사도 없다. 빠뜨린 것이 아니라 경합할 상대가 없어서다.
        // 이 UUID는 방금 이 트랜잭션이 만들었고 커밋 전까지 어디로도 나가지 않는다. 아무도 모르는
        // 식별자를 대상으로 삭제 전환을 요청할 수는 없으므로 잠글 행도, 재검사할 상태도 없다.
        // 잠금이 필요한 것은 이미 존재하는 계정을 건드리는 경로다(아래 linkSocialIdentity).
        repository.insertLifecycle(userId);
        repository.insertAccountPayload(userId);
        return userId;
    }

    @Override
    @Transactional
    public AccessDecision linkSocialIdentity(
            @Nullable UUID userId,
            @Nullable String provider,
            @Nullable String issuer,
            @Nullable String subject) {
        if (userId == null
                || !isAcceptable(provider, MAX_PROVIDER_BYTES)
                || !isAcceptable(issuer, MAX_IDENTIFIER_BYTES)
                || !isAcceptable(subject, MAX_IDENTIFIER_BYTES)) {
            return AccessDecision.DENIED;
        }

        // 잠금. 이 행을 붙든 동안 같은 계정의 삭제 전환은 여기서 기다린다.
        if (repository.lockLifecycleForUpdate(userId).orElse(null) != AccountState.ACTIVE) {
            // 아직 아무것도 쓰지 않았다. 되돌릴 것이 없으니 롤백 표시도 필요 없다.
            return AccessDecision.DENIED;
        }

        /*
         * 판정과 쓰기가 한 문장이다.
         *
         * 상태 술어(`WHERE state = 'ACTIVE'`)가 INSERT 안에 있고, 유일 인덱스 위반은
         * `ON CONFLICT DO NOTHING`이 예외가 아니라 0행으로 접는다. 그래서 거부된 호출은 아무것도
         * 쓰지 않고 데이터베이스 오류도 남기지 않는다. 되돌릴 것이 없으므로 롤백 표시가 필요 없다.
         *
         * 예전 모양은 먼저 INSERT하고 실패하면 `setRollbackOnly()`를 세웠다. 그 표시는 트랜잭션
         * 하나 전체에 걸리는 전역 값이라, 호출자가 자기 `@Transactional` 안에서 이 메서드를 부르면
         * (Story 1.4의 OIDC 콜백이 정확히 그 모양이다) 호출자의 커밋이
         * `UnexpectedRollbackException`으로 죽고 호출자가 한 작업까지 함께 사라졌다.
         *
         * 이 문장이 곧 "커밋 직전 재검사"다. 뒤에 재검사 SELECT를 따로 두지 않는 이유는 그것이
         * 관측 가능한 분기가 아니기 때문이다. 위에서 잡은 `FOR UPDATE` 잠금을 이 트랜잭션이 끝날
         * 때까지 들고 있으므로 같은 계정의 삭제 전환은 커밋 전에 상태를 바꿀 수 없고, 재검사는
         * 언제나 방금 본 값을 다시 본다. 즉 방어적 이중화일 뿐이며, 그 이중화를 남기면 도달하지
         * 않는 경로에 롤백 처리를 하나 더 두게 되고 그 롤백이 위의 문제를 그대로 되살린다.
         * 대신 술어를 쓰기와 원자적으로 묶어, 잠금 경로가 미래에 바뀌더라도 "삭제가 시작된 계정에
         * 매핑이 남는다"는 결과 자체가 나올 수 없게 했다.
         *
         * 여기서 `DataIntegrityViolationException`을 잡지 않는다. 기대된 거부는 모두 0행으로
         * 끝나므로 이 지점의 무결성 위반은 계약이 깨졌다는 뜻이고(예: ACTIVE인데 payload 행이
         * 없어 외래키가 걸리는 경우), 그것은 조용히 거부로 접을 것이 아니라 드러나야 한다.
         */
        int inserted = repository.insertSocialLinkIfActive(userId, provider, issuer, subject);
        return inserted == 1 ? AccessDecision.ALLOWED : AccessDecision.DENIED;
    }

    @Override
    @Transactional
    public AccessDecision unlinkSocialIdentity(@Nullable UUID userId, @Nullable String provider) {
        if (userId == null || !isAcceptable(provider, MAX_PROVIDER_BYTES)) {
            return AccessDecision.DENIED;
        }

        // 잠금. 같은 계정의 동시 해제가 여기서 직렬화된다. 이것이 없으면 두 트랜잭션이 각자의
        // 스냅숏에서 "아직 둘이다"를 보고 둘 다 통과해 마지막 수단까지 사라진다.
        if (repository.lockLifecycleForUpdate(userId).orElse(null) != AccountState.ACTIVE) {
            // 아직 아무것도 지우지 않았다. 되돌릴 것이 없으니 롤백 표시도 필요 없다.
            return AccessDecision.DENIED;
        }

        /*
         * 판정과 지우기가 한 문장이다.
         *
         * 상태 술어·대상 존재·"남은 수단 2개 이상"이 모두 DELETE의 술어 안에 있다. 그래서 거부된
         * 호출은 0행으로 끝나고 데이터베이스 오류도 롤백 표시도 남기지 않는다 — `linkSocialIdentity`가
         * `setRollbackOnly`를 쓰지 않는 것과 정확히 같은 근거이며(그 메서드의 긴 주석 참조), 이
         * 메서드도 호출자의 트랜잭션 안에서 불릴 수 있다(`UnlinkService`의 허가 소비 경계).
         *
         * 잠금과 술어 둘 다 필요하다. 위 잠금은 같은 계정의 동시 해제를 줄 세우고, 이 술어는 줄에서
         * 두 번째로 깨어난 요청이 이미 하나만 남은 상태를 보게 만든다. 잠금만 있으면 READ COMMITTED
         * 에서 각 문장이 새 스냅숏을 보긴 하지만 판정이 애플리케이션에 있으면 그 판정은 잠금 밖에서
         * 일어날 수 있고, 술어만 있으면 잠금 없는 동시 실행에서 두 DELETE가 같은 스냅숏을 본다.
         */
        int deleted = repository.deleteSocialLinkIfNotLast(userId, provider);
        return deleted == 1 ? AccessDecision.ALLOWED : AccessDecision.DENIED;
    }

    @Override
    @Transactional(readOnly = true)
    public AccessDecision decideSessionCreation(@Nullable UUID userId) {
        if (userId == null) {
            return AccessDecision.DENIED;
        }
        // guard 행이 없거나 ACTIVE가 아니면 거부다. payload가 삭제된 뒤에도 guard 행이 남아
        // 이 판정이 계속 거부하는 것이 삭제 상태 계약의 핵심이다.
        return repository.findLifecycle(userId).orElse(null) == AccountState.ACTIVE
                ? AccessDecision.ALLOWED
                : AccessDecision.DENIED;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findUserIdBySocialIdentity(
            @Nullable String issuer, @Nullable String subject) {
        // 거부 계약과 같은 모양이다. 받아들일 수 없는 입력은 예외가 아니라 빈 값으로 접힌다.
        // 여기서 예외를 던지면 호출자가 "그 값은 아예 저장될 수 없는 모양이었다"를 알게 되고,
        // 그것은 매핑 존재 여부와 다른 축의 정보 누출이 된다.
        if (!isAcceptable(issuer, MAX_IDENTIFIER_BYTES) || !isAcceptable(subject, MAX_IDENTIFIER_BYTES)) {
            return Optional.empty();
        }
        // 상태 술어는 질의 안에 있다. 삭제가 시작된 계정과 존재하지 않는 계정이 같은 빈 값이다.
        return repository.findActiveUserIdBySocialIdentity(issuer, subject);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> findLinkedProviders(@Nullable UUID userId) {
        if (userId == null) {
            // 거부 계약과 같은 모양이다. 예외가 아니라 다른 모든 빈 답과 구분되지 않는 빈 집합이다.
            return Set.of();
        }
        // 상태 술어는 질의 안에 있다. 미존재·삭제 진행·삭제 완료가 모두 빈 목록으로 접힌다.
        return Set.copyOf(repository.findLinkedProvidersOfActive(userId));
    }

    /**
     * 받아들일 수 있는 식별 문자열인지 본다.
     *
     * <p>{@code null}·빈 문자열·공백만인 값을 거부한다. 그런 값도 저장되면 전역 유일 슬롯
     * {@code (issuer, subject)}를 영구히 점유한다. 한 번 들어가면 그 뒤로 어떤 계정도 그 자리를
     * 쓸 수 없고, 아무도 그 매핑으로 로그인할 수 없다.
     *
     * <p>길이 상한은 스키마의 CHECK 제약과 같은 값이고, 그 CHECK와 여기가 한 쌍이다. 상한이
     * 애플리케이션에도 있어야 하는 이유는 위반의 모양 때문이다. CHECK 위반이든 btree 인덱스의
     * 크기 한계(약 2.7KB, SQLSTATE 54000)든 데이터베이스가 던지는 예외는 거부 값이 아니라
     * 트랜잭션을 abort시키는 오류이고, 그 트랜잭션이 호출자의 것이면 호출자의 작업까지 사라진다.
     * 길이는 여기서 먼저 값으로 거부하고, 스키마 CHECK는 이 모듈 밖에서 행이 들어올 경우의 최종
     * 방어로 남는다.
     *
     * <p>바이트로 센다. btree 한계도 CHECK의 {@code octet_length}도 바이트 단위이며, 한글·이모지가
     * 섞인 값에서 문자 수와 바이트 수가 크게 갈린다.
     */
    private static boolean isAcceptable(@Nullable String value, int maxBytes) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
