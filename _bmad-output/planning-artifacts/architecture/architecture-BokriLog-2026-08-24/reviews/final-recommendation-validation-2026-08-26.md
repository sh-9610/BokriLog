# BokriLog 최종 인프라 권고안 재검증

- 검증일: 2026-08-26
- 검증 대상: `.memlog.md`에 기록된 OCI A1 단일 서버 + Grafana Cloud 최종 권고안
- 검증 방식: OCI 신뢰성, 보안·재해복구, 자원·관측성 독립 검토 + 최신 공식 문서 대조
- 판정: **조건부 통과 — 필수 수정 5건 반영 후 구현 가능**

> `ARCHITECTURE-SPINE.md`는 아직 코칭용 초안이므로 자동 린트 대상이 아니다. 이번 결과를 반영해 아키텍처 본문을 최종화한 뒤 정식 린트를 수행해야 한다.

## 1. 결론

Spring Modulith 기반 모듈형 모놀리스, 별도 Worker, PostgreSQL, Nginx, 배포 시에만 blue/green, 운영 서버의 Alloy, Grafana Cloud, 개발·CI 전용 테스트 도구라는 큰 방향은 유지해도 된다. 포트폴리오와 소규모 공개 베타에 적합하고 1인 운영 범위도 과도하지 않다.

다만 다음 조건을 충족하지 않으면 운영 배포를 승인할 수 없다.

1. 무료 전용 OCI 계정은 **2 OCPU/12GB를 기준**으로 설계한다. 4 OCPU/24GB는 실제 tenancy 또는 PAYG 무료 적용이 콘솔에서 확인될 때만 사용한다.
2. OCI regional reserved public IPv4를 사용해 Bybit IP 화이트리스트의 고정 송신 주소를 보장한다.
3. 암호화 백업을 OCI와 OCI 밖의 두 장소에 보관하고, 복호화 키도 OCI 밖에 복구 사본을 둔다.
4. 운영 관측성을 Grafana Cloud + Alloy로 단일화하고 외부 synthetic health check를 둔다.
5. 운영 목표를 `24×7 상시 실행 목표, SLA 없는 best-effort`로 정의하고 유료 호스트 전환 조건을 둔다.

신뢰성 있는 24×7 가용성 보장이 요구되는 시점에는 OCI Always Free 단독 운영을 중단하고 유료 호스트를 주 운영 서버로 사용해야 한다.

## 2. 배포 전 필수 수정

### P0-1. OCI A1 무료 한도 전제 교정

2026-06-29 갱신된 Oracle Free Tier 문서는 무료 전용 계정의 A1 한도를 총 **1,500 OCPU-hour/9,000 GB-hour**, 즉 상시 사용 시 **2 OCPU/12GB**로 명시한다. 초과 A1 인스턴스는 비활성화되고 30일 후 삭제될 수 있다. 별도의 Arm 가격 문서에는 paid tenancy 기준 3,000/18,000이 남아 있어 문서 간 차이가 있다.

- 무과금 보장 설계값: A1 2 OCPU/12GB
- 생성 전 게이트: home region, A1 capacity, `Limits, Quotas and Usage`, Always Free 표시, 비용 추정 $0 확인
- 춘천 home region이면 A1 무료안을 사용하지 않는다.
- 2 OCPU에서 Worker drain 후 inactive API를 기동하며, 서버에서는 이미지 빌드와 테스트를 수행하지 않는다.
- 동일 ARM64 이미지 digest로 배포 피크 k6 smoke를 통과해야 한다.

근거: [OCI Free Tier](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm), [Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm), [Arm-Based Compute](https://docs.oracle.com/en-us/iaas/Content/Compute/References/arm.htm)

### P0-2. 암호화 키와 백업의 OCI 단일 장애점 제거

OCI Object Storage만 사용하면 tenancy 잠금·삭제·자격 증명 상실 때 VM과 백업을 함께 잃는다. DB dump가 남아도 애플리케이션 마스터 키가 없으면 Bybit 자격 증명을 복호화하지 못한다.

- 매일 `pg_dump --format=custom` 생성 후 별도의 백업 키로 암호화한다.
- OCI Object Storage와 별도 공급자 또는 암호화 오프라인 저장소에 모두 업로드되어야 성공이다.
- 애플리케이션 key ring과 백업 키의 복구 사본을 서버·DB·OCI 밖의 비밀번호 관리자에 둔다.
- `pg_dump` 종료, 암호화, SHA-256, 두 곳 업로드, `pg_restore --list`를 자동 검사한다.
- `last_successful_backup_age > 26h`를 외부 경보로 둔다.
- 월 1회 실제 복원에서 DB 불변식, 키 복원, 표본 자격 증명 복호화까지 확인한다.
- 복원 후 Spring Session 테이블을 비우고 전체 재로그인시킨다.

### P0-3. Bybit용 고정 송신 IP

기본 ephemeral IP는 인스턴스 종료 시 사라진다. OCI reserved public IP는 인스턴스와 독립적으로 유지되며 같은 리전의 복구 VM에 재할당할 수 있다.

- OCI regional reserved public IPv4를 VM VNIC에 할당한다.
- 사용자는 이 IP를 Bybit API key 화이트리스트에 등록한다.
- 연결 저장 시 `readOnly`, `permissions`, `ips`, `uta`, `userID`를 검증하고 실패하면 저장하지 않는다.
- 복구 순서는 reserved IP 재할당 → 외부 송신 IP 확인 → Bybit smoke test다.
- 다른 클라우드로 복구하면 IP가 바뀌므로 상태를 `BYBIT_IP_REBIND_REQUIRED`로 전환한다.

근거: [OCI Public IP Addresses](https://docs.oracle.com/en-us/iaas/Content/Network/Tasks/managingpublicIPs.htm), [Bybit API Key Information](https://bybit-exchange.github.io/docs/v5/user/apikey-info)

### P0-4. AD-14 관측성 결정 단일화

현재 기록에는 self-hosted 관측 스택을 확정한 문장과 Grafana Cloud 수정 추천이 동시에 존재한다. 구현자가 서로 다른 구성을 만들 수 있으므로 기존 AD-14를 폐기하고 다음으로 대체해야 한다.

- 개발: Prometheus + Loki + Grafana + Alloy를 필요할 때만 실행
- 운영: Alloy만 상주, Grafana Cloud Free에 metrics/logs/alerts 전송
- 운영 Compose에서 Prometheus, Loki, Grafana, Loki Canary 제거
- Grafana Cloud Synthetic Monitoring이 `/health/live`를 5분 간격으로 외부 확인
- Alloy heartbeat 부재, 전송 실패, drop, queue backlog를 경보
- OCI Monitoring/Notifications는 VM·볼륨의 보조 경보로 유지

Grafana Cloud Free는 현재 metrics 10k active series, logs 50GB/월, 14일 보존, API synthetic 100k회/월을 제공한다. 단일 URL 5분 검사는 월 약 8,640회다.

근거: [Grafana Cloud Pricing](https://grafana.com/pricing/)

### P0-5. 운영 등급과 탈출 조건 명시

Always Free 인스턴스는 7일 동안 CPU p95, 네트워크, A1 메모리가 모두 20% 미만이면 회수될 수 있고 SLA와 정식 지원이 없다. A1 재생성도 capacity 부족으로 지연될 수 있다.

- 서비스 목표: `24×7 상시 실행 목표, best-effort, SLA 없음`
- MVP 데이터 목표: `RPO ≤ 24시간`
- RTO: A1 capacity에 좌우되므로 보장값이 아닌 best-effort로 표시
- 탈출 조건: 최초 idle 회수, 8시간 이상 A1 확보 실패, 유료 사용자 발생, 또는 가용성 약속이 필요한 시점에 PAYG/유료 VPS로 전환

이 조건 없이 “24시간 운영”을 “24시간 가용성 보장”으로 표현하면 안 된다.

## 3. 중요 보완 사항

### 자원 한도

2 OCPU/12GB 기준 시작값은 다음과 같이 잡고 실제 부하로 조정한다.

| 구성요소 | 시작 상한 | 비고 |
|---|---:|---|
| API active/inactive | 각 768MiB | inactive는 배포 시에만 기동 |
| Worker | 512MiB | 배포 때 drain 후 정지 |
| PostgreSQL | 3GiB | `shared_buffers` 512MiB~1GiB에서 측정 |
| Alloy | 256MiB | 큐·전송 실패 관찰 |
| Nginx | 128MiB | 외부 진입점 |

OS, Docker, 파일 캐시와 순간 피크를 위해 나머지를 남긴다. CPU가 2개뿐이므로 메모리보다 배포 중 CPU 경합이 우선 검증 대상이다.

### 텔레메트리와 개인정보

- metric label에 user ID, 계획 ID, request ID, 계정 ID, 원시 URL을 금지한다.
- 로그와 metric 모두 API key/secret, token, email, 자산 금액, Bybit payload/query string을 전송하지 않는다.
- Loki label은 `env`, `service`, `level` 같은 낮은 cardinality만 허용한다.
- 보안·사용자 행위 감사 원장은 PostgreSQL 테이블을 권위 원본으로 두며 운영 로그로 대체하지 않는다.
- Docker 로그는 24~48시간 회전 보관하고 Alloy 저장 경로를 영속화한다.

### Terraform과 secret

- Bybit 키, 마스터 키, DB 비밀번호, OAuth secret, Grafana token을 Terraform state, tfvars, plan, Git에 넣지 않는다.
- Terraform 코드는 Git에 보관하고 state는 최소 권한 remote backend에 둔다.
- OCI 계정 상실 복구를 위해 암호화된 state 사본을 OCI 밖에도 보관한다.

### 백업 용량

무료 전용 계정의 Object Storage는 세 저장 등급 합산 20GB다. 사용자가 제시한 10+10+10은 paid/trial 계정 표기일 수 있으므로 실제 콘솔을 기준으로 한다. 백업 정책은 개수뿐 아니라 총 byte 한도와 70% 사용 경보를 둔다.

## 4. 통과한 결정

- Spring Modulith 모듈형 모놀리스와 별도 Worker 경계
- PostgreSQL 작업 큐를 먼저 구현하고 broker/outbox는 확장 경로로 두는 선택
- Bybit의 `totalEquity`를 권위 입력으로 저장하고 BokriLog 고유 계획 지표만 계산하는 경계
- AES-256-GCM과 DB 외부 key injection
- 배포 시에만 두 번째 API 슬롯을 띄우는 단일 서버 blue/green
- 테스트 도구를 운영 서버에 설치하지 않고 개발·CI에서만 실행
- ARM64 지원 공식 이미지를 사용하고 digest와 플랫폼을 CI에서 검증하는 방법
- 운영 서버에는 Alloy만 두고 Grafana Cloud를 사용하는 경량 관측 구성

## 5. 구현 승인 게이트

- [ ] OCI 콘솔에서 home region, 2 OCPU/12GB 이상 entitlement, A1 capacity, $0 예상 비용 확인
- [ ] reserved public IPv4 할당 및 Bybit IP 검증 통과
- [ ] AD-14를 개발 self-hosted / 운영 Grafana Cloud로 단일화
- [ ] OCI 밖 key 복구 사본과 두 번째 암호화 백업 저장소 구성
- [ ] 월간 restore drill에 key 복원·표본 복호화·세션 폐기 포함
- [ ] 외부 `/health/live` synthetic와 telemetry absence 경보 구성
- [ ] ARM64 동일 digest CI 및 배포 피크 smoke 통과
- [ ] `RPO ≤ 24h`, best-effort RTO, 유료 호스트 전환 조건 문서화

모든 항목을 충족하면 포트폴리오 및 초기 공개 베타 아키텍처로 승인 가능하다.
