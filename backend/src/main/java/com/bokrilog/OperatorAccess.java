package com.bokrilog;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.identity.AccountLifecycleApi;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.OperatorProperties;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;

/**
 * 운영자 판정(Story 3.1 AC-1·AC-6).
 *
 * <p><b>세션에 역할을 심지 않는다.</b> 요청마다 배포 환경의 allowlist({@code (issuer, subject, 권한)})를 {@code identity}
 * 공개 API로 내부 {@code user_id}에 풀고 세션 주체와 비교한다. 그래서 allowlist에서 빠진 운영자는 다음 요청부터 바로
 * 비운영자이고, 확정 트랜잭션 안의 재검사({@code OperatorCommandService})도 같은 함수를 지난다. allowlist는 몇 건이라
 * 조회 비용이 무시할 만하다.
 *
 * <p>계정 상태도 함께 본다 — 삭제가 시작된 계정은 allowlist에 있어도 운영자가 아니다
 * ({@code AccountAccessApi.decide(SENSITIVE)}). 판정은 값 하나(권한 집합)이고 "왜 아닌가"를 구분하지 않는다. 호출자는
 * 빈 집합을 비운영자·권한 부족·삭제 계정 모두에 대해 같은 404로 옮긴다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다(인증 표면과 같은 조건).
 */
@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
class OperatorAccess {

    private final AccountLifecycleApi lifecycle;

    private final AccountAccessApi accounts;

    /**
     * 지금 allowlist. 운영에서는 기동 시 설정 그대로이고 바뀌지 않는다(회수는 재배포다). 시험이 {@link #reinterpret}로
     * 바꿔 회수를 흉내 낸다 — 요청마다 이 값을 다시 읽으므로 바뀐 값이 다음 판정에 바로 쓰인다.
     */
    private volatile List<OperatorProperties.Operator> allowlist;

    OperatorAccess(OperatorProperties properties, AccountLifecycleApi lifecycle, AccountAccessApi accounts) {
        this.lifecycle = lifecycle;
        this.accounts = accounts;
        this.allowlist = properties.allowlist();
    }

    /**
     * allowlist를 다시 해석한다 — <b>시험 훅</b>이다. 설정 원문을 같은 파서({@link OperatorProperties#parseOperators})로
     * 읽으므로 형식 오류는 같은 예외다. 제품 경로에서 부르는 곳이 없다(HTTP 표면도 없다).
     */
    void reinterpret(@Nullable String operators) {
        this.allowlist = OperatorProperties.parseOperators(operators);
    }

    /**
     * 그 사용자의 운영자 권한. 운영자가 아니면(또는 계정이 쓸 수 없는 상태면) 빈 집합이다.
     */
    Set<OperatorPermission> permissionsOf(@Nullable UUID userId) {
        if (userId == null) {
            return Set.of();
        }
        Set<OperatorPermission> granted = EnumSet.noneOf(OperatorPermission.class);
        for (OperatorProperties.Operator operator : this.allowlist) {
            UUID mapped = this.lifecycle.findUserIdBySocialIdentity(operator.issuer(), operator.subject()).orElse(null);
            if (userId.equals(mapped)) {
                granted.addAll(operator.permissions());
            }
        }
        if (granted.isEmpty()
                || this.accounts.decide(userId, userId, AccountAccessKind.SENSITIVE) != AccessDecision.ALLOWED) {
            return Set.of();
        }
        return granted;
    }

    /** 요구하는 권한을 모두 가졌는가. 빈 요구는 "운영자이기만 하면"이다. */
    boolean permits(@Nullable UUID userId, Set<OperatorPermission> required) {
        Set<OperatorPermission> granted = permissionsOf(userId);
        return !granted.isEmpty() && granted.containsAll(required);
    }

    /** 운영자인가(권한 하나 이상). 복귀 목적지 {@code /operator}의 판정이 쓴다. */
    boolean isOperator(@Nullable UUID userId) {
        return !permissionsOf(userId).isEmpty();
    }

    /**
     * 세션 목적지 접기 — {@code /operator}는 <b>지금</b> 운영자인 사용자에게만 목적지다. 아니면 {@code /dashboard}로 접는다.
     *
     * <p>목적지를 산출하는 모든 자리가 이 함수 하나를 부른다 — 로그인 성공, 재인증 시작, 인증된 세션의 인증 시작·콜백
     * 되돌림, 세션 확인 응답의 목적지 종류. 세션에 {@code /operator}로 굳은 값이 있어도 회수 뒤에는 어느 자리에서도
     * 운영자 목적지가 되지 않는다. 사용자를 모르면 접는다. 둘 다 평범한 이동이라 권한 유무가 목적지로 새지 않는다.
     */
    String foldDestination(@Nullable UUID userId, String destination) {
        return AuthFlowHandlers.OPERATOR_PATH.equals(destination) && !isOperator(userId)
                ? AuthFlowHandlers.DASHBOARD_PATH
                : destination;
    }
}
