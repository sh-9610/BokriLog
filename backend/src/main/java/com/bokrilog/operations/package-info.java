/**
 * 운영 제한의 durable 판독 기반.
 *
 * <p><b>이 스토리는 읽기만 한다.</b> 제한을 만들고 푸는 운영자 표면은 Epic 3이 얹는다(Story
 * 3.1·3.2). 그럼에도 모듈이 지금 생기는 이유는 두 가지다 — {@code epics.md}의 Epic 2 독립 완료
 * 조건이 판독 기반의 소유자로 이 이름을 쓰고, Story 3.2가 선행으로 "Epic 2의 restriction 검사
 * 지점"을 든다. 판독 기반을 {@code source}에 두면 Epic 3이 그것을 옮겨야 하고 옮기는 동안
 * admission이 가리키는 곳이 바뀐다.
 *
 * <p>검사 지점은 넷이다 — admission·lease·publication·display. 이 스토리가 그중 <b>admission</b>
 * 하나를 실제로 연결하고 나머지는 그 기능을 만드는 스토리가 같은 API로 연결한다.
 *
 * <p>다른 모듈은 이 패키지의 공개 application API로만 협력하고 {@code internal} 하위 패키지나 소유
 * 테이블에 직접 닿지 않는다. {@code ModuleBoundaryTest}가 이를 강제한다. 여기에도 HTTP 표면은
 * 없다.
 *
 * <p>판독은 fail-closed로 쓴다. 제한이 <b>있으면</b> 막는 것이 이 API의 답이고, 읽지 못하는 상황
 * (저장소 장애)은 값이 아니라 예외로 올라가 요청 전체를 실패시킨다 — 조회 실패를 "제한 없음"으로
 * 접으면 그 순간 제한이 무의미해진다.
 *
 * <p><b>Story 3.1이 운영자 표면의 기반을 더했다.</b> 운영자 allowlist 설정({@link
 * com.bokrilog.operations.OperatorProperties}), 운영 command의 표준 envelope와 검증({@link
 * com.bokrilog.operations.OperatorCommandApi}), 추가 전용 감사 원장과 그 조회({@link
 * com.bokrilog.operations.OperatorAuditApi}), 감사 데이터의 deletion port({@link
 * com.bokrilog.operations.OperationsDeletionApi})다. 재인증·일회성 허가·세션 회전·HTTP는 루트 패키지가 Epic 1의
 * 부품으로 한다 — 이 모듈은 servlet·세션·허가를 모른다.
 *
 * <p><b>Story 3.2가 제한을 {@code 기능 × scope × target}으로 넓혔다.</b> 판독은 {@link
 * com.bokrilog.operations.OperationalRestrictionApi#evaluate} 하나이고({@code command} 문자열 판독은 없어졌다), 제한 행은
 * 운영 command({@code restrict-<기능>})가 감사 {@code COMPLETED}와 같은 트랜잭션에서만 만든다. 대상(계획·portfolio·연결)이
 * 있는지·누구의 것인지는 다른 모듈의 사실이라 이 모듈은 모른다 — 루트가 각 모듈 공개 API로 확인해 콜백으로 넘긴다.
 *
 * <p><b>Story 3.3이 강한 신호의 자동 정지를 더했다.</b> 판정표 {@code OPS-SIGNAL-1.0}은 버전 seed이고({@code
 * operations_signal_policy}), 감지 지점(sync)은 {@link com.bokrilog.operations.OperationalSignalApi#raise} 하나를 부른다.
 * raise는 자기 트랜잭션 하나에 신호 행·제한({@code origin='SIGNAL'})·감사 SYSTEM 행을 커밋하고 경보 WARN 한 줄을 남긴다.
 * 백그라운드 detector는 없다 — 감지 순간에 동기로 정지한다. 신호는 제한을 풀거나 줄이지 못한다.
 */
package com.bokrilog.operations;
