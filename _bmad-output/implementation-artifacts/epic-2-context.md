# Epic 2 Context: Bybit 자산으로 복리 계획을 기록하고 비교하기

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

읽기 전용 Bybit UTA 연결을 시작으로 정상 현재가치, 복리 계획, 자금흐름과 계산 근거를 기록해 대시보드·계획 생명주기·연결 해제까지 완성한다. 이 Epic은 자격정보·portfolio·불변 사실·작업 큐·원자 발행·결정적 십진 계산의 기반을 마련한다. 실제 Bybit 검증 계정과 regional reserved IP 연동, 광고 승인, 법률 검토는 별도 출시 게이트다.

## Stories

- Story 2.1: 읽기 전용 Bybit UTA 연결 검증·저장
- Story 2.2: 첫 정상 현재가치와 측정 시각 확인
- Story 2.3: 계획 조건 검증과 미리보기
- Story 2.4: 현재가치·원천 사건 완전 수집
- Story 2.5: 외부 자금흐름 판정
- Story 2.6: 사건 시점 USD 환산
- Story 2.7: 최초 계산·활성 계획·공식 결과 원자 발행
- Story 2.8: 여섯 지표와 세 신뢰 상태 대시보드
- Story 2.9: 공식 계산 입력·버전 근거 조회
- Story 2.10: 외부 자금흐름 조정 이력 조회
- Story 2.11: 증분 동기화와 결과 갱신
- Story 2.12: 이력 정밀 재검증과 늦은 사건 반영
- Story 2.13: 활성 계획 중도 종료
- Story 2.14: 만료 계획 완료 결과 확정
- Story 2.15: 후속 계획 생성
- Story 2.16: Bybit 연결 해제와 기록 보존

## Requirements & Constraints

- 사용자 세션의 내부 `user_id`가 조회·명령·작업·발행의 소유 범위다. 삭제 상태와 durable 운영 제한은 각 admission·발행·표시 경계에서 fail-closed로 확인한다.
- 금융값·비율·가격은 canonical decimal string으로 경계를 통과한다. JSON number, 지수 표기, NaN, Infinity, locale separator는 거부한다. 범위 오류는 0 대체 없이 실패한다.
- 저장은 UTC instant, 계획 입력·표시는 `Asia/Seoul`이다. 돈은 `BigDecimal`과 precision 34 `HALF_EVEN`으로 다루고 화면만 `HALF_UP` 2자리로 표시한다.
- 같은 입력·스냅샷·정책 버전은 결정적으로 재현한다. 금융 사실·계산 버전은 추가 전용이며 현재 결과는 명시적 포인터로 가리킨다.
- 실패·제한·부분 응답을 정상값이나 0으로 바꾸지 않는다. 기존 정상 결과를 보존하고 원인·다음 행동을 표시한다. 인증·권한·schema·semantic 오류는 자동 재시도하지 않는다.
- 작업 Worker는 lease·heartbeat·fencing으로 소유권을 관리하고 외부 호출 중 DB 트랜잭션을 열지 않는다. 오래되거나 lease를 잃은 Worker는 결과를 발행할 수 없다.
- 계획은 connection이 아닌 사용자 기본 `USD` portfolio에 귀속한다. MVP portfolio는 활성 Bybit source 한 개다. 계획 입력 미리보기(intent)는 활성 계획·공식 계산이 아니다.
- 모듈은 공개 API만 사용한다. 새 모듈의 구현·저장소는 `internal`에 두고 데이터 도입 스토리에서 삭제 경로와 시험도 만든다. 기존 Flyway 파일은 고치지 않고 다음 버전을 추가한다.

## Technical Decisions

- 소유권: `source`는 connection·자격정보·Bybit adapter, `portfolio`는 membership·snapshot·current pointer, `sync`는 작업·원천 사실·계산 결과·발행, `plan`은 불변 입력과 계획 규칙, `calculation`은 순수 계산을 소유한다. Adapter DTO는 provider-neutral이다.
- Bybit 지원은 main UTA와 읽기 전용 권한만 허용한다. 승인 reserved IP·UID·계정 모드·응답 구조를 모두 확인한 뒤 저장한다. 자격정보는 AES-256-GCM, 매번 새 96-bit nonce, 정의된 AAD와 외부 주입 key ring을 쓴다.
- 추가 전용 사실은 덮어쓰지 않는다. 같은 source revision은 no-op, 같은 ID의 핵심 필드 충돌은 격리한다. 현재 공식값을 최대 시각으로 추론하지 않는다.
- 공식 발행은 sync 소유의 짧은 `REQUIRED` 트랜잭션 안에서 모든 후보·구성요소·계산·포인터를 함께 기록한다. CAS 직전 lease·삭제·제한·소유권을 다시 확인한다.
- 계산 정책은 `CALC-1.0`, 달력 정책은 `CALENDAR-SEOUL-1.0`, 표시 정책은 `DISPLAY-1.0`이다. 목표율 입력은 화면 `%`, 저장 비율(예: 10%=`0.1`); 허용 범위 `0..10`, precision 18/scale 12다. 기간은 1일~1년, 주기는 일·주·월이다. 월 경계는 원래 일자를 기억해 월말 뒤 복귀한다.
- 고정 회귀 벡터 CV-01~CV-12가 단위·기간·자금흐름·월말·계산 범위를 결정한다. 0·음수 금액은 보존하고 해당 목표 비율은 계산 불가로 표현한다.

## UX & Interaction Patterns

- 첫 공식 현재가치는 Bybit `totalEquity`와 Bybit 측정 시각을 보여준다. 신선도·입력 신뢰도·계산 성격은 독립 축으로 상태·원인·마지막 정상 시각·영향·다음 행동을 표시한다.
- 계획 입력은 서버가 고정한 첫 정상 snapshot의 `P0`·`t0`를 읽기 전용으로 제시한다. 사용자가 보낸 사용자 ID·기준금액·시각은 권위로 취급하지 않는다. 입력 단위·KST 경계·오류를 영구 라벨과 함께 설명한다.
- 확인은 취소를 기본 포커스로 둔다. 입력 검증 후 조건·기준 snapshot·정책 버전을 요약하고, plan intent 저장 뒤에도 활성 계획·공식 지표가 아직 없음을 분명히 한다.
- 금융값은 브라우저 영속 저장소에 두지 않는다. 상태 변경은 키보드·스크린리더에 전달하고 320px 및 확대 환경을 지원한다.

## Cross-Story Dependencies

- 순차 핵심 사슬: 2.1 연결 → 2.2 첫 snapshot → 2.3 조건 검증·intent → 2.4 완전 수집 → 2.5 판정 → 2.6 환산 → 2.7 계산·활성 계획·공식 발행.
- 2.8은 발행 묶음을 표시한다. 2.9·2.10은 그 근거를 읽는다. 2.11·2.12는 증분·정밀 재검증을 추가하고, 2.13·2.14·2.15는 계획을 종료·완료·교체한다. 2.16은 연결을 해제하며 기존 계획 기록을 읽기 전용으로 보존한다.
- Epic 2는 durable 제한의 읽기 지점을 필요한 경계에서 구현하고 Epic 3은 제한을 설정·해제하는 운영자 흐름을 더한다. Epic 5는 계정 전체 삭제를 조정하며 모듈별 삭제 포트는 데이터 생성 스토리가 제공한다.
- 외부 Bybit 검증 계정은 자동 시험을 막지 않는다. 실제 연동 게이트는 별도로 승인한다.
