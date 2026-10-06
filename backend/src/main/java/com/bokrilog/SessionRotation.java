package com.bokrilog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 민감 command 실행 성공 뒤의 세션 처리 한 벌 — <b>재인증 시각 제거 + 세션 ID 회전</b>.
 *
 * <p>{@code SECURITY-ACTION-1.0}이 허가 소비 성공에 요구하는 회전이고 1.9의 결과 타입 이름
 * ({@code ActionGrantService.Consumption.ConsumedRequiringSessionRotation})이 각 서비스로 넘긴 의무다. Bybit 연결·
 * 계획 중도 종료·연결 해제가 들고 있던 {@code private static rotate} 사본 셋과, 수단 해제({@code UnlinkService.settleSessions})·
 * 수단 연결({@code AuthFlowHandlers.rotateIfLinked})의 인라인 {@code changeSessionId} 둘이 이것 하나로 모였다.
 *
 * <p><b>회전과 함께 재인증 시각을 지운다.</b> 본인 확인 1회는 command 1건이다. 지우지 않으면 재인증 한 번이 5분 안에서
 * 민감 command 여러 건을 연쇄 승인한다(Epic 1 회고 item-1). 호출 자리는 각 서비스의 <b>실행 플래그</b>
 * ({@code stored}·{@code ran}·{@code removed}·{@code LINKED})가 참인 곳이다 — 허가 포트는 처음 소비와 저장된 결과의
 * 재전송을 같은 타입으로 답하므로, "업무 변경이 이번 요청에서 실제로 실행됐다"를 아는 것은 그 플래그뿐이다. 거부·
 * 재전송·검증 실패에서는 부르지 않으며, 그래서 같은 무장으로 다시 시도할 수 있고 재전송은 회전하지 않는다.
 *
 * <p>순서는 제거 → 회전이다. {@code changeSessionId}는 속성을 보존하므로 순서가 결과를 바꾸지는 않지만, 회전이 실패한
 * 경로에서도 시각은 이미 지워져 있어야 연쇄 승인이 닫힌다.
 */
final class SessionRotation {

    private SessionRotation() {}

    /**
     * 커밋 뒤 세션 처리. <b>업무 변경이 실제로 실행된 요청에서만</b> 부른다. 예외를 삼킨다.
     *
     * <p>여기서 실패해도 command는 성공이다. 업무 변경은 이미 커밋됐고 이 처리는 그 뒤의 곁다리이므로, 예외를
     * 올려보내면 클라이언트가 "실패했다"를 보면서 실제로는 실행된 상태가 된다 — 그 불일치가 정리 실패보다 나쁘다. 다음
     * 요청이 같은 세션 상태를 다시 본다.
     */
    static void completeSensitiveCommand(HttpServletRequest request) {
        try {
            rotateAfterSensitiveCommand(request);
        } catch (RuntimeException ignored) {
            // 다음 요청이 같은 세션 상태를 다시 본다. 업무 변경 자체는 이미 커밋됐다.
        }
    }

    /**
     * {@link #completeSensitiveCommand}의 본체. 예외를 전파하고 회전한 새 세션 id를 돌려준다.
     *
     * <p>연결 콜백({@code AuthFlowHandlers.rotateIfLinked})이 이 모양을 쓴다 — 거기서는 판정과 회전을 한 자리에 모아
     * 회전 횟수를 HTTP 밖에서 세는 시험이 있고, 그 시험이 보는 반환·예외 모양을 바꾸지 않는다.
     *
     * @return 회전한 새 세션 id
     */
    static String rotateAfterSensitiveCommand(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            ReauthenticationFlow.clearAuthentication(session);
        }
        return request.changeSessionId();
    }
}
