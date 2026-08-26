# Current-Reality Review — BokriLog Architecture Spine

- 재검토일: 2026-08-26 (Asia/Seoul)
- 대상: `ARCHITECTURE-SPINE.md` 수정본
- 렌즈: 실제 존재 여부, 현재 안정 버전, 공식 호환성, OCI entitlement와 단일 VM 실행 적합성
- 최종 판정: **PASS**

이전 검토의 차단 사항은 모두 닫혔다. Stack이 `Allowed line`과 `Verified baseline`을 분리하고 실제 artifact는 lockfile과 image digest가 소유하도록 변경되어, 보안 patch 적용 가능성과 재현성을 동시에 확보했다. OCI 무료 한도와 단일 VM 자원·비용·탈출 조건도 현재 공식 조건에 맞게 명시됐다.

## 이전 발견의 종료 확인

| 이전 발견 | 수정 확인 | 상태 |
| --- | --- | --- |
| Nginx 1.29.5가 오래된 운영 핀 | `1.30.x stable / 1.30.4 image digest`로 변경 | CLOSED |
| Spring Boot 4.1.0과 Spring Modulith 2.1.0이 한 patch 뒤처짐 | `4.1.x / 4.1.1`, `2.1.x / 2.1.1 official BOM`으로 변경 | CLOSED |
| Spring Session 독립 핀의 BOM 충돌 가능성 | `Spring Boot BOM / Boot 4.1.1 managed version`으로 변경 | CLOSED |
| major/minor 표기와 실제 배포 핀이 혼재 | Allowed line, Verified baseline, lockfile/digest 권위를 분리 | CLOSED |
| PostgreSQL·React·Vite 기준 patch 미명시 | PostgreSQL 18.6, React 19.2.7, Vite 8.2.2 기준 명시 | CLOSED |
| OCI 무료 자원과 실행 여유가 모호 | 2 OCPU/12GB entitlement, 역할별 시작 예산, blue/green peak gate 명시 | CLOSED |

## 현재 Stack 검증

| 항목 | 판정 | 검증 메모 |
| --- | --- | --- |
| Java 25 LTS | PASS | 공식 LTS이며 Spring Boot 4.1.1의 Java 17~26 지원 범위에 포함된다. 최신 CPU patch와 JDK image digest 정책이 적절하다. |
| Spring Boot 4.1.x / 4.1.1 | PASS | 현재 안정 patch와 일치한다. |
| Spring Modulith 2.1.x / 2.1.1 | PASS | 현재 안정 patch와 일치하며 official BOM 사용이 적절하다. |
| Spring Session JDBC / Boot BOM | PASS | 독립 override 대신 Boot BOM이 관리하므로 조합 불일치 가능성을 줄인다. |
| PostgreSQL 18.x / 18.6 | PASS | 18은 current major이고 18.6은 현재 보수 릴리스다. image digest가 재현성을 보장한다. |
| big-math 2.3.x / 2.3.2 | PASS | 공식 저장소가 제시하는 dependency와 일치한다. 금융 공식에는 별도 golden/property test가 필요하지만 버전 차단 사유는 아니다. |
| React 19.2.x / 19.2.7 | PASS | 현재 계열과 patch 기준에 맞으며 frontend lockfile이 실제 버전을 소유한다. |
| Vite 8.2.x / 8.2.2 | PASS | 현재 지원 minor와 patch 기준에 맞으며 frontend lockfile 정책이 적절하다. |
| Nginx 1.30.x stable / 1.30.4 | PASS | 현재 stable 계열과 기준 버전에 맞고, 이전 1.29.5 보안 patch 누락 문제가 제거됐다. |
| Grafana Alloy 1.18.x / 1.18.0 | PASS | 현재 latest 계열이며 공식 ARM64 artifact가 존재한다. |
| OCI VM.Standard.A1.Flex | PASS WITH RUNTIME GUARD | Always Free 전용 tenancy의 현재 문서상 한도인 2 OCPU/12GB와 일치한다. console entitlement·capacity·Always Free·예상 비용 0 확인이 공급 조건 변화를 방어한다. |

## OCI 및 단일 VM 실행 적합성

AD-15의 시작 메모리 예산은 blue/green 중 두 API 슬롯을 모두 포함해 약 5.4GiB다.

- API 2개: 1.5GiB
- Worker: 0.5GiB
- PostgreSQL: 3GiB
- Alloy: 0.25GiB
- Nginx: 0.125GiB

12GB VM에서 OS, container runtime, filesystem cache, 일시적 migration·배포 부하를 위한 여유가 남는다. 동일 ARM64 digest의 blue/green peak gate가 있으므로 계산상 여유를 실제 측정으로 확인한다. CI에서 image를 빌드하고 운영 VM에서는 pull/run만 수행하는 전제와도 맞는다.

Always Free의 capacity 부족·회수·SLA 부재는 제거할 수 없는 외부 위험이지만 다음 규칙으로 정직하게 통제된다.

- 운영 등급을 `24×7 상시 실행 목표, best-effort, SLA 없음`으로 명시
- 생성 전 console entitlement와 예상 비용 0 확인
- 최초 회수 또는 A1 8시간 확보 실패 시 PAYG/유료 VPS 전환
- 유료 사용자 또는 가용성 약속 발생 시 무료 호스트에서 이탈
- regional reserved public IPv4로 Bybit allowlist의 egress identity 유지
- OCI 밖 복구 manifest와 백업으로 tenancy 상실 복구 경로 유지

따라서 포트폴리오 및 공개 베타라는 현재 운영 등급에서는 수용 가능하다. 유료 서비스의 보장형 24×7 인프라로 오해하지만 않으면 된다.

## 관측성과 외부 서비스 현재성

- Grafana Cloud Free의 현재 공식 범위인 10k metric series, logs 50GB/month, 14일 retention, 3 active users에 맞는다. AD-15의 70%/85% 경보와 AD-14의 cardinality allowlist가 무료 한도 초과를 방어한다.
- Alloy만 운영 VM에 두고 외부 synthetic 및 telemetry absence를 경보하는 구조는 VM 전체 사망을 내부 관측으로 놓치는 문제를 피한다.
- Bybit V5 `totalEquity`는 Unified Account USD total equity로 현재 제공된다. transaction log의 최대 2년 범위와 변동성 시 지연을 AD-8이 명시해 API 능력을 과장하지 않는다.
- Google, Naver, Kakao는 현재 OIDC Authorization Code 흐름을 제공한다. Naver는 OIDC 전용 endpoint, Kakao는 OIDC 활성화가 필요하므로 provider별 configuration test가 필요하지만 설계 선택은 유효하다.

## 남은 관찰 사항

High/Critical 현재성 문제는 없다. 아래는 구현·운영 검증 항목이며 아키텍처 차단 사항이 아니다.

- lockfile 및 image digest 갱신 PR마다 ARM64 build, SBOM/CVE scan, smoke test를 수행한다.
- 월 1회 patch 확인은 최소 주기이며 Nginx·JDK·PostgreSQL 보안 공지는 즉시 처리한다.
- OCI console의 예상 비용 0과 entitlement 확인 결과를 provisioning 로그 또는 체크리스트 증거로 남긴다.
- Grafana Cloud 플랜과 OCI Free Tier는 외부 정책이므로 공개 베타 출시 직전 다시 확인한다.

## 공식 근거

- [Spring Boot 4.1.1 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot 4.1.1 발표](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/)
- [Spring Modulith 공식 문서](https://docs.spring.io/spring-modulith/reference/)
- [PostgreSQL 18.6 문서](https://www.postgresql.org/docs/18/)
- [React 공식 버전 목록](https://react.dev/versions)
- [Vite 공식 홈페이지](https://vite.dev/)
- [Vite 릴리스 정책](https://vite.dev/releases)
- [Nginx 공식 다운로드](https://nginx.org/en/download.html)
- [Grafana Alloy 릴리스](https://github.com/grafana/alloy/releases)
- [OCI Always Free 공식 문서](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
- [OCI public IP 공식 문서](https://docs.oracle.com/en-us/iaas/Content/Network/Tasks/managingpublicIPs.htm)
- [Grafana Cloud 가격](https://grafana.com/pricing/?tab=free)
- [Naver OIDC 개발 가이드](https://developers.naver.com/docs/login/devguide/devguide.md)
- [Kakao OIDC](https://developers.kakao.com/docs/ko/kakaologin/rest-api)
- [Bybit Wallet Balance](https://bybit-exchange.github.io/docs/v5/account/wallet-balance)
- [Bybit Transaction Log](https://bybit-exchange.github.io/docs/v5/account/transaction-log)

## 최종 결론

**PASS.** 이전 현재성 리뷰의 모든 High/Blocker 발견이 수정됐다. 현재 Stack과 OCI/runtime guard는 2026-08-26 기준 포트폴리오·best-effort 공개 베타 목적에 적합하다.
