---
name: 'BokriLog MVP'
type: architecture-spine
purpose: build-substrate
altitude: feature
paradigm: 'Modular Monolith with Ports and Adapters'
scope: 'BokriLog MVP의 금융소스 연동, 통합 포트폴리오, 계산, 저장, 보안, 배포 및 운영 경계'
status: superseded
superseded_by: '../architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md'
created: '2026-08-24'
updated: '2026-09-04'
binds:
  - 'BokriLog MVP'
sources:
  - '../../prds/prd-BokriLog-2026-08-23/prd.md'
  - '../../ux-designs/ux-BokriLog-2026-08-23/DESIGN.md'
  - '../../ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md'
companions:
  - 'PORTFOLIO-SOLUTION-DESIGN.md'
  - 'CI-PLAN.md'
  - '../../EPIC-IMPLEMENTATION-READINESS.md'
  - 'reviews/final-recommendation-validation-2026-08-26.md'
---

# Architecture Spine — BokriLog MVP

## Design Paradigm

모듈형 모놀리스가 배포와 트랜잭션 경계를 단순하게 유지하고, 포트/어댑터가 Bybit·소셜 로그인·관측 제공자를 도메인에서 분리한다. 하나의 코드베이스와 이미지에서 API와 Worker 역할을 분리 실행한다.

```mermaid
flowchart LR
  UI[React SPA] --> API[Web/API adapters]
  API --> ID[identity]
  API --> PORT[portfolio]
  API --> PLAN[plan]
  API --> SYNC[sync]
  API --> ENG[engagement]
  SYNC --> SRC[financial source port]
  SYNC --> PORT
  SYNC --> PLAN
  SYNC --> CALC[calculation: pure]
  PLAN --> PORT
  SRC --> BYBIT[Bybit adapter]
  API --> OPS[operations restriction API]
  SYNC --> OPS
  ENG --> OPS
  DET[signal detector] --> OPS
```

모듈은 다른 모듈의 공개 API에만 의존한다. 다른 모듈의 repository, entity, 내부 패키지 또는 소유 테이블에 직접 접근하지 않는다.

## Invariants & Rules

### AD-1 — 모듈형 모놀리스와 소유권 경계 [ADOPTED]

- **Binds:** identity, source, portfolio, sync, plan, calculation, engagement, operations
- **Prevents:** 같은 엔티티에 복수 소유자와 상충하는 상태 변경 경로가 생기는 것
- **Rule:** 각 모듈은 자기 데이터와 상태 변경을 소유하고 공개 application API로만 협력한다. 외부 제공자는 port 뒤의 adapter로 구현한다.

### AD-2 — Java/Spring 단일 백엔드 [ADOPTED]

- **Binds:** backend, API, Worker, domain calculation
- **Prevents:** 서로 다른 런타임·정밀도·트랜잭션 규칙이 생기는 것
- **Rule:** 백엔드는 Java 25와 Spring Boot 4.1을 사용한다. 금액·비율은 `BigDecimal`, 공식 상태 승격은 Spring 트랜잭션으로 구현하며 `double`과 `float`를 금융 계산에 사용하지 않는다.

### AD-3 — Spring Modulith는 경계 검증 도구 [ADOPTED]

- **Binds:** backend module structure and tests
- **Prevents:** Modulith를 분산 이벤트 시스템으로 오해하거나 모듈 의존이 암묵적으로 순환하는 것
- **Rule:** Spring Modulith는 패키지 경계 검증, 모듈 통합 테스트, 문서 생성에 사용한다. MVP에서 외부 broker와 분산 이벤트 전달을 도입하지 않는다.

### AD-4 — SPA와 백엔드 권위 분리 [ADOPTED]

- **Binds:** frontend, API, authentication, financial display
- **Prevents:** 브라우저와 서버가 서로 다른 공식 금액·상태를 만드는 것
- **Rule:** React/Vite 정적 SPA는 Nginx가 제공하고 같은 오리진 `/api`만 프록시한다. 공식 계산·권한·신뢰 상태는 백엔드만 변경하며 브라우저 저장소에 금융 원본이나 인증 토큰을 영속화하지 않는다.

### AD-5 — PostgreSQL 단일 영속 저장소 [ADOPTED]

- **Binds:** all persistent modules
- **Prevents:** 저장소별로 다른 트랜잭션·십진수·시간·단일성 규칙이 생기는 것
- **Rule:** PostgreSQL 18을 사용한다. 금액·비율은 `numeric`, 사건 시각은 UTC `timestamptz`, 상태 기반 단일성은 조건부 유일 인덱스, 동기화 직렬화는 트랜잭션 범위 잠금과 작업 lease로 강제한다.

### AD-6 — 추가 전용 사실과 공식 버전 포인터 [ADOPTED]

- **Binds:** provider facts, cash flows, source snapshots, portfolio snapshots, calculation versions, audit
- **Prevents:** 늦게 발견한 사건이 과거 계산을 덮어써 재현성을 잃는 것
- **Rule:** 원 사건·외부 자금흐름·정상 스냅샷·계산 버전·감사 기록은 계정의 허용 보존기간 안에서 추가 전용으로 보존한다. 현재 공식 상태는 검증된 버전 포인터로 표현하고 수정은 새 버전으로 수행한다. 검증된 계정 삭제는 이 규칙의 명시적 예외다.

### AD-7 — PostgreSQL 작업 큐와 짧은 트랜잭션 [ADOPTED]

- **Binds:** sync request, Worker, retry, official publication
- **Prevents:** 웹 요청과 외부 API 호출 동안 DB 잠금을 유지하거나 동일 연결 작업이 중복 실행되는 것
- **Rule:** 요청은 `sync_job` 생성 또는 기존 실행 ID 반환까지만 짧게 커밋하고 HTTP 202를 반환한다. Worker는 lease·heartbeat·idempotency key로 선점하며 외부 호출 중 DB 트랜잭션을 열어두지 않는다. 동일 연결은 직렬, 다른 연결은 제한된 동시성으로 처리하고 공식 결과는 별도 짧은 트랜잭션으로 승격한다.

### AD-8 — 증분 동기화와 명시적 신선도 [ADOPTED]

- **Binds:** Bybit wallet balance, transaction log, user-visible freshness
- **Prevents:** 매 조회의 전체 이력 재검증 비용과 서로 다른 시점의 데이터를 하나의 완전한 스냅샷처럼 표시하는 것
- **Rule:** 일상 동기화는 `totalEquity` 1회와 `ERD-1.0.0`이 정한 24시간 또는 7일 중첩 구간부터 닫힌 상한까지 거래 로그의 모든 페이지를 조회한다. 모든 page가 영속화된 뒤에만 watermark를 전진시킨다. 화면과 API는 `measured_at`과 `cashflow_verified_through`를 분리 표시한다. 30일 초과 장기 미접속은 사용자의 계속/새 계획 선택 뒤 하나의 catch-up 작업으로 처리한다. 계획 종료는 전체 이력을 무조건 다시 읽지 않고 기존 완전성 증거의 공백과 마지막 미확정 구간만 검증한다. Bybit 재구성 가능 범위는 최대 2년을 넘는다고 약속하지 않는다.

### AD-9 — 제공자 현재가치·포트폴리오와 BokriLog 계산 경계 [ADOPTED]

- **Binds:** provider current value, source snapshot, portfolio snapshot, compounding plan, return calculations
- **Prevents:** BokriLog가 제공자의 공식 현재가치를 임의로 재계산하거나 계산 모듈이 Bybit 전용 타입에 결합되는 것
- **Rule:** Bybit adapter는 `totalEquity` 원문 십진 문자열을 USD 기준 source snapshot의 권위 입력으로 보존한다. `portfolio`는 같은 기준통화로 검증된 구성 source snapshot ID와 합계가 담긴 불변 portfolio snapshot을 만들고, 계획의 `P0`·`t0`·`E(t)`는 이 portfolio snapshot을 참조한다. 계산 모듈은 제공자 DTO가 아니라 portfolio value와 검증된 외부 자금흐름만 받아 계획금액, 차이, 조정 목표, 남은 필요 등가수익률, 목표 달성도를 계산한다. MVP portfolio는 `USD` 기준통화와 활성 `BYBIT` source 정확히 하나만 허용하므로 합계는 해당 `totalEquity`와 같다. 내부는 DECIMAL128 34자리 HALF_EVEN, 화면 반올림은 HALF_UP 2자리의 버전된 정책을 사용한다.

### AD-10 — 서버 세션 기반 소셜 로그인 [ADOPTED]

- **Binds:** Google/Naver/Kakao login, browser session, CSRF
- **Prevents:** 이메일 계정 병합 오류와 브라우저 토큰 탈취 범위 확대
- **Rule:** OIDC Authorization Code + PKCE를 사용하고 한 `user`는 여러 provider identity를 가질 수 있다. `(issuer, subject)`는 전역 유일하며 이메일 일치로 병합하지 않는다. 연결·해제는 최근 재인증과 원자적 확인을 요구하고 마지막 로그인 수단은 제거하지 못한다. 브라우저에는 HttpOnly/Secure/SameSite=Lax opaque 쿠키만 두고 Spring Session JDBC로 세션을 저장하며 상태 변경 요청은 CSRF 보호를 적용한다.

### AD-11 — Bybit 자격 증명과 키 복구 [ADOPTED]

- **Binds:** Bybit API key/secret, application key ring, backup key
- **Prevents:** DB 유출 시 평문 자격 증명 노출과 서버 상실 후 암호문 복구 불능
- **Rule:** 자격 증명은 AES-256-GCM, 무작위 96-bit nonce, `user_id/connection_id/credential_id/key_version/algorithm_version` AAD로 암호화한다. active/decrypt-only key ring은 DB·Git·로그와 분리해 주입하고 서버·DB·OCI 밖에 복구 사본을 둔다. 백업 키는 애플리케이션 키와 분리하며 관련 데이터와 백업 보존 종료 전 과거 키를 폐기하지 않는다.

### AD-12 — 단일 서버 안전 배포 [ADOPTED]

- **Binds:** Nginx, API slots, Worker, DB migration, rollback
- **Prevents:** 배포 실패가 즉시 전체 중단으로 이어지거나 두 Worker가 같은 작업을 실행하는 것
- **Rule:** Nginx를 고정 진입점으로 두고 평상시 API 한 슬롯만 실행한다. 배포 때 Worker를 drain하고 inactive API를 기동해 readiness·smoke·telemetry를 확인한 뒤 Nginx를 reload한다. Worker는 새 버전 하나만 재개한다. 운영 migration은 별도 단일 실행 역할만 수행하고 API/Worker startup은 schema 검증만 한다. cutover 전에는 old API+new schema와 new API+new schema를 모두 지원하는 expand만 허용하며, contract는 rollback 창 종료 후 별도 배포한다.

### AD-13 — 이중 외부 백업과 복구 검증 [ADOPTED]

- **Binds:** PostgreSQL backup, key recovery, disaster recovery, sessions
- **Prevents:** 같은 OCI 계정 장애로 서버와 백업을 함께 잃거나 데이터만 복원하고 서비스를 재구축하지 못하는 것
- **Rule:** 매일 custom-format dump를 별도 백업 키로 암호화해 OCI와 OCI 밖 저장소에 모두 보관한다. 종료 코드·체크섬·두 업로드·`pg_restore --list`를 완료 조건으로 삼고 모든 backup generation은 30일 안에 파기한다. OCI 밖 versioned recovery manifest는 image digest, Terraform source/state 복구법, DNS/TLS/OAuth/Grafana/backup 설정, 암호화 operational secret, key version, bootstrap 순서를 포함한다. 월 1회 격리 서비스를 전체 재구축하고 키·DB·표본 자격 증명·로그인·세션 폐기를 검증한다. 공개 베타 RPO는 24시간 이하다.

### AD-14 — 환경 분리형 관측성과 검증 [ADOPTED]

- **Binds:** metrics, logs, alerts, health checks, test location
- **Prevents:** 운영 VM의 관측 스택 과부하, self-hosted/Cloud 구현 분기, 호스트 사망을 내부 telemetry만으로 놓치는 것
- **Rule:** 개발 profile에서만 Prometheus·Loki·Grafana·Alloy를 필요 시 실행한다. 운영에는 Alloy만 상주시켜 Grafana Cloud Free로 metric/log를 전송하고 외부 synthetic `/health/live`와 telemetry 부재를 경보한다. 사용자 식별자·자산 금액·Bybit payload·secret/token은 모든 signal에서 금지한다. JUnit, Testcontainers, stub, promtool, k6, logcli는 개발·CI에서만 실행한다.

### AD-15 — OCI 무료 운영 경계와 탈출 조건 [ADOPTED]

- **Binds:** production host, egress IP, service objective, capacity escalation
- **Prevents:** 4 OCPU/24GB를 무과금으로 가정하거나 Always Free를 고가용성 서비스로 표현하는 것
- **Rule:** 무과금 기준은 home region의 OCI A1 단일 VM 2 OCPU/12GB와 regional reserved public IPv4다. 생성 전 콘솔 entitlement·capacity·Always Free 표시·예상 비용 0을 확인한다. API 슬롯당 768MiB, Worker 512MiB, PostgreSQL 3GiB, Alloy 256MiB, Nginx 128MiB에서 시작하고 동일 ARM64 digest의 blue/green peak gate를 통과해야 한다. disk·Grafana 사용량은 70% 경고/85% 긴급, 월 비용은 30,000원 budget alert를 둔다. 운영 등급은 `24×7 상시 실행 목표, best-effort, SLA 없음`이다. 최초 회수, A1 8시간 확보 실패, 유료 사용자 발생 또는 가용성 약속 필요 시 PAYG/유료 VPS로 전환한다.

### AD-16 — 발행 소유권, 원 사건 정체성과 fencing [ADOPTED]

- **Binds:** provider adapter, sync Worker, source facts, source/portfolio snapshots, calculation result, official pointer
- **Prevents:** 모듈별 부분 커밋, 중복·누락된 외부 사건, 만료 lease Worker의 공식 포인터 역행
- **Rule:** `source`는 provider connection과 안정적인 provider account를, `sync`는 immutable `sync_run`, 정규화 source fact, source snapshot, calculation result와 plan-result publication을, `portfolio`는 membership·portfolio snapshot·현재 snapshot pointer를 소유한다. adapter는 provider-neutral DTO만 반환하고 `calculation`은 부작용 없는 결정 함수다. source fact와 source snapshot은 교체 가능한 connection이 아니라 `(provider, verified_external_account_id)`로 유일한 내부 `provider_account_id`에 귀속하며 connection ID는 수집 provenance로만 기록한다. fact 고유성은 `(provider_account_id, category, stable_source_id, revision)`이고 동일 revision 재수집은 no-op, 정정은 새 superseding fact다. source별 `sync_sequence`는 수집 provenance·fencing에만 쓰며 공식 portfolio/plan 상태의 순서를 결정하지 않는다. stale Worker는 `LOST_LEASE` 또는 `SUPERSEDED`로 종료한다.

### AD-17 — 범위별 비상정지와 공지 소유권 [ADOPTED]

- **Binds:** new connection, sync admission/lease, calculation publication/display, advertising, service notices
- **Prevents:** UI-only 정지, 범위마다 다른 제한 해석, 자동 정지 뒤 검증 없는 자동 복구
- **Rule:** `operations`가 durable restriction·service notice·protected operator command·감사 기록을 소유한다. scope는 `plan`, `portfolio`, `calculation_policy_version`, `provider_connection`, `sync`, `advertising`, global feature이며 구체 scope가 global보다 우선한다. 모든 관련 read·admission·lease·publication·display는 effective restriction을 fail-closed로 확인한다. 강한 신호는 60초 안에 설정 범위를 자동 정지하고, 자동 복구는 금지하며 증거·복구 조건을 기록한 권한 있는 수동 명령만 재개할 수 있다. 영향을 특정할 수 없으면 신규 연결과 동기화를 전역 정지한다.

### AD-18 — 광고 경계와 fail-open [ADOPTED]

- **Binds:** manual sync success, consent, frequency cap, ad provider, result navigation
- **Prevents:** 광고가 공식 결과의 성공 조건이 되거나 금융·식별 데이터가 제3자에게 전달되는 것
- **Rule:** `engagement`가 동의, 수동 동기화 성공 trigger, 30분 및 일 3회 cap, provider adapter를 소유한다. 광고는 저장된 공식 결과 뒤의 중립 전환에서만 시도하고 미채움·차단·오류·비동의·접근성 실패는 같은 결과로 즉시 fail-open한다. 금융값·계획값·신뢰 상태·사용자 식별자는 DOM, URL, 제목 또는 provider 요청에 포함하지 않는다.

### AD-19 — 계정 삭제 orchestration [ADOPTED]

- **Binds:** identity, all user-owned module data, sessions, credentials, backups, legal retention
- **Prevents:** 부분 삭제를 완료로 표시하거나 restore가 삭제 계정을 활성 상태로 되살리는 것
- **Rule:** `identity`가 삭제 상태와 orchestration을 소유하고 각 모듈 deletion port를 호출한다. 최근 재인증 후 삭제를 시작하며 즉시 세션·자격 증명·민감 접근과 중복 요청을 차단한다. 일부 실패는 완료로 표시하지 않는다. 암호화된 최소 deletion tombstone을 서비스 데이터 밖에 backup 최대 수명 이상 유지하고 모든 restore 전에 적용한다. 운영 데이터와 해당 계정을 포함한 backup은 요청일부터 30일 안에 접근 불가·파기 상태가 되어야 한다. 법정 분리 보존의 필드·근거·목적·기간은 공개 베타 전 확정한다.

### AD-20 — 사용자 데이터 격리 [ADOPTED]

- **Binds:** every user-owned query, command, job, publication and aggregate
- **Prevents:** client-supplied owner ID 신뢰, 수평 권한 상승, target 존재 여부 유출
- **Rule:** 인증 세션이 유일한 내부 `user_id`를 결정한다. application API와 repository query는 모든 사용자 소유 작업을 이 ID로 scope하고 Worker job과 publication에도 이를 보존·재검증한다. client가 보낸 ownership ID는 권위가 아니다. 타 사용자와 미존재 target은 같은 외부 error code와 관찰 가능한 응답 계약을 사용하며 모든 aggregate에 대한 교차 사용자 통합 테스트를 CI에서 실행한다.

### AD-21 — 읽기 전용 Bybit 연결 admission [ADOPTED]

- **Binds:** Bybit provider connection creation and replacement
- **Prevents:** write-capable·미지원·IP 불일치 key를 먼저 저장하고 나중에 검증하는 것
- **Rule:** `readOnly=1`, supported UTA, expected `userID`, regional reserved IP 포함, 필요한 최소 permission, 실제 wallet read가 하나의 accepted validation result로 모두 성공한 뒤에만 암호화 자격 증명과 connection을 저장한다. 검증된 Bybit main UID는 `provider=BYBIT`의 안정적인 provider account에 귀속하고 자격 증명 교체·재연결은 같은 account를 재사용한다. write capability, 모호하거나 초과된 permission, UID/IP 불일치, live read 실패는 저장 없이 거부하며 자동 retry escalation을 하지 않는다.

### AD-22 — 계획은 제공자가 아니라 통합 포트폴리오에 귀속 [ADOPTED]

- **Binds:** user portfolio, provider connection, portfolio source membership, source snapshot, portfolio snapshot, plan baseline
- **Prevents:** 계획이 Bybit connection에 직접 묶여 이후 다른 거래소·증권 자산을 같은 계획에 넣을 때 데이터 이관이나 계산 의미 변경이 필요한 것
- **Rule:** 사용자에게 기준통화가 불변인 portfolio를 두고 plan은 provider connection이 아니라 portfolio를 참조한다. portfolio source membership은 UTC 반개구간 `[included_at, excluded_at)`이며 같은 portfolio와 provider account의 구간은 겹칠 수 없다. MVP는 사용자별 자동 생성된 `USD` portfolio 하나에 활성 `BYBIT` provider account membership 하나만 허용한다. 다른 거래소·토스증권 source 추가, 여러 source의 cutoff·freshness 합성, 포함 source 간 transfer matching, source 추가·제거 시 scope adjustment는 후속 범위이며 이를 구현하기 전까지 둘 이상의 활성 source를 허용하지 않는다. 포함 시점 가치의 scope adjustment는 비실행 설계 방향일 뿐이며 평가시각·환율/가격 증거·반올림·실패·제거·transfer matching 규칙이 별도 AD와 Story로 채택되기 전에는 구현하지 않는다.

### AD-23 — 포트폴리오 스냅샷 구성과 공식 발행 계약 [ADOPTED]

- **Binds:** provider account, membership interval, source snapshot, portfolio snapshot component, official portfolio pointer, official plan result
- **Prevents:** 연결 해제 뒤 늦은 값, 다른 사용자·계정의 값, 서로 다른 기준시각·통화 또는 부분 결과가 공식 포트폴리오와 계획 계산에 섞이는 것
- **Rule:** `portfolio_snapshot`은 정규화된 `portfolio_snapshot_component` 행으로만 구성하며 component는 `(portfolio_snapshot_id, membership_id)` 유일, `source_snapshot_id`, portfolio 기준통화 환산값과 적용 정책·가격 증거 참조를 가진다. snapshot 합계는 기록된 계산 정책으로 완전한 component 집합을 정확히 합산한 값이어야 한다. MVP는 component 정확히 하나이며 그 USD 값은 Bybit source snapshot의 `totalEquity`와 같다. 구성 source snapshot은 같은 user·provider account의 membership에 속하고 그 권위 `measured_at`이 `[included_at, excluded_at)` 안이며 portfolio 기준통화가 같고 superseded·제한 상태가 아니어야 한다. membership은 작업 admission과 최종 발행 시점 모두 활성이어야 한다. MVP `portfolio_snapshot.measured_at`은 유일한 source snapshot의 provider 측정시각이고 `composed_at`·`published_at`과 분리한다. `portfolio`는 portfolio별 단조 증가 `publication_sequence`와 current snapshot pointer를, `sync`는 plan별 `official_plan_result` pointer를 소유하며 둘 다 `max(created_at)`으로 현재값을 추론하지 않는다. `sync` publication application service가 외부 호출 없는 하나의 outer `REQUIRED` 트랜잭션을 소유하고 portfolio public command는 반드시 여기에 참여한다. `REQUIRES_NEW`, 비동기/after-commit 발행은 금지하며 eligibility·lease·restriction을 CAS 직전에 재검사하고 candidate·component·계산·포인터 중 하나라도 실패하면 모두 롤백한다.

## Consistency Conventions

| Concern | Convention |
| --- | --- |
| Module ownership | `identity`, `source`, `portfolio`, `sync`, `plan`, `calculation`, `engagement`, `operations`가 각자 entity·repository·table을 소유한다. |
| IDs | 내부 식별자는 UUID, 외부 식별자는 제공자 namespace와 함께 저장한다. |
| Time | 외부 원본과 저장은 UTC instant, 계획 일자 경계와 표시는 `Asia/Seoul`이다. |
| Numbers | 외부 숫자는 문자열로 수신해 `BigDecimal`/`numeric`으로 보존하고 계산 정책 버전을 기록한다. |
| API errors | 안정적인 machine code, 사용자 메시지, correlation ID를 반환하고 secret이나 외부 원문을 포함하지 않는다. |
| Mutation | 모듈 공개 command API와 소유 repository만 상태를 바꾼다. 공식 동기화 결과는 하나의 publication transaction으로 승격한다. |
| Logs and metrics | 낮은 cardinality와 allowlist를 사용하며 사용자·금융·자격 증명 데이터는 전송하지 않는다. |
| Images | 운영 이미지는 `linux/arm64`로 CI 검증하고 tag가 아니라 digest로 배포한다. |
| Dependency locking | 아키텍처 표는 허용 계열과 검증 기준을 제공하고 실제 artifact는 Gradle/frontend lockfile과 image digest가 소유한다. |

## Stack

| Name | Allowed line | Verified baseline |
| --- | --- | --- |
| Java | 25 LTS | current Java 25 CPU patch, JDK image digest |
| Spring Boot | 4.1.x | 4.1.1 |
| Spring Modulith | 2.1.x | 2.1.1 official BOM |
| Spring Session JDBC | Spring Boot BOM | Boot 4.1.1 managed version |
| PostgreSQL | 18.x | 18.6 image digest |
| big-math | 2.3.x | 2.3.2 |
| React | 19.2.x | 19.2.8 lockfile |
| Vite | 8.2.x | 8.2.2 lockfile |
| Nginx Open Source | 1.30.x stable | 1.30.4 image digest |
| Grafana Alloy | 1.18.x | 1.18.0 image digest |
| OCI Compute shape | VM.Standard.A1.Flex | console-confirmed 2 OCPU/12GB entitlement |

## Structural Seed

```text
backend/src/main/java/.../bokrILog/
  identity/       # social identity and session-facing application API
  source/         # provider connection, credential ownership and Bybit adapter
  portfolio/      # source membership and immutable portfolio snapshots
  sync/           # jobs, source facts/snapshots, calculation results and publication
  plan/           # immutable plan inputs and active-plan rule
  calculation/    # pure versioned calculation policy and value objects
  engagement/     # consent, ad cap and provider adapter
  operations/     # restrictions, notices, protected commands and audit
frontend/         # React/Vite SPA
deploy/
  compose/        # production and development profiles
  nginx/          # active upstream configuration
  alloy/          # telemetry allowlist, redaction and remote write
  terraform/      # OCI network, VM, volume, reserved IP and alerts
```

```mermaid
flowchart TB
  Internet --> RIP[OCI reserved public IPv4]
  RIP --> NG[Nginx]
  NG --> SPA[Static SPA]
  NG --> ACTIVE[Active Spring API]
  NG -. deploy only .-> INACTIVE[Inactive Spring API]
  WORKER[Spring Worker] --> DB[(PostgreSQL)]
  ACTIVE --> DB
  ACTIVE --> BYBIT[Bybit / OAuth providers]
  WORKER --> BYBIT
  ALLOY[Grafana Alloy] --> GC[Grafana Cloud]
  SYN[External synthetic] --> NG
  DB --> B1[Encrypted dump: OCI Object Storage]
  DB --> B2[Encrypted dump: external store]
```

```mermaid
sequenceDiagram
  participant U as User
  participant A as API
  participant D as PostgreSQL
  participant W as Worker
  participant B as Bybit
  U->>A: synchronize
  A->>D: create or reuse sync_job
  A-->>U: 202 + job id
  W->>D: lease job
  W->>B: wallet + incremental transaction log
  B-->>W: source values and timestamps
  W->>D: append facts + source snapshot
  W->>D: compose portfolio snapshot components
  W->>D: calculate + atomic portfolio/plan pointer publication
  U->>A: poll result
  A-->>U: official result + freshness timestamps
```

## Capability → Architecture Map

| Capability / Area | Lives in | Governed by |
| --- | --- | --- |
| Social login and session | identity | AD-1, AD-10 |
| Read-only Bybit connection | source | AD-1, AD-11, AD-15, AD-21, AD-22 |
| Synchronization and freshness | sync + Worker | AD-6, AD-7, AD-8, AD-23 |
| Portfolio composition and snapshot | portfolio | AD-6, AD-9, AD-22, AD-23 |
| Compounding plan | portfolio + plan | AD-5, AD-6, AD-22, AD-23 |
| Return calculation policy | calculation; persisted result in sync | AD-2, AD-9, AD-16, AD-22, AD-23 |
| Official result publication and fencing | sync publication service + portfolio pointer API | AD-6, AD-7, AD-16, AD-23 |
| Deployment and rollback | deploy + Nginx | AD-12, AD-15 |
| Backup and recovery | deploy backup job | AD-11, AD-13 |
| Observability and verification | Alloy + Grafana Cloud + CI | AD-14 |
| Emergency controls and notices | operations | AD-17 |
| Support intake and incident communication | operations | AD-17 |
| Manual-sync advertising | engagement | AD-18 |
| Account deletion and restore exclusion | identity deletion orchestrator | AD-19 |
| User-data isolation | every module boundary | AD-20 |

## Deferred

- 메시지 broker와 transactional outbox: DB polling 부하, 다중 소비자 또는 독립 Worker 확장이 실제로 필요해질 때 검토한다.
- Redis 세션: 다중 호스트 전환으로 PostgreSQL 세션이 병목이 될 때 검토한다.
- 외부 KMS: 사용자 수·보안 등급·키 순환 빈도가 단일 호스트 secret 운영 범위를 넘을 때 검토한다.
- WAL 기반 PITR: 공개 베타가 24시간보다 짧은 RPO를 요구할 때 도입한다.
- 두 번째 고정 egress와 자동 장애조치: 유료 사용자 또는 명시적 가용성 계약이 생길 때 도입한다.
- Binance·다른 거래소·토스증권 adapter와 다중 금융소스 합산은 MVP 범위 밖이다. source별 공통 valuation cutoff·freshness, 기준통화 환산, source membership 변경, scope adjustment, 포함 source 간 transfer matching 계약이 승인되기 전에는 둘 이상의 활성 source를 허용하지 않는다. 다중 source 활성화 전에는 각 publication sequence가 참조하는 source revision 집합과 동시 갱신 순서도 별도 정책으로 고정한다.
- 주문 실행은 MVP 범위 밖이며 별도 요구사항·위협 모델 없이는 추가하지 않는다.
- `ERD-1.0.0`이 안전 중첩, 지원 Bybit 사건·ID, 비-USDT 환산, 강한 신호 판정표를 고정한다. 미인식 사건이나 필수 환산 누락은 공식 발행을 기본 거부한다.
- **외부 production 출시 차단:** 실제 Google Ad Manager 계정·ad unit·소재·개인정보·접근성 승인과 법정 분리 보존 필드·근거·목적·기간의 법률 승인은 공개 베타 전에 확보한다. 부재 시 광고와 실제 계정 삭제 실행은 비활성화한다.
- `BYBIT-IP-1.0`에 따라 Day 60에 기존 IP 미제한 연결에 30일 전환 공지를 하고 Day 90부터 동기화·재연결을 정지한다. Day 90 이후 신규 IP 미제한 연결은 거부한다.
