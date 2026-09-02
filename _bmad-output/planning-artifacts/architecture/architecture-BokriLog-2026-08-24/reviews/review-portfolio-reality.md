# Portfolio Boundary Current-Reality Review — BokriLog Architecture Spine

- 검토일: 2026-09-02 (Asia/Seoul)
- 대상: `ARCHITECTURE-SPINE.md`, `PORTFOLIO-SOLUTION-DESIGN.md`
- 렌즈: 저장소 근거, 현재 기술 기준선, Bybit 제공자 개념, provider-neutral portfolio/source 경계, MVP/Later 경계
- 최초 판정: **PASS WITH DOCUMENTATION CONDITIONS**
- 현재 판정: **PASS** — 아래 2026-09-02 재검증이 최초 조건을 대체한다.

새 `portfolio`/`source` 경계는 현재 Bybit 단일 연결 MVP를 불필요하게 분산시키지 않으면서 계획이 특정 제공자 connection에 영구 결합되는 것을 막는 현실적인 모델이다. `portfolio`, membership, source snapshot, portfolio snapshot은 같은 PostgreSQL과 Spring 트랜잭션 안에서 구현할 수 있는 일반적인 관계형 aggregate 경계이며 별도 broker, 서비스 분리 또는 다중 제공자 런타임을 요구하지 않는다.

특히 AD-22는 미래 방향과 현재 제공 기능을 구분한다. MVP에는 `USD` portfolio 하나와 활성 `BYBIT` source 하나만 허용하고, 둘 이상의 활성 source, source별 cutoff/freshness 합성, transfer matching, membership 변경과 scope adjustment는 명시적으로 후속 범위다. 따라서 문서는 아직 구현하지 않은 Binance·토스증권·다중소스 합산을 현재 능력으로 주장하지 않는다.

## 판정 근거

| 검증 항목 | 판정 | 저장소 근거와 해석 |
| --- | --- | --- |
| 제공자 중립 경계의 구현 가능성 | PASS | AD-1, AD-9, AD-16, AD-22가 provider adapter → source snapshot → portfolio snapshot → calculation의 단방향 계약을 정의한다. 모듈형 모놀리스와 단일 PostgreSQL 안에서 공개 application API와 하나의 `REQUIRED` 트랜잭션으로 조정 가능하다. |
| 현재 Bybit 값의 의미 보존 | PASS | AD-9와 상세 설계 §5는 MVP portfolio snapshot이 Bybit source snapshot 하나로 구성되고 그 권위 값이 `totalEquity` 원문 십진 문자열이라고 명시한다. 추상화 도입 후에도 화면과 계산 입력은 기존 Bybit 단일 소스 설계와 동일하다. |
| 외부 사건 정체성의 제공자 충돌 방지 | PASS | AD-16과 상세 설계 §6이 외부 식별자를 provider namespace와 함께 저장하고 `(provider, connection_id, category, stable_source_id, revision)` 유일성을 사용한다. 서로 다른 제공자의 동일 ID 충돌을 피할 수 있다. |
| 계획과 연결의 생명주기 분리 | PASS | AD-22는 plan이 connection이 아니라 portfolio를 참조하고 membership이 포함 시작·종료 시각을 보존하도록 한다. 연결 교체·해제 후에도 계획의 소유 대상과 과거 snapshot provenance를 유지할 수 있는 구조다. |
| 다중소스 기능 과장 여부 | PASS | AD-22와 `Deferred`, 상세 설계 §6은 둘 이상의 활성 source를 금지하고 합산 cutoff/freshness, 환율, transfer matching, scope adjustment 계약 승인 전에는 기능을 제공하지 않는다고 반복해서 명시한다. 미래 방향은 기록하지만 현재 동작으로 서술하지 않는다. |
| 현재 기술과 provider 사실의 근거 | PASS WITH REVALIDATION GUARD | `reviews/review-current-reality.md`가 2026-08-26 공식 근거로 Java 25, Spring Boot/Modulith, PostgreSQL, React/Vite, Nginx, Alloy, OCI A1, Bybit `totalEquity`와 transaction log 범위를 검증했다. release/요금제/외부 API는 변동 가능하므로 기존 문서가 요구하듯 출시 직전 및 lockfile/image 변경 시 재검증해야 한다. |
| 구현 증거 | NOT YET APPLICABLE | 저장소에는 현재 제품 backend/frontend 소스, Gradle lockfile, frontend lockfile 또는 운영 image digest가 없다. 이 문서는 구현 완료 증거가 아니라 구현 기준선이다. `Verified baseline`을 실제 설치·배포 버전으로 오해하면 안 된다. |

## 발견 사항

### M-1 — `DEVELOPMENT.md`가 최종 아키텍처와 문서상 충돌한다

`DEVELOPMENT.md`는 “현재 제품 애플리케이션의 기술 스택은 아직 확정되지 않았다”고 적고 있지만, 최종 spine AD-2~AD-5와 Stack 표는 Java/Spring/PostgreSQL/React/Vite 기준선을 이미 채택했다. 런타임 설계의 결함은 아니지만 구현자가 어느 문서를 권위로 봐야 하는지 혼동할 수 있다.

**권고:** 구현 착수 전에 `DEVELOPMENT.md`를 갱신해 `ARCHITECTURE-SPINE.md`가 제품 스택의 권위 문서임을 연결하고, BMAD 도구용 Python/uv와 제품 런타임을 명확히 분리한다.

### M-2 — 명시한 lockfile·digest는 아직 존재하지 않는다

spine은 실제 artifact가 Gradle/frontend lockfile과 image digest를 소유한다고 올바르게 규정하지만, 현재 저장소는 기획 산출물과 BMAD 환경만 있고 해당 artifact는 아직 없다. 따라서 버전 조합은 현실 검증된 설계 기준선이지만 재현 가능한 빌드가 입증된 상태는 아니다.

**권고:** 첫 skeleton story의 수용 기준에 Gradle BOM/lock, frontend lockfile, ARM64 image build, dependency compatibility smoke를 포함하고 생성된 artifact를 이후 버전 권위로 삼는다.

### L-1 — `source`와 `sync`의 저장 소유권은 구현 시 API로 증명해야 한다

`source`는 provider connection/credential을, `sync`는 source fact/source snapshot을, `portfolio`는 portfolio snapshot을 소유한다. 이 분리는 타당하고 현재 문서에도 일관되지만 공식 발행이 하나의 트랜잭션이어야 하므로 구현자가 repository를 우회해 다른 모듈 테이블을 직접 쓰기 쉽다.

**권고:** Spring Modulith 구조 테스트와 하나의 `REQUIRED` application orchestration 통합 테스트로 공개 API 호출, rollback, fencing 및 source/portfolio/calculation 포인터의 원자성을 증명한다. 별도 비동기 이벤트나 독립 트랜잭션을 도입하지 않는다.

## 새 포트폴리오 경계의 현실성 결론

- 현재 비용은 테이블과 명시적 aggregate 경계가 추가되는 정도이며, MVP 런타임 호출 수나 Bybit 동기화 양을 늘리지 않는다.
- MVP 합계는 source 하나의 `totalEquity`와 같으므로 환율·교차 제공자 시간 정렬·내부 transfer matching이 실행 경로에 들어오지 않는다.
- 미래 Toss/Binance 연동 가능성을 보장하는 것은 adapter 이름이 아니라 provider namespace, membership interval, immutable snapshot provenance, plan→portfolio 참조다. 문서는 이 최소 구조만 현재 채택했다.
- 실제 다중소스 기능은 별도 요구사항과 정책 없이는 활성화할 수 없도록 금지되어 있으므로 숨은 구현 약속이 없다.

## 최종 결론

**PASS WITH DOCUMENTATION CONDITIONS.** 새 provider-neutral portfolio/source 경계는 기술적으로 실현 가능하고 현재 Bybit 단일 소스 의미를 보존하며, 미구현 다중소스 동작도 명확히 차단한다. 아키텍처 자체의 수정 차단 사항은 없다. 구현 착수 전 `DEVELOPMENT.md`의 스택 문구를 정리하고, 첫 skeleton에서 lockfile·image digest·Modulith 원자성 테스트를 실제 증거로 만들어야 한다.

## Revalidation — AD-23 및 문서 조건 종료 (2026-09-02)

**현재 판정: PASS. 남은 blocker 없음.**

- **M-1 CLOSED:** `DEVELOPMENT.md`가 제품 기술 기준선의 권위를 `ARCHITECTURE-SPINE.md`로 지정하고, Python/uv는 BMAD 기획 도구 전용이며 제품 코드·lockfile·image digest는 아직 생성 전이라고 명확히 구분한다.
- **M-2 ACKNOWLEDGED / NON-BLOCKING:** lockfile과 image digest는 여전히 없지만 문서가 이를 구현 완료 증거로 주장하지 않는다. 첫 skeleton에서 생성·검증해야 하는 구현 수용 기준이며 현 기획 아키텍처의 현실성 blocker는 아니다.
- **L-1 CLOSED AT DESIGN LEVEL:** AD-23과 상세 설계 §4가 `sync` outer `REQUIRED` 트랜잭션, `portfolio` public command의 mandatory join, `REQUIRES_NEW`·비동기·after-commit·타 모듈 repository 직접 접근 금지, eligibility/lease/restriction 재검사와 전체 rollback을 명시한다. portfolio별 `publication_sequence`와 plan별 pointer version CAS도 source 수집 sequence와 분리됐다.
- **MVP FEASIBILITY PASS:** component 수가 정확히 1이 아니면 application command와 공식 발행을 거부하고, 유일한 component의 USD 값과 Bybit `totalEquity`를 동일하게 강제한다. AD-23은 다중 source 계산을 활성화하지 않으며 현재 Bybit 호출량·환산·transfer matching 의무를 추가하지 않는다.
- **FUTURE CLAIM BOUNDARY PASS:** 1:N component 스키마의 표현 가능성만 준비하고, 다중 source의 공통 valuation cutoff/freshness, 기준통화 환산, publication revision 집합, membership 변경, scope adjustment와 내부 transfer matching은 계속 후속 승인 대상으로 남는다.
