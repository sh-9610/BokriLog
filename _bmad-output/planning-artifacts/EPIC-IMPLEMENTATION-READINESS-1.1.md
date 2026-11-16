---
title: 'BokriLog Epic & Story Implementation Readiness Decisions'
status: final
version: '1.1'
created: '2026-09-02'
updated: '2026-11-16'
applies_to: '_bmad-output/planning-artifacts/epics.md'
# 파일 접미사 `-1.1`은 version '1.1'이고 결정 버전 이름은 ERD-1.1.0이다(1.0은 접미사 없는 EPIC-IMPLEMENTATION-READINESS.md = ERD-1.0.0).
decision_version: 'ERD-1.1.0'
supersedes: 'EPIC-IMPLEMENTATION-READINESS.md'
architecture: 'architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md'
---

# Epic·Story 구현 준비 결정 — ERD-1.1.0

> `ERD-1.1.0`은 Epic 1~5의 as-built를 반영한 새 버전이다. 절 번호와 정책 버전 이름(`CALC-1.0`·`SECURITY-ACTION-1.0`·`AD-GPT-1.0` 등)은 `ERD-1.0.0`과 같다. 바뀐 절은 §5·§8·§9이고 engagement 데이터 계약 §8.1을 더했다(제목에 `[1.1 개정]`·`[1.1 신설]`). 나머지 절은 1.0.0과 같다. 바뀐 곳마다 근거는 문서 끝 `## 1.0 → 1.1 변경 기록`에 있다. 아키텍처 불변식은 `architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md`(1.1)다.

## 1. 목적과 우선순위

이 문서는 PRD·Architecture·UX에서 이미 정한 제품 방향을 바꾸지 않고 `epics.md`의 구현 착수 게이트를 결정 가능한 수준으로 고정한다. 충돌 시 PRD의 제품 범위, Architecture의 불변식, 이 문서의 하위 구현 계약 순서로 적용한다. 정책 변경은 기존 결과를 덮어쓰지 않고 새 버전으로 발행한다.

## 2. G-1 — Bybit 수집·판정·환산 계약

### 2.1 연결 admission `BYBIT-CONNECTION-1.0`

- `GET /v5/user/query-api`: `readOnly=1`, `isMaster=true`, `parentUid="0"`, `ips`에 운영 regional reserved public IP가 정확히 포함되어야 한다. `userID`를 `provider=BYBIT`의 검증된 외부 계정 ID로 사용한다.
- 모든 쓰기 성격 permission 배열은 비어 있어야 한다. 허용 permission은 실제 호출에 필요한 읽기 접근뿐이며 모호한 값·새 필드는 fail-closed로 거부한다.
- `GET /v5/account/info`: `unifiedMarginStatus ∈ {5,6}`만 허용한다.
- `GET /v5/account/wallet-balance?accountType=UNIFIED`: 성공과 단일 `UNIFIED` 결과, 유효한 `totalEquity`를 확인한다.
- 세 검증과 운영 제한 재검사가 모두 성공한 뒤에만 credential·provider account·connection·portfolio membership을 저장한다. API spike fixture는 main/sub UID, 5/6, 알 수 없는 status, permission/IP 변화 응답을 포함한다.

### 2.2 동기화 기준시각·수집

- 작업 admission 직후 `GET /v5/market/time`으로 Bybit 기준 상한 `tc`를 한 번 고정한다. 응답 실패 시 외부 이력 작업을 시작하지 않는다.
- wallet `measured_at`은 wallet 응답 envelope의 Bybit `time`이며 로컬 수신·DB 저장시각과 분리한다.
- Transaction Log 요청은 항상 `accountType=UNIFIED`, `type=TRANSFER_IN`과 `type=TRANSFER_OUT`을 각각 조회하고 `startTime`, `endTime`, `limit=50`, 전 cursor를 명시한다.
- 한 구간은 닫힌 제품 범위 `[start,end]`로 취급하되 인접 구간 경계 중복을 허용하고 `source_id`로 제거한다. API의 `end-start <= 7일`을 지킨다.
- 일반 시작점은 `max(t0, cashflow_verified_through-24시간)`, 확장은 `max(t0, cashflow_verified_through-7일)`이다. 30일 이하 공백은 이 시작점부터 `tc`까지 전 구간을 연결하고, 30일 초과는 사용자 선택 전 호출하지 않는다.
- Transaction Log의 공식 조회 가능 범위는 현재 문서상 최대 2년이다. 필요한 구간이 그 범위를 벗어나거나 필수 페이지를 증명하지 못하면 기존 계획 catch-up은 `복구 불가`로 종료하고 마지막 정상 결과를 보존하며 새 계획 선택을 안내한다.

### 2.3 외부 자금흐름 판정 `BYBIT-CASHFLOW-1.0`

- 사건은 요청 문맥 `accountType=UNIFIED`, 비어 있지 않은 `id`, 유효한 UTC millisecond `transactionTime`, 대문자 `currency`, 유한한 비영(非0) `cashFlow`를 모두 요구한다.
- 정확한 `TRANSFER_IN`은 `cashFlow > 0`, 정확한 `TRANSFER_OUT`은 `cashFlow < 0`일 때만 외부 자금흐름이다. type과 부호가 모순되거나 0이면 `검토 필요`다.
- `qty`, `change`, `cashBalance`, 이름 일부는 방향·수량의 권위가 아니다. 외부 자금흐름 원수량은 `abs(cashFlow)`이고 방향으로 부호를 복원한다.
- 공식 문서에 새 enum이 추가돼도 allowlist는 자동 확대하지 않는다. 정확한 두 유형 외에는 제외 또는 미인식 상태이며, 미인식 유형은 정책 검토 전 해당 구간 발행을 보류한다.
- 고유키는 `(provider_account_id, source_id)`다. 같은 semantic hash는 no-op, 같은 ID의 핵심 필드 차이는 `PROVIDER_ID_CONFLICT`로 격리하고 자동 supersede·합산하지 않는다.

### 2.4 사건시점 USD 환산 `BYBIT-PRICE-1.0`

- `USD`와 `USDT`만 `1 unit = 1 USD` 제품 정규화로 처리한다. 이는 시장가격 주장이 아니라 BokriLog USD 계획 단위 정책이며 근거 유형을 `DIRECT_NORMALIZATION`으로 저장한다.
- 그 밖의 자산은 Bybit `spot` Instruments Info에서 사건시점에 출시되어 있고 `status=Trading`인 경로만 사용한다. 경로 우선순위는 `ASSETUSDT`, 다음 `ASSETUSDC × USDCUSDT`다. derivatives·inverse·제3자 가격·현재 ticker·수동 가격은 사용하지 않는다.
- 가격은 `GET /v5/market/kline`, `category=spot`, `interval=1`의 사건시각이 속한 **종료된 1분 candle close**다. 해당 candle이 없으면 사건시각 이전 가장 가까운 종료 candle을 최대 5분까지 허용한다. 이후 candle이나 5분 초과 값은 사용하지 않는다.
- `USDC`는 `USDCUSDT`의 같은 규칙을 사용하며 1 USD로 고정하지 않는다. 교차 경로의 두 candle은 각각 사건시각 기준 5분 이내여야 한다.
- kline close는 Bybit 공식 API에서 얻지만 정확한 사건 체결가격이 아니므로 `ESTIMATED`다. 이를 사용한 Plan·Difference·Target·RequiredRate·GoalProgress는 모두 `추정`, Bybit `totalEquity` 현재가치만 `공식`으로 유지한다.
- 증거에는 provider, category, symbol, interval, candle start/end, close, 관측시각, 경로, 정책 버전과 원문 hash를 저장한다. 동일 증거 key는 사용자 간 batch/cache할 수 있다. 같은 key의 값 변경은 새 price revision으로 보존하고 영향 계산을 새 버전으로 재평가한다.
- 검증 경로가 없거나 가격이 0 이하·비유한·허용시차 초과면 사건을 제외하지 않고 `환산 필요`로 두며 watermark와 공식 결과를 전진시키지 않는다.

### 2.5 호출 예산·재시도

- 프로세스 token bucket 기본값은 인증 Account API를 UID당 5 req/s, public Market API를 서비스 전체 10 req/s로 제한하고 동시 Bybit sync connection은 2개로 시작한다. 이는 현재 공식 25/s Transaction Log·50/s wallet/account와 IP 600회/5초보다 보수적인 운영 한도다.
- 응답의 `X-Bapi-Limit*` header를 관측하고 잔여량 20% 이하에서는 새 page lease를 늦춘다. `10006`은 reset timestamp 이후 jitter 재시도한다.
- timeout·429/10006·5xx는 동일 page에서 최대 3회, 2초·10초·30초 exponential backoff+jitter로 재시도한다. 인증·permission·schema·semantic 오류는 자동 재시도하지 않는다.
- IP 403 rate ban은 모든 Bybit HTTP 세션을 중단하고 최소 10분 회로를 연다. 부분 page는 발행하지 않고 checkpoint만 보존한다.

## 3. G-2 — 계산·시간·십진수 계약 `CALC-1.0`

### 3.1 입력·저장

- API의 금액·비율·가격·수량은 JSON number가 아니라 canonical decimal string이다. 지수 표기, NaN, Infinity, locale separator는 거부한다.
- PostgreSQL은 제약 없는 `numeric`과 원문 text를 함께 보존한다. 애플리케이션 입력 한도는 금액 precision 38/scale 18, 수량·가격 precision 50/scale 24, 목표율 precision 18/scale 12다.
- 목표율 화면 단위는 `%`, API·DB 단위는 비율이다. `10% ↔ "0.1"`. 제품 입력 범위는 `0 <= r <= 10`(주기당 최대 1000%)이다.
- 공식 계산은 Java `BigDecimal`, `MathContext(34, HALF_EVEN)`을 모든 산술·거듭제곱에 적용하고 `big-math`의 버전 고정 `pow`를 사용한다. 사건 순서는 `(effective_time, provider_account_id, source_id)`다. 중간 표시 반올림과 `double`/`float` 변환은 금지한다.
- 공식 값은 최대 절대값 `< 1E24`, canonical 결과 최대 34 유효숫자·scale 24다. preview 중 하나의 중간값이라도 범위를 벗어나면 계획을 만들지 않고 `CALCULATION_RANGE_EXCEEDED`를 반환한다. 0이 아닌 절대값 `<1E-24`는 0으로 만들지 않고 같은 오류로 거부한다.
- 표시만 HALF_UP 2자리다. `-0.00`은 `0.00`, 저장값은 변경하지 않는다.

### 3.2 시간과 `periods`

- 원본·API·DB 시각은 UTC millisecond instant, 사용자 입력·표시·계획 경계 zone은 고정 `Asia/Seoul`이다.
- `t0`부터 다음 같은 현지시각의 일/7일/월 경계까지를 첫 완전 주기로 한다. 월에 원래 일이 없으면 월말로 이동하고 다음 달에는 원래 일로 복귀한다.
- `T`는 첫 일 경계 이상, `t0`의 1년 뒤 월·일·시각 경계 이하이며 최종 확인 시 `serverNow < T`여야 한다.
- `periods(a,b)`는 `[a,b]`가 걸치는 각 고정 경계 구간에서 `실제 UTC 경과 ms / 해당 구간 실제 UTC 길이 ms`를 DECIMAL128로 합산한다. 따라서 가법성을 만족한다.
- 같은 millisecond 사건은 모두 독립 보존한 뒤 위 안정 순서로 더한다. `ti=t0`는 P0에 이미 포함된 것으로 보고 Fi에서 제외하고 `t0<ti<=min(tc,T)`만 포함한다.

### 3.3 계산 불가·정책 버전

- PRD §4의 Plan, Target, Difference, AdjustedPrincipal, RequiredRate, GoalProgress 공식을 그대로 사용한다.
- `E<=0`, `Target<=0`, `periods(tc,T)<=0`, `GoalProfit<=0`, 실수 범위 밖 pow, 범위 초과는 해당 지표의 명시적 reason code로 반환하며 0으로 대체하지 않는다.
- 계산 정책 ID는 `CALC-1.0`, 달력 정책 `CALENDAR-SEOUL-1.0`, 표시 정책 `DISPLAY-1.0`을 저장한다. 구현 변경 없이 상수·경계가 바뀌어도 정책 버전을 올리고 기존 결과를 보존한다.

### 3.4 승인 고정 예제

모든 시각은 주기 경계에 정확히 맞는 예제이며 내부값 비교는 CALC-1.0의 canonical decimal 결과를 사용한다.

| ID | 입력 요약 | 필수 기대 결과 |
|---|---|---|
| CV-01 | P0=1000, r=0.1/day, tc=2 periods, T=3, E=1250, Fi 없음 | Plan=1210, Target=1331, Difference=40, RequiredRate=0.0648, GoalProgress=0.7552870090634441087613293051359517 |
| CV-02 | CV-01 + t0+1 period 입금 100, E=1340 | Plan=1320, Target=1452, Difference=20, RequiredRate=0.083582089552238805970149253731343, GoalProgress=0.6818181818181818181818181818181818 |
| CV-03 | CV-01 + t0+1 period 출금 -200, E=950 | Plan=990, Target=1089, Difference=-40, RequiredRate=0.146315789473684210526315789473684, GoalProgress=0.5190311418685121107266435986159170 |
| CV-04 | 같은 시각 +100,-30,-70 | 세 사건 보존, 대수합 0, source ID 순서와 무관하게 CV-01 결과 |
| CV-05 | ti=t0 입금 100 | Fi 제외, P0 중복 조정 금지 |
| CV-06 | E=0 또는 음수 | 금액 보존, RequiredRate 계산 불가; 0% 대체 금지 |
| CV-07 | 출금으로 Target 또는 GoalProfit<=0 | 계획 `검토 필요`, 목표 기반 비율 계산 불가 |
| CV-08 | 비-USDT 사건 가격 결측 | 새 공식 결과·watermark 미발행, 마지막 정상 결과 유지 |
| CV-09 | 2028-01-31 10:00 KST 월 시작 | 경계 02-29 10:00, 03-31 10:00로 복귀 |
| CV-10 | 한 경계 구간을 임의 b에서 분할 | periods(a,c)=periods(a,b)+periods(b,c), canonical 결과 동일 |
| CV-11 | 첫 종료 후 관측이 T+24h 이하/초과 | 이하만 참고 비교; 초과는 관측값 보존·완료 비교 계산 불가 |
| CV-12 | 결과 절대값>=1E24 또는 r>10 | preview 거절, 활성 plan·공식 pointer 미생성 |

## 4. 동기화 admission·신선도 `SYNC-UX-1.0`

- `동기화 중`: 해당 portfolio/source에 `QUEUED|RUNNING|RETRYABLE` 작업이 존재한다. 기존 공식 값은 그대로 둔다.
- `최신`: 마지막 공식 결과가 성공이고 `serverNow-measured_at <=15분` 및 `serverNow-cashflow_verified_through <=15분`이다.
- `최신 아님`: 위 조건 밖, 마지막 시도 실패·검토 필요·연결 해제 또는 정책 제한 상태다. 항상 마지막 정상시각과 원인을 같이 표시한다.
- 대시보드 진입은 공식 결과가 없거나 마지막 성공 admission 후 5분이 지났을 때만 자동 요청한다. 탭 재활성화는 5분 이상 hidden이었고 같은 5분 조건을 만족할 때만 요청한다.
- 수동 동기화 cooldown은 성공·실패와 무관하게 admission 후 30초다. 진행 작업이나 cooldown 중 요청은 외부 호출 없이 기존 job/result reference를 반환한다.
- 사용자 행동과 무관한 polling·전 사용자 배치는 없다. 30일 초과는 선택 화면, 제한·해제 상태는 외부 호출 없이 설명 화면으로 간다.

## 5. 민감 command·재연결 `SECURITY-ACTION-1.0` [1.1 개정]

- Bybit 연결 생성·재연결·해제, 계획 중도 종료, 로그인 수단 연결·해제, 계정 삭제와 운영 command는 성공한 재인증 후 5분 이내 발급된 일회성 action grant를 요구한다.
- grant는 user, session, command, target, canonical confirmation hash에 귀속하고 한 번 소비한다. 성공 시 session ID를 다시 회전한다. 회전과 함께 재인증 시각을 지우므로 본인 확인 1회는 command 1건이다(거부·재전송·검증 실패는 지우지 않는다 — `SessionRotation`).
- **1.1 — 계정 삭제의 비회전 예외.** `delete-account`의 확정 성공은 세션 ID를 **회전하지 않는다**(`SessionRotation.completeSensitiveCommandWithoutRotation`). 커밋 뒤 요청 세션 밖의 그 사용자 세션 폐기 → 무장 폐기 → 재인증 시각 제거 순서이고, 남은 요청 세션은 상태 조회·같은 요청 재전송·로그아웃만 쓴다. 응답이 유실돼도 같은 쿠키·같은 멱등 키의 재전송이 같은 200을 받게 하려는 것이다. 재전송 판정은 허가 경로보다 앞에서 요청 행의 멱등 키 digest로 한다. 이 예외는 이 command 하나뿐이다.
- **1.1 — 계정 삭제의 확인 문구.** `delete-account`의 확인은 계정 표시명 재입력이 아니라 정책이 고정한 문구 `계정을 삭제합니다`(`AccountDeletionPolicy.CONFIRMATION`)의 재입력이다. target은 `<userId>|<정책 버전>`이고 확인 hash에는 사용자가 **입력한** 문구가 들어간다 — 고정 문구와 등가가 아니면 `CONFIRMATION_MISMATCH`로 허가를 소비하지 않고 무장을 남긴다. 운영 command도 확인 문구 재입력을 hash에 넣는다(3.1). Epic 1·2 command의 확인 hash 판별력 정정은 미결정 — 주인 Epic 1 회고 item-7.
- 계획 중도 종료와 연결 해제는 모두 민감 command다. 연결 해제 확인의 기본 포커스는 취소다.
- 해제 뒤 같은 Bybit main UID로 재연결하면 기존 provider account를 재사용하고 활성 계획은 기존 watermark부터 다시 동기화한다. 공백이 30일 초과면 기존 장기 미동기화 선택 정책을 적용한다.
- 다른 main UID는 기존 활성 계획에 연결하지 않는다. 기존 계획을 먼저 종료하고 새 provider account의 새 portfolio baseline으로 후속 계획을 만들어야 한다.

## 6. G-3 — 강한 신호·운영 대응 `OPS-SIGNAL-1.0`

### 6.1 자동정지 판정표

| 신호 | 권위 증거 | 자동 제한 범위 |
|---|---|---|
| 쓰기 가능 Bybit key가 저장 단계에 도달 | admission invariant event 1건 | 해당 connection 생성 차단; 이미 저장됐으면 신규 연결+sync 전역 정지 |
| 교차 사용자 데이터 접근 성공 또는 공식 pointer 혼합 | authorization/integrity assertion 1건 | 사용자 금융 read/write·신규 연결·sync 전역 정지 |
| secret/token이 redaction canary 또는 외부 telemetry에서 검출 | DLP rule 1건 | 신규 연결·sync·민감 화면 전역 정지 |
| 같은 canonical input/policy에서 다른 result hash | deterministic replay 1건 | 해당 calculation policy 표시·publication 정지; 범위 불명 시 sync+계산 전역 정지 |
| portfolio component 합계·membership·pointer CAS invariant 위반 | DB/application invariant 1건 | 영향 portfolio/plan; 둘 이상 또는 범위 불명 시 sync+계산 전역 정지 |
| 같은 source ID의 semantic 충돌 | provider account 사건 1건 | 영향 plan publication 정지, 전역 정지 아님 |
| 미인식 Transaction Log enum | provider schema observation 1건 | 영향 provider account publication 정지, 전역 정지 아님 |
| schema 검증 실패가 5분 내 서로 다른 provider account 2개 이상에서 각 3회 | versioned detector | 신규 연결+sync 전역 정지 |
| credential 복호화 실패가 5분 내 서로 다른 사용자 2명 이상 | key-version detector | 신규 연결+sync 전역 정지 |

단일 사용자의 인증 만료, 일반 timeout·rate limit·5xx, 단일 가격 결측, 광고 오류는 강한 신호가 아니며 마지막 정상값·일반 경보·재시도 정책으로 처리한다.

### 6.2 복구와 대응시간

- restriction은 감지 후 60초 안에 durable하게 설정하고 자동 해제하지 않는다.
- 운영 대응시간은 대한민국 영업일 09:00~18:00 KST다. 24시간 수동 대응을 약속하지 않으며 시간 밖에는 자동정지가 일차 통제다.
- 운영자 역할은 배포 환경의 `(issuer,subject)` allowlist로만 부여·회수하고 사용자 UI에서 승격할 수 없다. 단일 운영자 MVP이므로 이중승인을 거짓으로 요구하지 않되 모든 command에 5분 재인증·일회 grant·사유·증거·감사를 요구한다.
- 복구는 원인 제거, 영향 범위 재현 시험, 고정 회귀·소유권·pointer 검사 통과와 새 배포/version 증거가 있을 때만 수동 수행한다.

## 7. 지원 정책 `SUPPORT-1.0`

- 인증 사용자 웹 문의 폼을 기본 채널로 구현하고 공개 지원 이메일을 fallback으로 표시한다. 실제 주소는 배포 필수 설정이며 누락 시 문의 Story production readiness가 실패한다.
- 영업일은 `Asia/Seoul` 월~금에서 대한민국 법정 공휴일을 제외한다. 연도별 공식 공휴일 자료를 versioned seed로 저장하고 다음 연도 자료가 없으면 그 연도의 자동 deadline 확정을 차단한다.
- 목표는 접수 다음 영업일부터 세 번째 영업일 18:00 KST이며 SLA로 표현하지 않는다.
- 일반 문의 운영 보존은 해결 후 90일 뒤 삭제로 정한다. 법정 분리 보존이 승인된 경우에만 별도 저장소로 이동하며 금융 상세·credential은 문의 입력과 로그에서 금지한다.

## 8. G-4 — 광고 구현 계약 `AD-GPT-1.0` [1.1 개정]

- provider 후보는 Google Ad Manager의 Google Publisher Tag(GPT) web interstitial로 고정한다. 실제 network/ad-unit 승인과 계약이 없으면 adapter는 `DISABLED`로 동작하고 결과를 즉시 표시한다.
  - 1.1: adapter는 브라우저의 `frontend/src/ads/gptAdapter.ts` 하나다. 서버(`engagement`)는 공급자 **활성 판정**만 한다 — `BOKRILOG_AD_PROVIDER_ENABLED`와 형식에 맞는 `BOKRILOG_AD_UNIT_PATH`가 함께일 때만 활성이고 기본은 꺼짐이며, 꺼짐은 판정의 `PROVIDER_DISABLED` 건너뜀이다.
- 공식 trigger는 **수동 동기화 성공 후 중립 전환 화면의 `결과 보기` anchor를 사용자가 클릭한 순간**이다. 동기화 완료 자체, 대시보드 진입, tab unhide, inactivity, back navigation은 trigger로 사용하지 않는다.
  - 1.1 — **reservation 시점.** 광고를 띄우는 GPT trigger는 여전히 `결과 보기` anchor 클릭이지만, 서버 cap의 reservation(노출 시도 집계)은 그보다 앞서 일어난다. 대시보드가 자기 화면에서 `ACCEPTED`로 접수한 MANUAL 동기화의 성공을 본 직후 jobId당 한 번 `POST /api/engagement/ad-eligibility`를 부르고, ELIGIBLE 행이 곧 reservation·노출 시도다. 그래서 대화상자·새 접수 때문에 이동하지 않거나 SHOW가 아닌 결말에서도 cap은 소비된다. 기존 작업에 합류한(`JOINED`) 요청·자동·탭 재활성화 동기화와 정밀 검증은 판정을 부르지 않는다.
- GPT는 중립 전환 route에서만 load하며 금융값·계획값·신뢰상태·user ID를 DOM, URL, title, slot, targeting에 넣지 않는다. 다른 모든 링크에는 interstitial 방지 속성을 적용한다.
  - 1.1 — **방지 속성 범위.** GPT는 `/ad-transition` 문서에서만 load되므로 다른 화면의 링크는 interstitial trigger가 될 수 없다. 방지 속성(`data-google-interstitial="false"`)이 필요한 곳은 중립 화면의 `결과 보기` 밖 anchor뿐이고, 지금 그런 anchor는 없다(accepted deviation — 4.3).
- 명시적 광고 저장소 동의가 없으면 GPT를 load/request하지 않는다. 동의 시에도 서버의 30분·KST 일 3회 cap을 먼저 원자 소비하고 provider 자체 cap을 추가 적용한다.
  - 1.1 — **관문 시점.** 관문(동의 → 전역 `ADVERTISING` 제한 → 공급자 활성)은 두 번 본다 — 자격 판정 때와 중립 화면이 불투명 `transition`을 소비할 때(`POST /api/engagement/ad-transition`, 결정 뒤 120초 안, DB 시각). 소비 결과가 `SHOW`인 뒤에는 서버가 관문을 다시 보지 않는다 — 그 뒤의 철회·제한은 다음 판정부터 적용된다.
- `defineOutOfPageSlot=null`, no-fill, 차단, timeout 3초, render/accessibility 실패, 이탈·재진입은 모두 동일 저장 결과로 fail-open한다.
  - 1.1 — **이동 대상.** "동일 저장 결과"는 binding된 결과 버전이 아니라 `/dashboard`다 — `광고 닫기`와 `SHOW`가 아닌 모든 결말은 광고 없이 `/dashboard`로 replace하고, 화면은 그 시점의 현재 포인터(저장된 최신 결과)를 읽는다. 중립 화면에는 문서 시작 뒤 소비 창 120초(`AD_CLAIM_WINDOW_SECONDS`)까지만 머문다. 닫은 뒤 대시보드 제목으로 포커스를 옮기는 UX 요구(EXPERIENCE)는 미구현이다(deferred 186).
- production enable은 실제 request field 검사, 개인정보 검토, keyboard/screen-reader/close 검증과 Google 계정 승인을 요구한다. 이 외부 승인은 코드·Story 자동시험으로 대체하지 않는다.
  - 1.1: 이 외부 승인 묶음이 출시 게이트 G-4이고 미검증이다. 성능 kill switch(`AD-PERF-1.0`, Story 4.5)의 임계 실측 보정도 G-4에 든다.

### 8.1 engagement 데이터 계약 [1.1 신설]

| 데이터 | 자리 | 분류 | 삭제·수명 |
|---|---|---|---|
| 광고 동의 결정 | `engagement_ad_consent_decision`(V26) — 추가 전용, 현재 상태는 사용자 advisory 잠금 아래 순번이 가장 큰 행, 정책 버전 `AD-CONSENT-1.0`, 멱등 키·요청 digest | 사용자 귀속 | 계정 삭제 `EngagementDeletionApi.eraseConsents`(행 삭제). 보존 기한은 미결정 — 주인 G-4(deferred 148) |
| 광고 자격·건너뜀 결정 | `engagement_ad_attempt`(V27) — `(user_id, sync_job_id)` 한 행, `ELIGIBLE`(불투명 `transition_ref`)·`SKIPPED`(`CONSENT_REQUIRED`·`RESTRICTED`·`PROVIDER_DISABLED`·`INTERVAL`·`DAILY_CAP`). ELIGIBLE 행이 곧 reservation이고 30분·KST 일 3회 cap은 이 행들에서 계산한다(별도 `frequency_cap` 표 없음) | 사용자 귀속 | `eraseAdAttempts`(행 삭제). 보존 기한은 미결정 — 주인 G-4(deferred 152) |
| transition 소비·SHOW·Vitals 보고 표지 | 같은 행의 열 — `transition_consumed_at`(V28), `transition_shown`·`vitals_reported_metrics`(V29) | 사용자 귀속(행의 일부) | 행과 함께 삭제 |
| Web Vitals 표본 | `engagement_ad_vital_sample`(V29) — metric·값·기록 시각뿐, 사용자·attempt·transition 열 없음. SHOW 소비 10분 안 문서의 metric마다 하나 | 식별 없는 집계 | 계정 삭제 대상 아님(잔여 행 검사에도 잡히지 않는다). 수명은 판정 창 24시간 — `AdPerformanceJudge`가 창 밖·발동 시각까지의 표본을 지운다 |
| 계측 카운터 | `bokrilog_web_vitals_total`·`bokrilog_ad_client_event_total`·`bokrilog_ad_transition_total`(닫힌 label, 사용자·결과·금융값 없음) | 식별 없는 집계 | 삭제 대상 아님 |

- 경계: 사용자 귀속 행의 답은 비식별이 아니라 행 삭제이고, 식별 없는 집계는 사용자로 되돌아갈 열을 갖지 않는다. 잔여 검사 `remainingRowsOf`는 `engagement_*` 표의 `user_id` 열을 카탈로그로 센다.
- 동의 판독(`AdConsentApi`)은 engagement와 그 컨트롤러만 의존하고, sync·plan·dashboard는 engagement에 의존하지 않는다 — 광고 판정은 결과 접근을 막지 않는다(`ModuleBoundaryTest`).
- 브라우저 계측은 동일 출처 beacon `POST /api/engagement/telemetry` 하나이고 닫힌 어휘 밖이 하나라도 있으면 400·기록 없음이다. 법정 분리 보존 후보 필드는 없다 — 미결정, 주인 G-5.

## 9. G-5 — 삭제·법률 게이트의 엔지니어링 기본값 [1.1 개정]

- 법정 분리 보존의 정확한 필드·법적 근거·목적·기간은 법률·개인정보 검토 없이는 확정하지 않는다. production 계정 삭제 실행과 공개 베타는 승인 전 차단한다.
  - 1.1: `BOKRILOG_ENV=production`에서 삭제 준비·확정·재시도는 403 `ACCOUNT_DELETION_UNAVAILABLE`, 조회는 `available:false`이고 Worker는 삭제 실행을 집지 않는다. 운영 재대기열 `requeue-deletion`도 production에서 403 `DELETION_REQUEUE_UNAVAILABLE`이다.
- 개발·CI 기본값은 법정 분리 보존 `없음`이며 모든 사용자 데이터를 삭제한다. 이 기본값을 production 법률 판단으로 간주하지 않는다.
  - 1.1: 정책 상수 `AccountDeletionPolicy`(`ACCOUNT-DELETION-1.0`)의 법정 분리 보존 목록은 빈 목록이다.
- restore 방지 tombstone은 사용자 ID의 복구 불가능한 keyed digest, deletion request ID·시각, 영향 backup generation, 파기 deadline·상태만 포함하고 금융·credential·소셜 원 식별자를 금지한다. 백업 최대 수명 30일보다 긴 35일 보존 후 자동 파기한다.
  - 1.1 — **필드.** tombstone(`DELETION-TOMBSTONE-1.0`)은 operations 소유 outbox `operations_deletion_tombstone`(V25, V32 열)이다 — `request_id`, `user_digest`(전용 키 `BOKRILOG_TOMBSTONE_DIGEST_KEY`의 `tombstone:` HMAC — 감사 digest 키와 바이트가 같으면 앱·gate가 거부한다), `requested_at`(V30 요청 시각 — 예외: V32 이전에 5.2 단계 모드가 쓴 옛 행은 그 실행의 운영 완료 시각을 담아, 그 행의 백업 파기 기한·보존 기한은 정책 기준보다 늦을 수 있다. production 삭제가 열리기 전이라 개발·CI DB에만 있다 — V32 주석), `destroy_after`(보존 기한), `origin` `DELETION|RESTORE`, `policy_version`, `status` `PENDING|OVERDUE|DESTROYED`, `closed_at`(영향 generation 집합이 닫힌 시각 = 운영 데이터 삭제 완료), `overdue_at`, `destroyed_at`, `destroyed_export`. 영향 generation은 행이 아니라 별도 표 `operations_deletion_backup_generation`(V32 — `request_id`·`generation_id`·`oci_purged_at`·`offsite_purged_at`)이고, 백업 파기 기한은 열이 아니라 식 `requested_at + 720시간`(요청 + 30일)이다.
  - 1.1 — **기록 시점.** Worker가 삭제 실행을 처음 집을 때 `CREDENTIALS` 앞 자기 트랜잭션에서 요청 시각으로 멱등 기록한다(실패는 첫 미완료 단계의 `TOMBSTONE_FAILED`). 마지막 단계 `IDENTITY`가 영향 generation 집합을 닫는다.
  - 1.1 — **영향 generation 집합.** 운영 완료 + `CLOSURE_SLACK`(1시간, `BackupDestructionApi` — 운영 완료 문장과 그 커밋 사이에 시작한 dump가 삭제 전 snapshot을 담을 수 있어서다) 전에 `STARTED`한 저장된(`SUCCEEDED`) generation 전부다. 요청 전에 두 저장소에서 이미 파기된 generation은 넣지 않고 하한은 없다. 집합은 닫히기 전에는 판정마다 늘 수 있고 행을 빼지 않는다. 1시간 여유는 5.3 SPEC의 동결 Always 문장보다 넓은 accepted deviation이다.
  - 1.1 — **상태 판정.** `DESTROYED`는 집합이 닫혔고, 운영 완료 + `CLOSURE_SLACK`이 지났고, 모든 영향 generation에 두 저장소의 `AGE|COUNT|BYTES` `PURGED` 기록(성공 기록이 있으면 순서 무관)이 있고, 마지막 결과 없는 `STARTED`가 없을 때만이다. 판정은 `operations_backup_event`(V24) 기록만 보고 저장소를 조회하지 않는다(범위 밖 사례는 deferred 174~176). 기한(요청 + 30일)이 지났는데 미완료면 `OVERDUE`로 바꾸고 감사 SYSTEM 행 한 번·경보(`DeletionBackupDestructionOverdue`)만 낸다 — 백업을 앞당겨 지우지 않는다. 판정자는 루트 `BackupDestructionJudge`(Worker·`all`, 기본 5분, production에서도 돈다)이고 tombstone마다 짧은 트랜잭션이다.
  - 1.1 — **보존식.** "35일 보존 후 자동 파기"는 다음으로 구체화됐다. `destroy_after`는 처음 요청 + 35일(840시간)이고 집합이 닫히면 `max(요청, 운영 완료) + 35일`로 늘린다 — 줄이지 않는다. 보존 기한으로 지우는 것은 `DESTROYED`이고 그 상태로 성공한 내보내기(`destroyed_export`)에 실린 행뿐이며, 미완료 행은 기한이 지나도 남고 외부 사본에 계속 실린다.
  - 1.1 — **외부 사본.** 15분 timer의 `deploy/production/tombstone-export.sh`가 내보낼 행 전체를 머리(digest 키 ID·시각)와 함께 tombstone 전용 age key(backup key와 다르다)로 암호화해 OCI 밖 `tombstones/`에 두고 `latest`를 갱신한다. 사본 object는 24시간만 남기고(35일 지난 object가 아니다 — 보존은 위 행 필터가 정한다), 앞선 사본의 살아 있는 digest가 빠지면 `TOMBSTONE_SHRANK`로 `latest`를 지킨다. 줄은 여덟 칸(`digest request_id requested_at destroy_after backup_deadline status OPEN|CLOSED gen_YYYYMMDD:<oci><offsite>,…`)이다. 내보내기 성공은 업로드·대조·`latest` 갱신 뒤 기록 모드가 `operations_tombstone_export`(V32)에 쓰고 경보 `TombstoneExportStale`(1시간)이 정지를 알린다. 복원은 `latest` 하나만 읽고 장애 시각보다 1시간 넘게 앞서면 거부한다. 키 회전은 아직 없다(deferred 139).
- 계정 삭제가 시작되면 session·credential·일반 쓰기를 즉시 차단하고 module deletion은 멱등 재시도한다. 법정 정책 version과 사용자 고지 hash를 삭제 요청에 고정한다.
  - 1.1 — **차단과 재시도.** 요청 확정이 `ACTIVE → DELETION_IN_PROGRESS`와 요청 행(V30)을 허가 소비와 한 트랜잭션에 쓰고, 커밋 뒤 요청 세션 밖의 세션을 폐기한다(§5 비회전 예외). 자격정보 행은 실행 첫 단계 `CREDENTIALS`가 지운다. 모듈 삭제는 멱등이지만 자동 재시도는 없다 — 실패 단계는 `RETRY_NEEDED`로 멈추고 요청 세션의 `POST /api/account/deletion/retry` 또는 운영 command `requeue-deletion`이 다시 대기열에 넣는다.
  - 1.1 — **고지 hash.** 요청 행에 정책 버전과 고지 hash를 고정한다. hash는 정책 상수의 정규 직렬화(정책 버전·대상 code·파기 30일·법정 분리 보존 목록·고정 문구, `AccountDeletionPolicy.noticeHash`)의 SHA-256이고 화면이 보여 준 문장 전체를 덮지 않는다. "남기는 것" 카피를 hash에 넣을지는 미결정 — 주인 G-5(Epic 5 회고 item-53).
- **1.1 — 재가입 차단(새 보존 범주).** 삭제한 소셜 identity는 identity 소유 `identity_deleted_identity`(V33 — 사용자 열 없음, 단계 모드는 `request_id`)의 keyed digest로 남는다. 입력은 `deleted-identity:<issuer 길이>:<issuer>:<subject>`이고 키는 감사 digest 키다 — 루트 port(`DeletedIdentityDigestPort` → operations `DeletedIdentityDigestApi`)가 계산하고 identity는 키를 갖지 않는다. 매핑을 지우는 트랜잭션(`CREDENTIALS` — 단계·단일·복원 재삭제)이 지우기 전에 기록하고 digest를 만들 수 없으면 지우지 않는다(fail-closed). 기록이 있으면 로그인은 계정을 만들지 않는다. 로그인 실패 표지 `deleted`는 그 실행이 끝났거나 실행 행이 없을 때만 내는 본인 한정 예외다(미완료·판정 실패는 `failed`). 서비스 DB에만 있고 만료·해제 경로가 없다. 보존 기한·해제 경로·복원 내구성은 미결정 — 주인 G-5(Epic 5 회고 item-53). 감사 digest 키를 회전하면 기존 차단이 대조되지 않는다(deferred 139와 같은 주인).
- **1.1 — 완료 확인 세션.** 요청 행은 요청 세션의 `spring_session.primary_id`를 남기고, `IDENTITY` 커밋은 그 세션을 지우지 않고 주체·CSRF/OAuth2 인가 요청 밖의 속성을 걷어 `identity_deletion_completion_session`(V33, `spring_session` FK cascade)으로 요청 ID에 잇는다. 이 세션은 미인증이고 닿는 경로는 완료 조회와 확정 재전송 거부(409 `ACCOUNT_DELETION_COMPLETED`) 둘뿐이며, 세션 응답은 이 세션에만 `deletionCompleted: true`를 싣는다. 수명은 기존 유휴 30분·절대 12시간 그대로라 백업 파기(최대 30일)를 화면에서 다 보지 못할 수 있다.
- **1.1 — 전체 삭제 완료.** 실행 상태 `DELETION_COMPLETED`·`deletion_completed_at`(V33)은 운영 데이터 삭제 완료(`OPERATIONAL_DATA_ERASED`) + tombstone `DESTROYED` + 법정 보존 처리(빈 목록이면 충족)일 때만 `BackupDestructionJudge`가 주기마다 단조·멱등으로 기록한다. 운영 데이터 삭제 완료는 백업 파기 완료가 아니다.
- **1.1 — 검증 상태.** 실저장소 파기와 실환경 격리 삭제·복원 통합시험(5.4 AC-5)은 미수행이다. 사용자 카피의 약속(30일 파기·복원해도 되살리지 않음)과 구현의 차이, 요청 확정 뒤 첫 claim 전·첫 내보내기 전 창(deferred 171)은 미결정 — 주인 G-5(Epic 5 회고 item-53).

## 10. G-6 — Day 90 IP 정책 `BYBIT-IP-1.0`

- 신규 연결은 처음부터 regional reserved public IP binding을 요구한다.
- 기존 IP 미제한 연결은 Day 60에 30일 사전 공지하고 Day 90부터 새 sync와 재연결을 정지한다. 마지막 정상 결과는 읽기 전용으로 유지한다.
- 사용자가 IP-bound read-only key로 교체하면 같은 provider account를 재사용하고 30일 경계에 따라 정상/장기 catch-up을 수행한다. 기존 credential은 새 연결 검증 성공과 원자 교체 뒤 즉시 삭제한다.

## 11. 스프린트 계획을 막지 않는 외부 입력

다음은 Story의 구현·자동시험을 계획할 수 있으나 production 완료와 공개 베타를 선언하려면 외부 입력이 필요하다.

1. Google Ad Manager network/ad-unit ID, 계정 승인과 실제 소재 접근성·데이터 흐름 승인.
2. 공개 지원 이메일 주소.
3. 법률·개인정보 전문가가 승인한 법정 분리 보존 정책과 개인정보 처리방침.
4. production OAuth client ID/secret·redirect URI, Bybit 검증 계정과 regional reserved IP, 운영자 `(issuer,subject)` allowlist.

이 네 항목은 현재 사용자에게 제품 공식을 다시 선택하게 할 사안이 아니라 외부 계정·법률·배포 비밀의 공급/승인 작업이다. 값이 준비되지 않아도 mock/stub과 fail-closed 계약으로 개발 스프린트는 계획할 수 있다.

## 12. 최종 구현 준비 검증

| 검증 항목 | 결과 | 근거 |
|---|---|---|
| 요구사항 추적 | PASS | FR 20개, NFR 21개, AR 22개, UX-DR 23개 전부 Epic/Story에 연결됨 |
| Story 완결성 | PASS | 45개 Story 모두 사용자 가치, 연결 요구사항, AC와 테스트 기준을 포함함 |
| 의존성 | PASS | Story 선행 참조에 자기·미래 Story가 없고 Epic 2~5는 완료된 이전 Epic 출력만 사용함 |
| 아키텍처 착수 | PASS | 특정 starter template 요구가 없으며 스키마·큐·UI 기반은 최초 사용 Story에서 필요한 만큼만 생성함 |
| 계산 결정성 | PASS | `CALC-1.0`의 단위·시간·정밀도·실패 코드와 CV-01~12를 고정함 |
| 외부 연동 fail-closed | PASS | Bybit schema·가격·페이지 결측은 마지막 정상값을 유지하고, 광고 미승인은 `DISABLED`로 fail-open함 |
| 운영·삭제 경계 | PASS WITH EXTERNAL LAUNCH GATES | 개발 계약은 확정됐고 실제 광고 승인·법률 보존 승인·production 비밀만 공개 베타 전에 필요함 |

결론은 `ready-for-sprint-planning`이다. 이 판정은 구현 완료나 production 출시 승인이 아니라, 각 Story를 추가 제품 의사결정 없이 추정·배치·개발할 수 있다는 뜻이다.

## 13. 공식 근거

- [Bybit Get Transaction Log](https://bybit-exchange.github.io/docs/v5/account/transaction-log): 7일 요청 창, cursor, 최대 2년, `id`와 필드 계약
- [Bybit API Key Information](https://bybit-exchange.github.io/docs/v5/user/apikey-info): `readOnly`, permission, IP, master/sub UID 필드
- [Bybit Account Modes](https://bybit-exchange.github.io/docs/v5/acct-mode): `unifiedMarginStatus=5/6`
- [Bybit Wallet Balance](https://bybit-exchange.github.io/docs/v5/account/wallet-balance): UTA `totalEquity`
- [Bybit Server Time](https://bybit-exchange.github.io/docs/v5/market/time): 동기화 상한 시각
- [Bybit Kline](https://bybit-exchange.github.io/docs/v5/market/kline): spot 1분 candle과 close
- [Bybit Instruments Info](https://bybit-exchange.github.io/docs/v5/market/instrument): base/quote/status/launch 정보
- [Bybit Rate Limits](https://bybit-exchange.github.io/docs/v5/rate-limit): endpoint·UID·IP 제한과 reset header
- [Bybit Transaction Log enums](https://bybit-exchange.github.io/docs/v5/enum): 정확한 `TRANSFER_IN/OUT` 및 유사 별도 유형
- [Google GPT web interstitial](https://developers.google.com/publisher-tag/samples/display-web-interstitial-ad): 허용 trigger·cap·top-window·storage 조건
- [Google Ad Manager web interstitial](https://support.google.com/admanager/answer/9840201): 실제 trigger와 fail/no-show 특성

## 1.0 → 1.1 변경 기록

근거는 바뀐 문장을 확인한 파일이다. 대괄호는 그 차이를 남긴 회고 항목이다(E<n>은 Epic n 회고). 바뀌지 않은 절(§1~§4·§6·§7·§10~§13)은 `ERD-1.0.0`과 같다.

- **§5** — 계정 삭제의 세션 ID 비회전 예외, 고정 문구 `계정을 삭제합니다`와 입력 문구 hash, 성공 뒤 재인증 시각 제거를 적고 Epic 1·2 hash 정정이 미결정임을 표기했다. 근거: `SessionRotation.java`(`completeSensitiveCommandWithoutRotation`), `AccountDeletionPolicy.java`(`CONFIRMATION`), `AccountDeletionRequestService.java`(`CONFIRMATION_MISMATCH`), `spec-5-1-account-deletion-request.md`, `spec-sensitive-command-commons.md`. [E5 R-2]
- **§8** — 서버의 공급자 활성 판정과 브라우저 adapter, reservation 시점(자격 판정), 방지 속성 범위, 관문 두 시점과 SHOW 뒤 무검사, 이동 대상 `/dashboard`, 닫기 뒤 포커스 미구현(deferred 186), G-4 미검증을 적었다. 근거: `frontend/src/ads/gptAdapter.ts`, `frontend/src/screens/AdTransitionScreen.tsx`, `frontend/src/session/useDashboardSync.ts`, `engagement/internal/AdProviderState.java`·`AdGate.java`, `V28__engagement_ad_transition.sql`, `spec-4-2-ad-eligibility.md`·`spec-4-3-neutral-ad-transition.md`·`spec-4-4-ad-fail-open.md`. [E4 R-1, E4 R-3]
- **§8.1(신설)** — engagement 데이터 계약(사용자 귀속 행과 식별 없는 집계의 경계, 표 구성, 삭제·수명)을 적었다. `frequency_cap` 표가 없고 transition이 같은 표의 열임을 적었다. 근거: `V26`~`V29` migration, `engagement/EngagementDeletionApi.java`, `spec-4-1-ad-consent.md`·`spec-4-5-ad-telemetry-web-vitals.md`. [E4 R-3]
- **§9** — production 차단 방식, tombstone 필드(V25·V32 — V32 이전 5.2 단계 모드 행의 `requested_at`이 운영 완료 시각인 예외 포함)·기록 시점·영향 generation 집합과 `CLOSURE_SLACK`·V24 기록만 보는 상태 판정·`OVERDUE`·보존식 `max(요청, 운영 완료) + 35일`과 행 필터·외부 사본 24시간과 여덟 칸 줄, 차단·재시도, 고지 hash의 범위, 재가입 차단(V33), 완료 확인 세션, 전체 삭제 완료를 적었다. 보존 기한·해제 경로·카피 약속·고지 hash 범위는 미결정(G-5)으로만 적었다. 근거: `V25`·`V30`~`V33` migration, `operations/BackupDestructionApi.java`, `BackupDestructionJudge.java`, `DeletedIdentityDigestPort.java`, `DeletionCompletionSessions.java`, `AccountDeletionPolicy.java`, `deploy/production/tombstone-export.sh`·`restore.sh`, `deploy/observability/alerts.yml`, `spec-3-10-isolated-restore.md`·`spec-5-2-account-data-erasure.md`·`spec-5-3-backup-destruction-tracking.md`·`spec-5-4-deletion-completion-verification.md`. [E3 R-2, E3 R-3, E5 R-3, E5 R-6]
- **frontmatter** — `version: '1.1'`을 두고 파일 접미사 `-1.1`과 `decision_version: ERD-1.1.0`의 대응을 주석으로 적었다(SPINE·PSD 1.1과 같은 키). 근거: 이 파일 frontmatter. [E2 R-7]
