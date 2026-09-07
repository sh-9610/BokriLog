package com.bokrilog.identity;

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
     * 않는 계정은 모두 {@link AccessDecision#DENIED} 하나로 거부한다. 어느 계정에 연결돼 있었는지
     * 밝히지 않는다.
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
     * 이 계정에 새 세션을 만들어도 되는지 판정한다.
     *
     * <p>{@code ACTIVE}가 아니면 거부한다. payload와 매핑이 모두 삭제된 뒤에도 생명주기 guard 행이
     * 남아 이 판정을 계속 거부한다.
     *
     * @param userId 세션을 만들려는 계정
     * @return 허용 또는 거부
     */
    AccessDecision decideSessionCreation(@Nullable UUID userId);
}
