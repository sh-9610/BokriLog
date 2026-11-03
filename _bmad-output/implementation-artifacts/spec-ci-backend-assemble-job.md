---
title: 'CI 임계 경로 분리: backend jar 생성 job'
type: 'chore'
created: '2026-11-03'
status: 'done'
review_loop_iteration: 0
baseline_commit: '6b16acb698baaf858ebbbf394c7b6709a363c470'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-ci-critical-path.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 제품 PR의 CI wall-clock이 26~30분이다(최근 5회: `backend-quality` 12~16분 → `backup-rehearsal` 11.5~14분). 리허설 셋과 same-origin이 기다리는 것은 시험 통과가 아니라 jar 파일인데, 그 jar가 시험을 돌리는 같은 Gradle `build`에서 나와 뒤 job들이 시험 14분을 통째로 기다린다.

**Approach:** jar만 만드는 job `backend-assemble`(컴파일 + `bootJar`, 약 1분 30초)을 더해 jar 소비 job 넷이 그것만 기다리게 한다. `backend-quality`는 지금처럼 `build`를 돌리되 jar를 올리지 않고 자기 jar의 SHA-256을 job output으로 내며, `backend-assemble`도 같은 output을 낸다. `ci-required`의 집계가 두 digest의 일치를 함께 판정해 CI-PLAN §8의 "시험이 검증한 바이트를 통합이 소비한다"를 가정이 아니라 증거로 유지한다. bootJar는 Gradle 재현 가능 archive 설정으로 바이트 단위 결정적이게 만든다.

## Boundaries & Constraints

**Always:**
- 필수 검사 이름 `ci-required`, 기존 여덟 job의 이름, artifact 이름 `backend-jar-<run_id>-<run_attempt>`, 소비 job 넷의 내려받기 단계·게이트(`docs-only`, `backup-restore != 'false'`)는 그대로다. 생산자만 `backend-assemble`로 바뀐다.
- `backend-assemble`은 `changes`만 기다리고 docs-only 게이트를 달며, jar 업로드가 성공 조건이다(`if-no-files-found: error`). 소비 job 넷의 `needs`에서 `backend-quality`를 `backend-assemble`로 바꾼다. `ci-required`는 아홉 job 전부를 `needs`로 기다린다.
- 집계는 `aggregate-results.sh`가 소유한다 — `ASSEMBLE_RESULT`는 다른 제품 job과 같은 기대(제품 변경 success·문서-only skipped)이고, 두 digest(`ASSEMBLE_JAR_SHA256`·`QUALITY_JAR_SHA256`)는 두 job이 success일 때 둘 다 64자 hex이며 같아야 한다. 하나라도 비거나 다르면 종료 1이고 메시지가 "같은 커밋의 두 빌드가 다른 jar를 냈다"고 말한다. 둘 중 한 job이라도 success가 아니면 digest를 판정하지 않는다(그 job의 결과가 이미 실패다).
- `build.gradle.kts`가 모든 archive task에 `isPreserveFileTimestamps = false`·`isReproducibleFileOrder = true`를 건다. 같은 커밋·같은 JDK의 두 `clean bootJar`가 같은 SHA-256을 내야 한다.
- 판정은 스크립트에 두고 `test-ci-scripts.sh`가 새 job 구조·집계 계약을 단정한다(I/O 표 전부). `ci.yml`의 인라인 `run`은 digest 계산 한 줄(`sha256sum`)뿐이다.
- `bash .github/scripts/test-ci-scripts.sh`가 통과하고, PR의 실제 CI가 아홉 job green으로 끝난 뒤에만 완료다.

**Ask First:**
- 두 `clean bootJar`가 바이트 단위로 같지 않을 때(JDK·Boot 플러그인의 비결정성) 일치 검사를 class 항목만 비교하는 것으로 좁히거나 없애는 것.
- `backend-quality`의 Gradle 명령을 `build`에서 `check`로 바꾸는 것(jar를 만들지 않으면 digest를 낼 수 없다 — 바꾸지 않는다).
- CI-PLAN §8 "두 품질 job이 산출물을 전달한다" 문장의 새 버전 발행. 이 SPEC은 편차를 Design Notes에 기록만 한다.

**Never:**
- 리허설·same-origin 시나리오, `deploy/**`, 분류기 규칙(`classify-changes.sh`)·사유 enum 변경. 백엔드 시험 병렬화·suite 배치(별도 defer). 리허설의 PR 경로 분리(별도 defer).
- top-level `paths` 필터, `continue-on-error`, 재시도. 각 job의 timeout 값 변경(새 job만 8분을 받는다).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 제품 PR 정상 | 아홉 job success, 두 digest 같음 | 집계 종료 0, 요약 표에 `backend-assemble` 행과 digest 한 줄 | N/A |
| digest 불일치 | 두 job success, digest 다름 | 종료 1, `::error::` "같은 커밋의 두 빌드가 다른 jar를 냈다" | 병합 차단 |
| digest 하나 비거나 모양 아님 | assemble success, `QUALITY_JAR_SHA256` 빈 값 또는 64자 hex 아님 | 종료 1, 어느 쪽이 빈 값인지 명시 | 병합 차단 |
| assemble 실패 | `ASSEMBLE_RESULT=failure` | 종료 1 "backend-assemble job이 failure로 끝났다", digest 판정 없음 | 병합 차단 |
| 문서-only | 아홉 job 중 제품 job 일곱 skipped, digest 빈 값 | 종료 0, digest 판정 없음 | N/A |
| 문서-only인데 assemble success | `DOCS_ONLY=true`, `ASSEMBLE_RESULT=success` | 종료 1 "분류와 게이트가 어긋난다" | 병합 차단 |
| 옛 workflow 입력 | `ASSEMBLE_RESULT` 미전달(빈 값), 제품 변경 | 종료 1 "결과가 비었다 … needs에서 빠졌다" | 병합 차단 |
| 소비 job의 jar 출처 | 네 job이 `backend-jar-…`를 내려받음 | assemble이 올린 바이트를 그대로 씀, gradlew 호출 없음 | N/A |

</frozen-after-approval>

## Code Map

- `.github/workflows/ci.yml:6-12` 머리 그림, `:290-375` `backend-quality`(`:314-316` job `outputs.jar-sha256`, `:352` `build`, `:354` `jar digest` 단계 — jar 업로드는 제거), `:377-438` 새 `backend-assemble` job(`:379` `needs: changes`, `:391-392` outputs, `:417` `Gradle bootJar` → `:421` `jar digest` → `:430` 업로드, timeout 8분, `cache-dependency-path`는 `backend-quality`와 다르게). `needs`: `:442`·`:553` `[changes, frontend-quality, backend-assemble]`, `:622`·`:687` `[changes, backend-assemble]`, `:753` ci-required에 `backend-assemble` 추가. `:777-780` env에 `ASSEMBLE_RESULT`·`ASSEMBLE_JAR_SHA256`·`QUALITY_JAR_SHA256`.
- `.github/scripts/aggregate-results.sh:75` `write_summary`의 digest 한 줄, `:103-104` changes 실패 시 판정 불가 행(job 목록 순서: `backend-quality` 다음 `backend-assemble`), `:203` `is_sha256`, `:214` `check_jar_digest`, `:261-267` `check_job` 호출 열과 그 뒤 `check_jar_digest`.
- `.github/scripts/test-ci-scripts.sh:769-783` digest fixture와 `check_aggregate`(env에 `ASSEMBLE_RESULT`·digest 둘 — 기본값은 backend 결과·같은 digest, override 변수로 분기), `:977-1090` 집계 사례(digest I/O 표·summary 줄·skip/문서-only 사례), `:1458-1500` job 목록·runs-on 9·`needs` 단정(소비 job의 needs에 `backend-quality` 없음 포함), `:1526` 집계 입력 열, `:1593-1608` `backend-quality`·`backend-assemble` 단계 목록, `:1663` docs 게이트 5개·gated_job 목록, `:1811-1860` backend-assemble 단정(gradlew 한 줄·단계 순서·캐시 키 분리), `:1865-1894` jar digest 블록 단정(두 job 블록 글자 동일), `:1929-1958` artifact 생산자 단정, `:1970` 통합 job gradlew 금지 단정은 유지.
- `backend/build.gradle.kts:176-188` `bootJar` 설정 옆 — `tasks.withType<AbstractArchiveTask>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }`와 근거 주석.
- `CLAUDE.md:125` CI 구조 문장(`:287` 미룬 결정 건수), `DEVELOPMENT.md:738-764` 그림·job 표·`backend-assemble` 문단, `_bmad-output/implementation-artifacts/epic-1-context.md:45` CI 구조 문장, `AGENTS.md`(구조 문장 없음 — 손대지 않음). 머리 그림은 `changes → frontend-quality·backend-quality·backend-assemble → (assemble) same-origin·deploy·backup·restore → ci-required`.
- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` — 이 chore의 PR이 판정할 defer: 2026-11-03에 더한 둘(163 시험 fork 분할·164 리허설 PR 경로 분리, 미해소), 리뷰 defer 165(소비 job의 내려받은 jar 대조 없음, 미해소), 항목 20(통합 job의 `npm ci` 중복 — 이 chore가 건드리지 않음, 미해소 유지).
- 걸린 열린 회고 액션: `epic-3-retro-item-31`(셸 규약 검사 통일)은 새 셸 코드가 `aggregate-results.sh` 안에만 들어가므로 건드리지 않고 지나간다. `epic-3-retro-item-37`(CI 임계 경로)은 done이며 이 chore는 그 후속이다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/build.gradle.kts` -- archive 재현 설정 -- 두 job의 jar가 바이트 단위로 같게
- [x] `.github/scripts/aggregate-results.sh` -- `ASSEMBLE_RESULT` 행과 digest 일치 판정 -- 판정은 스크립트가 소유
- [x] `.github/scripts/test-ci-scripts.sh` -- 집계 사례(I/O 표)·job 구조·단계·입력 단정 갱신 -- 계약 고정
- [x] `.github/workflows/ci.yml` -- `backend-assemble` 추가, `backend-quality` 업로드 제거·digest output, `needs` 다섯 곳, ci-required env -- 임계 경로 분리
- [x] `CLAUDE.md`, `DEVELOPMENT.md`, `AGENTS.md` -- 구조 문장·그림·표 -- 문서 정합
- [x] `deferred-work-resolutions.md` -- 위 defer 판정 -- done 전환 규칙
- [ ] PR을 열 때 `CLAUDE.md` `## 현재 진행 지점` 표에 이 chore 행(PR 번호)과 `deferred-work-resolutions.md` 2026-11-03 문단의 PR 번호를 채운다 -- 추적 산출물 정합

**Acceptance Criteria:**
- Given 같은 커밋, when `clean bootJar`를 두 번 돌리면, then `bokrilog-backend.jar`의 SHA-256이 같다.
- Given 제품 PR, when CI가 돌면, then `backup-rehearsal`·`restore-rehearsal`·`deploy-rehearsal`·`same-origin-integration`이 `backend-quality` 종료 전에 시작하고 아홉 job이 green이며 집계 요약에 digest 일치가 적힌다.
- Given 문서-only PR, when CI가 돌면, then `changes`·`ci-required`만 돌고 종료 0이다.
- Given `bash .github/scripts/test-ci-scripts.sh`, when 로컬에서 돌리면, then 모든 단정이 통과한다.

## Spec Change Log

## Design Notes

실측(분): backend-quality 15.6 = Gradle test 14.4 + 컴파일·jar 1.2. 분리 뒤 임계 경로는 max(backend-quality 15.6, assemble 1.5 + backup 12 = 13.5, frontend 4.5 + same-origin 7 = 11.5) ≈ 16분으로 약 11분 준다. 러너 분은 assemble 1.5분이 더 들고 실패 PR에서는 시험이 끝나기 전에 리허설이 돌아 낭비가 생길 수 있다(CI-PLAN §10 비용 주의).

CI-PLAN §8은 "두 품질 job이 산출물을 전달하고 통합은 다시 build하지 않는다"고 적었다. 이 chore 뒤 backend 산출물의 생산자는 품질 job이 아니지만, 품질 job이 자기 빌드의 digest를 내고 집계가 둘의 일치를 요구하므로 "통합이 소비한 바이트 = 시험이 돌던 바이트"는 여전히 기계가 확인한다. digest를 artifact가 아니라 job output으로 넘기는 이유는 내려받기 경합·실패 job의 빈 artifact 처리를 없애기 위해서다.

두 job의 JDK는 `setup-java`가 각각 해석한 Temurin 25 최신이라 그 사이 patch가 갈리면 digest가 어긋날 수 있다. 그때는 재실행이 답이고 조용히 통과시키지 않는다(Ask First 첫 항). 두 job의 `jar digest` 단계는 `java -version`과 `unzip -lv`(항목 이름·크기·CRC)를 로그에 남겨 어긋남의 원인(JDK patch·어느 항목)을 가를 자료를 두고, digest 치환 실패(jar 없음)가 단계를 실패시키도록 `&&` 기록이다. `backend-assemble`은 digest를 업로드 앞에서 내 digest 없는 jar가 artifact 저장소에 남지 않게 한다. 두 Gradle job의 setup-java 캐시 키는 `cache-dependency-path`로 일부러 가른다 — 같으면 먼저 끝나는 `backend-assemble`(compile·runtime classpath만)이 저장한 캐시를 `backend-quality`가 받아 시험 classpath를 매번 다시 내려받는다.

**비용·동시성.** 러너 분은 실행마다 약 +1.5분(새 job)이다. 실패 PR에서는 `backend-quality`의 시험이 끝나기 전에 리허설 셋과 통합이 이미 돌므로 낭비 상한은 그 넷의 합(리허설 셋 ≈ 24분 + 통합 ≈ 7분)이다 — 전에는 시험 실패가 그 넷을 건너뛰게 했다. peak 동시 job은 6(`frontend-quality`·`backend-quality`·`backend-assemble` 셋 뒤 리허설 셋 + 통합이 겹치는 구간; 계정 한도 20 안)이다. 새 job의 timeout 8분은 그 job의 실측이 아니라 `backend-quality` 안의 컴파일·jar 1.2분에 cold 캐시의 의존 내려받기 여유를 더한 값이고, 첫 실측이 쌓이면 CI-PLAN 새 버전이 보정한다.

**수치 출처.** 이 SPEC의 wall-clock·job 시간은 최근 전체 실행 run ID 41330046287·39048094877·39045451384·39030031416·38940214120의 job 시각이고, "Gradle test 14분 25초"는 39048094877의 `backend-quality` 로그, 클래스별 시간은 2026-11-03 로컬 `gradlew test` 보고서다.

**남겨 둔 서술.** `deploy/compose/compose.yaml:19`·`deploy/compose/verify-same-origin.sh:19`·`deploy/production/rehearse-deploy.sh:12`의 "두 품질 job의 산출물" 류 주석은 이 chore의 Never(`deploy/**` 변경 금지) 범위라 고치지 않았다 — backend jar의 생산자는 이제 `backend-assemble`이고, 그 주석은 다음에 그 파일을 고치는 작업이 손대는 범위에서 바꾼다.

## Verification

**Commands:**
- `bash backend/gradlew --project-dir backend clean bootJar && sha256sum backend/build/libs/bokrilog-backend.jar` ×2 -- expected: 같은 digest (macOS는 `shasum -a 256`을 쓴다)
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 통과, 실패 0
- `bash backend/gradlew --project-dir backend build` -- expected: 통과(설정 변경이 시험에 영향 없음)
- PR CI -- expected: 아홉 job green, 리허설 시작 시각 < backend-quality 종료 시각, `ci-required` 요약에 digest 일치

**로컬 실측(2026-11-03, Windows 11, Temurin 25, Gradle 9.7.1).** `clean bootJar` ×2와 `clean build`의 `bokrilog-backend.jar` SHA-256이 셋 다 `7a3cd439003bfaf5b7a7a93c7e9e1d3945a22acc8ef34cb927d817a1b9ccaf53`이다 — `build`(시험 포함)와 `bootJar`만의 jar가 바이트까지 같다. `clean build`는 19분 42초였고 `BybitAdapterTest` 5건이 `java.net.SocketException: Bad address: listen`(loopback 포트 0 바인딩)으로 실패했는데, 이 build 스크립트 변경을 되돌린 baseline에서도 같은 예외가 재현돼 이 호스트의 Windows 환경 문제(Hyper-V 제외 포트 범위)이지 이 변경의 결과가 아니다 — 시험 통과 여부는 PR CI `backend-quality`가 본다.

## Suggested Review Order

**임계 경로 분리 — jar 생산자 job**

- 진입점: 시험 없이 컴파일·bootJar만 하는 새 생산자. jar 소비 job 넷이 이것만 기다린다
  [`ci.yml:377`](../../.github/workflows/ci.yml#L377)
- 생산자와 시험 job의 setup-java 캐시 키를 일부러 가른다 — 시험 classpath 없는 캐시가 품질 job을 덮지 않게
  [`ci.yml:413`](../../.github/workflows/ci.yml#L413)
- 소비 job 넷의 `needs`가 `backend-quality` → `backend-assemble`로 바뀐 자리(배포·백업·복원도 같은 모양)
  [`ci.yml:442`](../../.github/workflows/ci.yml#L442)

**"시험이 검증한 바이트"의 증거 — digest 일치**

- 품질 job은 jar를 올리지 않고 자기 jar의 SHA-256만 output으로 낸다. 치환 실패는 단계 실패다
  [`ci.yml:354`](../../.github/workflows/ci.yml#L354)
- 집계가 두 digest를 대조하는 판정. 두 job이 모두 success일 때만 보고, 불일치·빈 값·모양 아님은 종료 1
  [`aggregate-results.sh:214`](../../.github/scripts/aggregate-results.sh#L214)
- ci-required가 두 job의 output을 집계 입력으로 넘기는 배선
  [`ci.yml:779`](../../.github/workflows/ci.yml#L779)
- bootJar를 바이트 단위로 결정적이게 만드는 archive 재현 설정 — 두 러너의 jar가 같아야 위 판정이 성립한다
  [`build.gradle.kts:185`](../../backend/build.gradle.kts#L185)

**계약 시험·문서**

- I/O 표의 집계 사례(불일치·빈 값·판정 생략·문서-only)
  [`test-ci-scripts.sh:984`](../../.github/scripts/test-ci-scripts.sh#L984)
- job 목록·순서·needs·runs-on 9개 단정
  [`test-ci-scripts.sh:1458`](../../.github/scripts/test-ci-scripts.sh#L1458)
- 생산자 job의 gradlew 호출이 bootJar 한 줄뿐이라는 단정과 캐시 키 분리 단정
  [`test-ci-scripts.sh:1821`](../../.github/scripts/test-ci-scripts.sh#L1821)
- CI 구조 그림·job 표·분리 근거
  [`DEVELOPMENT.md:741`](../../DEVELOPMENT.md#L741)
- 이 chore가 판정한 defer 163·164·165
  [`deferred-work-resolutions.md:27`](deferred-work-resolutions.md#L27)
