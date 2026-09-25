package com.bokrilog.operations;

import org.jspecify.annotations.Nullable;

/**
 * 지금 유효한 운영 제한을 읽는다. <b>판독 전용이다.</b>
 *
 * <p>쓰기 표면이 없다. 제한을 만들고 푸는 것은 Epic 3의 운영자 화면이며, 이 인터페이스에 그
 * 메서드를 미리 두면 소비자 없는 표면이 된다({@code ActionGrantService}가 HTTP 표면을 만들지
 * 않았던 것과 같은 판단).
 *
 * <p>시각의 권위는 데이터베이스다. 유효 구간 판정을 애플리케이션 시계로 하지 않는 이유는
 * {@code V3__action_grant.sql} 머리말과 같다 — 인스턴스마다 값이 다르고 뒤로 갈 수 있다.
 */
public interface OperationalRestrictionApi {

    /**
     * 그 command가 지금 막혀 있는가.
     *
     * <p>{@code null}·빈 command는 참이다. 이름을 모르는 요청을 "제한 없음"으로 접으면 그것이 곧
     * 제한을 우회하는 입력이 된다 — fail-closed가 이 API의 기본값이다.
     *
     * @param command 민감 작업의 이름. 허가의 {@code command}와 같은 문자열을 쓴다
     */
    boolean isRestricted(@Nullable String command);
}
