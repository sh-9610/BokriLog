package com.bokrilog;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * 스텁 프로필이 <b>꺼진</b> 프로세스가 스텁 주소를 가리키지 않게 한다.
 *
 * <p>{@link DevBybitStubConfig}의 생성자 guard와 한 쌍이고 방향이 반대다. 그쪽은 "프로필이 켜졌는데
 * 실제 제공자를 가리킨다"를 막고 이쪽은 "프로필이 꺼졌는데 스텁을 가리킨다"를 막는다. 한 방향만
 * 두면 나머지 방향이 조용히 성립한다 — 그 스택에서는 스텁 경로에 라우트가 없어 제공자가 404를
 * 돌려주고, 그 404가 {@code AUTHENTICATION_REJECTED}로 접혀 사용자는 "키가 유효한지 확인하라"는
 * 고칠 수 없는 안내를 받는다.
 *
 * <p>빈 값은 통과시킨다. 그것은 "연결 기능을 쓰지 않는 스택"이며 이 스토리의 fail-closed가 이미
 * 덮는 상태다 — 기동을 죽이는 것은 잘못 가리키는 구성뿐이다.
 *
 * <p>어느 값이 문제인지 메시지에 적지 않는다. 환경 변수 이름은 응답에도 로그에도 남기지 않는 것이
 * 이 저장소의 계약이고 기동 실패 로그도 로그다({@code DevOidcStubConfig}와 같은 규율).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!" + DevBybitStubConfig.PROFILE)
class BybitProviderTargetGuard {

    BybitProviderTargetGuard(Environment environment) {
        String baseUrl = environment.getProperty("bokrilog.source.bybit.base-url", "").trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (pointsAtStub(baseUrl)) {
            throw new IllegalStateException(
                    "개발용 Bybit 스텁 주소는 그 프로필 없이 쓸 수 없다. "
                            + "스텁과 실제 제공자 중 하나만 구성한다.");
        }
    }

    private static boolean pointsAtStub(@Nullable String baseUrl) {
        return baseUrl != null && !baseUrl.isEmpty() && baseUrl.endsWith(DevBybitStubConfig.BASE_PATH);
    }
}
