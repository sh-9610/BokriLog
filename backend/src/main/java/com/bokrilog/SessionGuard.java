package com.bokrilog;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * 세션 상태 전환의 내부 표면.
 *
 * <p>여기에는 HTTP 표면이 없다. 컨트롤러도, {@code @Endpoint}도, 서블릿 타입도 참조하지 않는다.
 * 의심 세션을 만드는 기능이 일반 사용자 HTTP API로 노출되면 그 경로 하나가 곧 강제 로그아웃
 * 도구가 되고, 개발 전용 엔드포인트로 두면 프로필이 조용히 켜지는 날 그것이 운영에 남는다.
 * 개발·CI는 이 bean을 직접 불러 상태를 만든다.
 *
 * <p>{@code identity}에 두지 않는다. 그 모듈은 {@code org.springframework.http..}까지 의존이
 * 금지돼(`ModuleBoundaryTest`) 세션 표면을 가질 수 없고, {@code AccessDecision}은 두 값뿐이라
 * 세션 수준의 상태를 표현할 자리도 없다. {@code identity}의 {@code deleteSessionsOf}는 삭제 경로
 * 전용으로 그대로 남고, 이 스토리는 그 결합을 넓히지 않는다.
 *
 * <p>새 컬럼도 새 SQL도 만들지 않는다. {@code spring_session}에는 {@code reauth_required} 컬럼이
 * 없고 부가 상태가 사는 {@code ATTRIBUTE_BYTES}는 Java 직렬화라 SQL로 다룰 수 없다. 남는 길은
 * Spring Session 자신의 API로 상태를 담고 {@code PRINCIPAL_NAME} 색인
 * ({@code V2__spring_session.sql}의 {@code SPRING_SESSION_IX3})으로 사용자 단위 조작을 하는
 * 것이다. 그래서 V3 마이그레이션도, 새 의존도 없다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다. 일회성 migration 역할은
 * {@code spring.main.web-application-type: none}으로 뜨고 그 프로세스에는 세션 저장소 자동 구성이
 * 아예 없다({@code AuthFlowHandlers}·{@code AuthSessionController}와 같은 조건).
 */
@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
class SessionGuard {

    /**
     * 재인증 요구 표시.
     *
     * <p>Spring Session 속성 하나다. 이름에 접두사를 두는 것은 애플리케이션이 세션에 넣는 다른
     * 값({@code AuthFlowHandlers}의 두 속성)과 같은 관용이며, 이 문자열이 저장소에 남는 유일한
     * 표시다. 값은 {@link Boolean#TRUE} 하나뿐이고 사유·시각·출처를 담지 않는다 — 담으면 그것이
     * 곧 세션 저장소에 남는 내부 신호가 된다.
     */
    static final String REAUTH_REQUIRED_ATTRIBUTE = "bokrilog.session.reauth-required";

    /**
     * 세션 절대 수명.
     *
     * <p>값의 정의는 {@code application.yml}의 {@code bokrilog.session.absolute-lifetime} 하나다.
     * 여기 있는 상수는 그 값이 없는 구성에서 상한이 사라지지 않게 하는 fail-closed 바닥이며,
     * 두 값이 갈라지면 {@code AuthSessionExpiryTest}가 먼저 드러낸다.
     */
    static final Duration DEFAULT_ABSOLUTE_LIFETIME = Duration.ofHours(12);

    /**
     * 세션 수명 설정.
     *
     * <p>유휴 수명은 {@code spring.session.timeout}이 소유하고 Spring Session이 강제한다. 절대
     * 수명은 그 프레임워크에 대응 개념이 없어 이 애플리케이션이 소유한다.
     *
     * <p>{@link #DEFAULT_ABSOLUTE_LIFETIME}이 바닥이자 <b>천장</b>이다. Boot의 relaxed binding은
     * 어떤 {@code @ConfigurationProperties} 키에도 대응 환경 변수를 받으므로
     * ({@code BOKRILOG_SESSION_ABSOLUTELIFETIME}) "환경 변수로 받지 않는다"는 배선으로 성립하지
     * 않는다. 그래서 설정이 할 수 있는 일을 한 방향으로 좁힌다 — 상한을 <b>줄이는</b> 것만
     * 가능하고 늘리는 값은 조용히 무시되는 대신 이 바닥으로 접힌다. 그것이 fail-closed다.
     */
    @ConfigurationProperties("bokrilog.session")
    record SessionProperties(@Nullable Duration absoluteLifetime) {

        SessionProperties {
            /*
             * 값이 없거나 0 이하면 상한이 없는 것과 같다. 그것은 fail-open이므로 기본값으로 접는다.
             * 기본값보다 긴 값도 같은 이유로 접는다 — 환경이 상한을 조용히 늘릴 수 있게 두면 그
             * 완화의 출처를 추적할 자리가 없고, 정책 값을 바꾸는 것은 SPEC이 다시 협상할 일이다.
             * 정규화가 여기 하나뿐이어서 아래 `SessionGuard`에 null 검사도 범위 검사도 남지 않는다.
             */
            if (absoluteLifetime == null
                    || absoluteLifetime.isZero()
                    || absoluteLifetime.isNegative()
                    || absoluteLifetime.compareTo(DEFAULT_ABSOLUTE_LIFETIME) > 0) {
                absoluteLifetime = DEFAULT_ABSOLUTE_LIFETIME;
            }
        }
    }

    /**
     * 요청이 들고 온 세션의 판정.
     *
     * <p>{@link #EXPIRED}와 {@link #REAUTH_REQUIRED}의 <b>외부 관측은 같다</b> — 둘 다 보호 접근을
     * 차단하고 미인증 응답으로 수렴한다. 이 스토리에는 민감 command도 재인증 화면도 없으므로
     * (1.8·1.9 범위) 그래야 한다. 차이는 저장소 한 곳이다: 폐기는 세션 행을 지우고, 재인증 요구는
     * 행을 남겨 1.8의 재인증이 회전시킬 대상을 보존한다.
     */
    enum Verdict {

        /** 쓸 수 있는 세션. */
        ACTIVE,

        /** 절대 수명을 넘겼다. 이 요청에서 폐기한다. */
        EXPIRED,

        /** 재인증을 요구받았다. 행은 남기고 이 요청의 인증만 지운다. */
        REAUTH_REQUIRED
    }

    private final SessionStore<?> store;

    private final Duration absoluteLifetime;

    SessionGuard(
            FindByIndexNameSessionRepository<? extends Session> sessions, SessionProperties properties) {
        this.store = SessionStore.of(sessions);
        this.absoluteLifetime = properties.absoluteLifetime();
    }

    /** 구성된 절대 수명. 시험이 설정 파일의 값과 이 판정이 같은 값을 쓰는지 확인하는 데 쓴다. */
    Duration absoluteLifetime() {
        return this.absoluteLifetime;
    }

    /**
     * 세션 하나의 판정.
     *
     * <p>인자를 요청 경로가 이미 읽은 두 값으로 좁힌다. 세션 id로 받아 저장소를 다시 조회하면
     * 모든 요청에 질의가 하나 늘고, 그 질의가 보는 것은 요청이 이미 들고 있는 값과 같다.
     *
     * <p>절대 수명을 먼저 본다. 두 상태가 겹칠 때 남는 것은 더 강한 쪽이어야 한다 — 재인증으로
     * 되살릴 수 있는 세션과 수명이 끝난 세션은 다르다.
     *
     * @param creationTimeMillis 세션 {@code CREATION_TIME}. 요청·회전으로 늘어나지 않는 값이라
     *     절대 수명의 기준이 된다
     * @param reauthMarker 세션에 실린 {@link #REAUTH_REQUIRED_ATTRIBUTE} 값
     */
    Verdict evaluate(long creationTimeMillis, @Nullable Object reauthMarker) {
        Instant deadline = Instant.ofEpochMilli(creationTimeMillis).plus(this.absoluteLifetime);
        if (!Instant.now().isBefore(deadline)) {
            return Verdict.EXPIRED;
        }
        return Boolean.TRUE.equals(reauthMarker) ? Verdict.REAUTH_REQUIRED : Verdict.ACTIVE;
    }

    /**
     * 이 사용자의 세션 전부를 폐기한다.
     *
     * <p>{@code PRINCIPAL_NAME} 색인으로 대상을 고르므로 다른 사용자의 행은 어떤 경우에도 대상이
     * 되지 않는다. 조건절을 우리가 쓰지 않는 것이 요점이다.
     *
     * <p>조회와 삭제 사이에 로그인이 끼면 폐기된 사용자에게 살아 있는 세션이 남는다. 그래서 지운
     * 뒤 한 번 더 확인한다({@link SessionStore#REVOKE_ATTEMPTS}).
     *
     * @return 실제로 지운 세션 수
     */
    int revokeSessionsOf(UUID userId) {
        return this.store.deleteByPrincipal(principalName(userId));
    }

    /**
     * 세션 하나를 폐기한다.
     *
     * @return 그 세션이 있었는가
     */
    boolean revokeSession(String sessionId) {
        return this.store.delete(sessionId);
    }

    /**
     * 이 사용자의 세션 전부에 재인증 요구를 표시한다.
     *
     * <p>행은 남는다. 표시된 세션으로는 보호 접근이 차단되지만 1.8의 재인증이 그 세션을 회전시켜
     * 되살릴 수 있다.
     *
     * @return 표시한 세션 수
     */
    int requireReauthenticationOf(UUID userId) {
        return this.store.markReauthRequiredByPrincipal(principalName(userId));
    }

    /**
     * 세션 하나에 재인증 요구를 표시한다.
     *
     * @return 그 세션이 있었는가
     */
    boolean requireReauthentication(String sessionId) {
        return this.store.markReauthRequired(sessionId);
    }

    /**
     * 이 사용자가 가진 세션 수.
     *
     * <p>표시와 폐기가 행에 무엇을 했는지 확인하는 유일한 내부 경로다. 값은 개수뿐이고 세션 id도
     * 속성도 밖으로 나가지 않는다.
     */
    int sessionCountOf(UUID userId) {
        return this.store.countByPrincipal(principalName(userId));
    }

    /**
     * 내부 {@code user_id}가 {@code PRINCIPAL_NAME}에 실리는 모양.
     *
     * <p>{@code AuthFlowHandlers}가 로그인 성공 시 인증 주체의 이름으로 넣는 값과 같아야 한다.
     * 두 곳이 갈라지면 사용자 단위 조작이 조용히 0건이 되므로 변환을 한 곳에 둔다.
     */
    private static String principalName(UUID userId) {
        return userId.toString();
    }

    /**
     * 와일드카드로 받은 저장소의 타입 매개변수를 한 번만 붙들어 둔다.
     *
     * <p>{@link FindByIndexNameSessionRepository}의 {@code save}는 저장소가 만든 세션 타입을
     * 요구한다. 주입 지점에서 그 타입을 알 수 없어 {@code ? extends Session}으로 받으면 조회는
     * 되지만 저장이 컴파일되지 않는다. 여기서 capture 변환을 한 번 거쳐 타입을 붙들면
     * unchecked 캐스트도, 이 클래스의 소비자에게 새는 타입 매개변수도 없다.
     */
    private record SessionStore<S extends Session>(FindByIndexNameSessionRepository<S> repository) {

        static <S extends Session> SessionStore<S> of(FindByIndexNameSessionRepository<S> repository) {
            return new SessionStore<>(repository);
        }

        /**
         * 경합 재확인 횟수.
         *
         * <p>{@code AuthSessionService.MAX_ATTEMPTS}와 같은 값이고 같은 이유다. 첫 시도가 지는
         * 경우는 조회와 삭제 사이에 그 사용자의 로그인이 커밋됐을 때뿐이고, 그 세션 행은 이미
         * 커밋되어 있으므로 두 번째 시도의 조회가 반드시 본다. 무한 재시도로 두면 로그인이 계속
         * 들어오는 동안 이 호출이 돌아오지 않는다.
         */
        private static final int REVOKE_ATTEMPTS = 2;

        int deleteByPrincipal(String principalName) {
            int deleted = 0;
            for (int attempt = 0; attempt < REVOKE_ATTEMPTS; attempt++) {
                Map<String, S> found = this.repository.findByPrincipalName(principalName);
                if (found.isEmpty()) {
                    /*
                     * 남은 세션이 없다. 첫 시도에서 이 값을 보면 대상이 애초에 없었다는 뜻이고,
                     * 두 번째에서 보면 삭제가 실제로 비웠다는 뜻이다. 둘 다 여기서 끝난다.
                     */
                    return deleted;
                }
                for (String sessionId : found.keySet()) {
                    /*
                     * 세는 것은 지운 수다. 찾은 수를 돌려주면 그 사이에 사라진 행(유휴 만료 정리,
                     * 같은 사용자의 로그아웃)까지 "내가 지웠다"로 보고된다.
                     */
                    if (delete(sessionId)) {
                        deleted++;
                    }
                }
            }
            return deleted;
        }

        int markReauthRequiredByPrincipal(String principalName) {
            Map<String, S> found = this.repository.findByPrincipalName(principalName);
            for (S session : found.values()) {
                mark(session);
            }
            return found.size();
        }

        int countByPrincipal(String principalName) {
            return this.repository.findByPrincipalName(principalName).size();
        }

        boolean markReauthRequired(String sessionId) {
            S session = this.repository.findById(sessionId);
            if (session == null) {
                return false;
            }
            mark(session);
            return true;
        }

        boolean delete(String sessionId) {
            if (this.repository.findById(sessionId) == null) {
                return false;
            }
            this.repository.deleteById(sessionId);
            return true;
        }

        private void mark(S session) {
            session.setAttribute(REAUTH_REQUIRED_ATTRIBUTE, Boolean.TRUE);
            this.repository.save(session);
        }
    }
}
