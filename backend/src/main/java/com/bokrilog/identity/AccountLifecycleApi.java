package com.bokrilog.identity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 계정 생성과 인증 매핑 연결.
 *
 * <p>여기서 만들거나 바꾸는 트랜잭션은 모두 계정 상태 행 잠금을 잡고, 삭제 상태 술어를 쓰기
 * 문장 안에 넣어 판정과 쓰기를 원자적으로 만든다. 삭제 전환과 같은 경계에서 경합하며, 지는 쪽은
 * 아무것도 남기지 않는다.
 *
 * <p>이메일·표시명을 받지 않는다. 받지 않으므로 저장할 수도 없다. 이메일 일치로 계정을
 * 생성·병합·전환하지 않는다는 Epic 1의 불변식이 이 시그니처에 들어 있다.
 */
public interface AccountLifecycleApi {

    /**
     * 새 계정을 만든다.
     *
     * <p>내부 식별자는 서버가 만든다. 호출자가 준 값을 쓰지 않는다.
     *
     * @return 새 계정의 내부 {@code user_id}
     */
    UUID createAccount();

    /**
     * 인증 매핑을 계정에 연결한다.
     *
     * <p>{@code (issuer, subject)}는 전역 유일이고 {@code (userId, provider)}도 유일하다. 이미 다른
     * 계정에 연결된 식별자, 이미 같은 제공자를 가진 계정, 삭제가 시작됐거나 끝난 계정, 존재하지
     * 않는 계정, 그리고 삭제가 끝난 계정의 식별자(Story 5.4 재가입 차단 — {@link #isDeletedIdentity})는 모두
     * {@link AccessDecision#DENIED} 하나로 거부한다. 어느 계정에 연결돼 있었는지 밝히지 않는다.
     *
     * <p>비어 있거나 공백뿐인 값, 지나치게 긴 값도 같은 값으로 거부한다. 뜻 없는 값이 전역 유일
     * 슬롯을 영구히 점유하지 못하게 한다.
     *
     * <p>거부하면 아무것도 저장되지 않는다. 이 호출이 남기는 상태는 허용된 경우의 매핑 한 행뿐이다.
     *
     * <p>거부는 호출자의 트랜잭션도 건드리지 않는다. 호출자가 자기 트랜잭션 안에서 이 메서드를
     * 불러 거부를 받아도 그 트랜잭션은 그대로 이어지고 정상적으로 커밋된다.
     *
     * @param userId 연결 대상 계정
     * @param provider 제공자 이름
     * @param issuer 제공자 namespace
     * @param subject 제공자가 준 외부 식별자
     * @return 허용 또는 거부
     */
    AccessDecision linkSocialIdentity(
            @Nullable UUID userId,
            @Nullable String provider,
            @Nullable String issuer,
            @Nullable String subject);

    /**
     * 인증 매핑을 계정에서 떼어 낸다.
     *
     * <p><b>마지막 남은 수단은 떼어 낼 수 없다.</b> 그 판정이 이 API 안에 있는 것이 계약이다 —
     * 호출자가 먼저 세고 나중에 지우는 형태를 만들면 두 문장 사이에 다른 요청이 들어와 둘 다
     * 통과하고, 그 순간 사용자는 자기 계정에서 영구히 잠긴다. 여기서는 계정 생명주기 행을 잠근 뒤
     * <b>한 문장</b>이 {@code ACTIVE}·대상 매핑 존재·남은 수단 2개 이상을 함께 판정한다
     * ({@code linkSocialIdentity}가 상태 술어를 INSERT 안에 넣은 것과 같은 관용).
     *
     * <p>거부는 하나다. 마지막 수단, 연결되지 않은 제공자, 존재하지 않는 계정, 삭제가 시작됐거나
     * 끝난 계정, 받아들일 수 없는 값이 모두 {@link AccessDecision#DENIED}로 접힌다 — 호출자는 그
     * 값에서 대상 계정이 어떤 수단을 몇 개 갖고 있었는지 복원할 수 없다.
     *
     * <p>거부하면 아무것도 지워지지 않는다. 그리고 거부가 호출자의 트랜잭션을 건드리지 않는다
     * ({@link #linkSocialIdentity}와 같은 근거) — 삭제 0행은 값이지 예외가 아니다.
     *
     * <p>떼어 낸 매핑을 되살리는 경로는 없다. 같은 {@code (issuer, subject)}로 다시 로그인하면 그
     * 식별자는 어느 계정에도 연결돼 있지 않으므로 신규 가입 경로를 지난다. 해제는 재가입 차단 기록을 남기지 않는다 — 그 기록은
     * 계정 삭제만 남긴다(Story 5.4).
     *
     * @param userId 대상 계정
     * @param provider 떼어 낼 제공자 이름
     * @return 허용 또는 거부
     */
    AccessDecision unlinkSocialIdentity(@Nullable UUID userId, @Nullable String provider);

    /**
     * 이 계정에 새 세션을 만들어도 되는지 판정한다.
     *
     * <p>{@code ACTIVE}가 아니면 거부한다. payload와 매핑이 모두 삭제된 뒤에도 생명주기 guard 행이
     * 남아 이 판정을 계속 거부한다.
     *
     * @param userId 세션을 만들려는 계정
     * @return 허용 또는 거부
     */
    AccessDecision decideSessionCreation(@Nullable UUID userId);

    /**
     * 이 인증 매핑에 연결된 계정을 찾는다.
     *
     * <p>재로그인 경로의 유일한 진입이다. 인증 표면은 검증을 통과한 {@code (issuer, subject)} 하나로
     * 여기에 묻고, 계정을 찾는 다른 경로를 새로 만들지 않는다.
     *
     * <p>빈 값은 하나의 답이다. 매핑이 없는 계정(신규 가입), 삭제가 시작된 계정, 삭제가 끝난 계정,
     * 그리고 비어 있거나 지나치게 긴 입력이 모두 빈 값으로 접힌다. 호출자는 이 값에서 "그
     * 식별자로 만들어진 계정이 있었는가"를 복원할 수 없다. 그것이 {@link AccessDecision#DENIED}가
     * 사유를 노출하지 않는 것과 같은 계약이다.
     *
     * <p>이메일·표시명을 받지 않는다. 이메일이 같아도 {@code subject}가 다르면 다른 식별자이며,
     * 이 조회는 빈 값을 돌려주어 호출자가 신규 가입으로만 처리하게 만든다. 병합·전환 경로가
     * 시그니처에 없다.
     *
     * @param issuer 제공자 namespace
     * @param subject 제공자가 준 외부 식별자
     * @return 연결된 활성 계정의 내부 {@code user_id}, 없으면 빈 값
     */
    Optional<UUID> findUserIdBySocialIdentity(@Nullable String issuer, @Nullable String subject);

    /** 재가입 차단 기록에서 본 이 인증 매핑의 상태(Story 5.4). 계정 ID를 담지 않는다. */
    enum DeletedIdentityState {
        /** 기록이 없다 — 새 매핑을 만들 수 있다. */
        NONE,
        /** 기록이 있지만 그 계정의 삭제가 아직 끝나지 않았다(뒤 단계가 남았다). 새 매핑을 만들지 않는다. 로그인 표지는 {@code failed}다. */
        DELETION_UNFINISHED,
        /** 기록이 있고 그 계정의 삭제가 끝났다({@code DELETED}). 새 매핑을 만들지 않는다. 로그인 표지는 {@code deleted}다. */
        DELETED,
        /** 이 프로세스가 기록을 대조할 수 없다(digest를 만들 수 없다). fail-closed — 새 매핑을 만들지 않는다. 표지는 {@code failed}다. */
        UNVERIFIABLE
    }

    /**
     * 이 인증 매핑의 재가입 차단 상태(Story 5.4).
     *
     * <p>계정 삭제는 소셜 로그인 연결을 지우기 전에 그 {@code (issuer, subject)}의 keyed digest를 남긴다({@link DeletedIdentityDigests}). 이
     * 판정은 그 기록과, 기록을 남긴 삭제 실행의 계정이 {@code DELETED}인가만 본다 — 계정 ID를 돌려주지 않는다. {@link DeletedIdentityState#NONE}
     * 이 아니면 로그인은 계정을 만들지 않는다. 매핑 연결({@link #linkSocialIdentity})도 같은 기록을 INSERT 술어로 본다(상태와 무관하게).
     *
     * <p>해제 command·문의 경로는 없고 기한이 해제다(G-5 전 결정 D-4, {@link AccountDeletionRunApi#expireDeletedIdentities}) — 그 삭제의
     * 전체 삭제 완료가 기록되면(실행 없는 기록은 기록 뒤 35일) 기록이 지워지고 같은 로그인 수단의 가입은 새 계정이 된다. 받아들일 수 없는 입력은 {@link DeletedIdentityState#NONE}이다(그 값은 어차피
     * 매핑이 될 수 없다).
     */
    DeletedIdentityState deletedIdentityStateOf(@Nullable String issuer, @Nullable String subject);

    /**
     * 이 인증 매핑이 삭제가 <b>끝난</b> 계정의 것이었는가 — {@link #deletedIdentityStateOf}가 {@link DeletedIdentityState#DELETED}인가.
     * 참이면 로그인은 표지 {@code deleted}로 닫힌다. 그 identity를 가진 본인에게만 보이는 구분이다(검증을 통과한 로그인 직후의 같은
     * 브라우저) — 다른 계정·대상의 접근 거부는 여전히 {@link AccessDecision#DENIED} 하나이고, 삭제가 시작됐지만 끝나지 않은 계정은 그대로
     * {@code failed}다.
     */
    boolean isDeletedIdentity(@Nullable String issuer, @Nullable String subject);

    /**
     * 이 계정에 연결된 제공자 이름.
     *
     * <p>소유자에게 자기 계정의 사실을 보여 주기 위한 읽기 전용 조회다. 반환은 <b>제공자 이름
     * 집합</b> 하나이며 {@code issuer}·{@code subject}·연결 시각을 담지 않는다. 담으면 그 값이
     * 화면과 로그를 지나 외부 식별자가 밖으로 나가는 경로가 되고, 이름만으로는 어느 계정의
     * 것인지도 복원되지 않는다.
     *
     * <p>빈 집합은 하나의 답이다. 연결이 없는 계정, 삭제가 시작된 계정, 삭제가 끝난 계정,
     * 존재하지 않는 계정, {@code null} 입력이 모두 빈 집합으로 접힌다 — 호출자는 이 값에서 대상
     * 계정의 존재 여부를 복원할 수 없다. {@link AccessDecision#DENIED}가 사유를 노출하지 않는 것과
     * 같은 계약이다.
     *
     * <p>이 조회로 <b>남의 계정</b>을 물을 수는 있지만 답은 언제나 그 계정의 사실이므로, 호출자가
     * 세션이 결정한 {@code user_id}만 건네는 것은 인증 표면의 계약이다
     * ({@code AccountAccessApi#decide}의 {@code callerUserId}와 같은 규율).
     *
     * @param userId 조회 대상 계정
     * @return 연결된 제공자 이름, 없으면 빈 집합
     */
    Set<String> findLinkedProviders(@Nullable UUID userId);
}
