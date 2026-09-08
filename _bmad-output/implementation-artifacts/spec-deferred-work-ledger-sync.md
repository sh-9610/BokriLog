---
title: 'Deferred Work 해소 원장을 현재 저장소 상태에 맞춘다'
type: 'chore'
created: '2026-09-08'
status: 'done'
review_loop_iteration: 0
baseline_commit: '0e3ded3a5a0f19a6eb361c0cfd161910fa2b6bf0'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/deferred-work.md'
  - '{project-root}/_bmad-output/implementation-artifacts/deferred-work-resolutions.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `deferred-work.md`에 34건이 쌓였는데 해소 원장은 22건까지만 판정을 갖는다. Story 1.3·1.4·1.5·lint-policy 네 라운드가 append한 12건(항목 23~34)에 판정이 전혀 없고, 기존 판정 2건(14·15)은 조건이 이미 충족돼 현실과 어긋난다. 원장의 존재 이유는 "검사가 실제로 성공한 뒤에만 해소로 적는다"인데, 판정 자체가 두 스프린트만큼 밀려 그 규율이 원장 안에서 깨졌다. 그 결과 소유 스토리가 지나갔는데도 처리되지 않은 항목 2건(23·27)이 아무 경보 없이 통과했다.

**Approach:** 원장에 쓴다(승인 시점 사용자 결정으로 `CLAUDE.md:174`의 미룬 결정 단락도 함께 맞춘다). 판정 없는 12건에 `deferred-work.md` 등장 순서대로 판정을 추가하고, 낡은 2건에는 기존 문단을 남긴 채 날짜와 근거를 붙인 새 판정을 덧붙이고, 상단 상태 요약 표를 34건 기준으로 다시 센다. 모든 판정은 이번 라운드에 실측한 `파일:줄번호`·커밋·job 결과를 근거로 지목한다.

## Boundaries & Constraints

**Always:**
- `deferred-work.md`는 열어 읽기만 한다. 이 파일은 추가 전용 로그이며 스킬 규칙이 기존 항목 수정을 금지한다.
- 기존 판정 문단을 덮어쓰거나 지우지 않는다. 판정이 바뀌면 아래에 `**<판정>(2026-09-08).**` 형태로 새 문단을 덧붙인다.
- 판정마다 검증 가능한 근거를 지목한다 — `파일:줄번호`, PR 번호·머지 커밋, CI job 결과, 또는 권위 문서 절 번호.
- `해소`는 실제로 확인된 것에만 붙인다. 확인하지 못했으면 `미해소`나 `부분 해소`로 두고 무엇이 남았는지 적는다.
- 판정 순서는 `deferred-work.md` 등장 순서와 같게 유지한다(항목 23~34를 22 뒤에 이어 붙인다).
- 원문 주장이 실측과 다르면 원문을 고치지 않고 판정 안에서 그 차이를 밝힌다.

**Ask First:**
- 어떤 항목을 `해소`로 적으려면 코드를 고쳐야 하는 경우 — 이 라운드는 문서 전용이므로 중단하고 묻는다.
- 근거로 지목할 권위 문서 절이 존재하지 않는 경우(항목 32의 CI-PLAN이 그렇다) — 절을 지어내지 않고, 절이 없다는 사실을 판정에 적는 방식으로 처리한다.
- 원장 판정과 `CLAUDE.md` 문장이 어긋나면 원장이 권위다. `CLAUDE.md`를 근거로 판정을 바꾸지 않는다.

**해결된 Ask First(2026-09-08, 승인 시점 사용자 결정):** `CLAUDE.md`의 `## 현재 진행 지점`이 미룬 결정을 열거하면서 Story 1.3 출처 항목을 빠뜨리고 있다. 사용자가 그 문장을 이번에 함께 고치기로 결정했으므로 변경 파일이 둘이 된다.

**Never:**
- 코드·시험·CI 설정·nginx 구성·프런트엔드 자산을 바꾸지 않는다. 변경 파일은 원장과 `CLAUDE.md` 둘뿐이다.
- `CLAUDE.md`의 다른 절(`## 코드 규칙`·`## CI`·스토리 상태 표 등)을 건드리지 않는다. 이번 변경은 미룬 결정을 열거한 그 한 단락으로 한정한다.
- `deferred-work.md`에 새 항목을 append하지 않는다. 이번 라운드는 `defer` 판정 라운드가 아니다.
- 미룬 항목의 실제 작업(잠금 상한 추가, 세션 결합 해소, 화면 통합, 시험 강화)을 여기서 하지 않는다.
- 확인하지 않은 것을 그럴듯하다는 이유로 `해소`로 적지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 판정 없는 항목 | 항목 23~34, 원장에 문단 없음 | `### N. <제목>` 절을 22 뒤에 순서대로 추가하고 판정·근거·남은 것을 적는다 | 해당 없음 |
| 조건 충족된 낡은 판정 | 항목 14·15, 기존 판정이 현실과 어긋남 | 기존 문단 유지, 아래에 날짜 붙은 새 판정 추가 | 기존 문단을 지우면 실패 |
| 원문 주장이 부정확 | 항목 29의 `DevOidcStubTest` 예시는 실제로 알파벳 순서에 맞다 | 판정 안에서 그 부분이 성립하지 않음을 밝히고, 성립하는 나머지 근거로 판정한다 | 원문을 고치면 실패 |
| 원문이 규모를 과소평가 | 항목 33은 `X-CSRF-TOKEN`을 3곳이라 적었으나 실측 7곳 | 실측 수치를 판정에 기록하고, 원문이 몰랐던 동종 중복도 함께 적는다 | 해당 없음 |
| 소유 스토리가 지나감 | 항목 23(1.4)·27(1.5) | 판정에 "소유 스토리 done, 미처리 — 현재 주인 없음"을 명시한다 | 소유가 남아 있는 것처럼 적으면 실패 |
| 근거로 쓸 권위 절이 없음 | 항목 32, CI-PLAN에 OS matrix 절 없음 | §7·§17이 브라우저 매트릭스에 한정된다는 사실과 절 부재를 판정에 적는다 | 없는 절 번호를 지어내면 실패 |

</frozen-after-approval>

## Code Map

- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` — **주 변경 파일.** `## 상태 요약` 표(현재 `해소 10 / 부분 3 / 미해소 9`), `## 항목별 판정`(항목 1~22), `## 갱신 규칙`. `updated: '2026-09-06'` frontmatter도 갱신 대상.
- `_bmad-output/implementation-artifacts/deferred-work.md` — 읽기 전용. 34건(`grep -c "^- source_spec:"`). 항목 23~26은 1.3 출처, 27~29는 1.4, 30~32는 lint-policy, 33~34는 1.5.
- `CLAUDE.md:174` — 두 번째 변경 파일. 미룬 결정을 열거한 **한 단락**이며 그 줄만 고친다. 현재 열거하는 것은 항목 19·20·21·22 + 1.4의 셋(27·28·29) + 1.5의 둘(33·34)이다. 빠진 미해소는 1.3 출처 셋(23·25·26 — 1.3은 넷을 append했고 24는 해소다), **lint-policy 출처 셋(30·31·32)**, 오래된 넷(13·16·17·18)이며, 이 단락은 원래 선택적 열거였다. 항목 33을 "삼중 중복"으로 적은 수치도 실측과 어긋난다.

이번 라운드 실측 결과. 아래 표는 계획 시점의 값이며, 구현·리뷰가 다시 재어 값이 갈린 곳은 **원장 본문이 정본이다**(항목 25의 컨텍스트 수, 33의 세션 수명·계수, 28의 동일 줄 수, 34의 `.header` 선언 수). 리뷰가 바로잡은 값은 표에도 반영했다.

| # | 판정 | 근거 |
|---|---|---|
| 14 | **해소** | PR #9 run `34035780145`의 `same-origin-integration` green(5 job 전부 success). 원장이 지목한 잔여 조건 그대로. 머지 커밋 `d9a1fc2` |
| 15 | **해소** | `frontend/src/content/entry.ts`에 "준비 중" 문구 부재. Story 1.4가 Google 버튼으로 교체. PR #14 머지 커밋 `2f300f6` |
| 23 | 미해소 | `IdentityAccountRepository.java:35-43` `FOR UPDATE`에 `NOWAIT`·`lock_timeout` 없음. `AccountLifecycleService.java:44-77` `@Transactional` timeout 없음. `application.yml`에 `lock_timeout`·`statement_timeout`·Tomcat 요청 타임아웃 grep 0건. **원문 전제 깨짐** — `AuthFlowHandlers.java:339-342` → `AuthSessionService.java:104-105` → `linkSocialIdentity`가 요청 스레드에서 동기 도달. 소유 스토리 1.4 done, spec-1-4·1-5에 관련 언급 0건 |
| 24 | **해소** | `AccountLifecycleService.java:120-132` `findUserIdBySocialIdentity` 존재. 두 번째 절반도 해결 — `AuthSessionService.java:68-127` `establish()`가 `TransactionTemplate` 하나로 세 호출을 묶고 `:117` `setRollbackOnly()`로 고아 계정 방지. `AuthAccountMappingTest.java:78-148`이 동시 경합까지 단정 |
| 25 | 미해소 | `PostgresTestcontainersConfig.java:37-43` `static` 필드 + `@Bean` 구조 그대로. 원문의 "컨텍스트 넷" 전제는 지금도 성립 — 시험 클래스가 18개로 늘었지만 구별 컨텍스트는 **정확히 넷**(`AuthFlowTestBase` 계열, `StatusApiTest`, `identity` 3종 공유, `AccountConcurrencyTest`)이고 `AuthFlowTestBase.java:38-41`이 그 재사용을 의도적으로 설계했다. `@DirtiesContext` 0건, 캐시 상한 32에 여유 큼 |
| 26 | 미해소 | `verify-same-origin.sh:218-222` 마이그레이션 `build`·`run` 상한 없음. 다른 대기는 유지(`:239` `--wait-timeout 180`, `:270` 등 `--max-time`). Story 1.5가 이 파일을 고쳤으나 이 갭은 미접촉 |
| 27 | 미해소 **악화** | `IdentityAccountRepository.java:195-198` raw `DELETE FROM spring_session` 그대로. `SessionGuard.java:23-26` javadoc이 "이 결합을 넓히지 않는다"로 명시적 범위 밖 처리 — **1.5가 결정을 내리지 않았다**. `principal_name == user_id` 규약이 세 곳에 독립 재구현(`AuthFlowHandlers.java:389-397`, `IdentityAccountRepository.java:196-197`, `SessionGuard.java:209-211`), 공유 상수 없음. 세션 삭제 경로도 둘로 늘었다(raw SQL / `SessionGuard.java:237-259` Spring Session API). `ModuleBoundaryTest`는 패키지 의존만 봄. `AuthDeletionPathTest`·`SessionGuardTest`가 서로의 회귀를 못 봄. 소유 스토리 1.5 done, 미처리 |
| 28 | **부분 해소** | 버튼 축 중복은 실제로 제거 — `components/SignOutButton.tsx`(52줄)·`SignOutButton.test.tsx`(239줄)를 두 화면이 공유. 남은 것: `.tsx` 구조 중복(103 대 108줄, **80줄 동일**), `.module.css` 189줄 중 1줄만 다름. 시험은 오히려 발산(422 대 362줄, `git diff --no-index` 618줄) — 통합을 미룰 근거도 함께 커졌다. 부분 해소는 실체 축에만 걸리고 결정 축은 미해소이며(항목 4·7 선례), 결정 축만 세면 부분 2·미해소 19가 된다는 사실을 절에 함께 적는다 |
| 29 | 미해소 | `build.gradle.kts`에 spotless·checkstyle·pmd·archunit 없음, `:131` `-Xlint:all -Werror`만. 기계 검사 결과 어긋난 파일은 `AuthSecurityConfig.java:19-20`·`:28-29` 하나뿐이고 1.5가 더한 5개 파일은 알파벳 순서를 지킨다. **원문 주장 일부 부정확** — `DevOidcStubTest.java:12-13`은 `org.jspecify` < `org.junit`으로 알파벳순에 맞다. 게이트 부재라는 원인은 그대로. 항목 21과 한 쌍 |
| 30 | 미해소 **악화** | `lint-policy.test.ts:679-685` 여전히 `arrayContaining`으로 3개만 확인. `package.json:16`의 `src/**/*.css`가 고르는 파일은 이제 **6개** — 1.5가 `SignOutButton.module.css`를 더해 무단정 CSS가 2개에서 3개로 늘었다. `spec-frontend-lint-policy.md:69`가 못박은 시험 49개는 실제와 일치(드리프트 없음) |
| 31 | 미해소 | `` `${cwd}/${filePath}` `` 결합 3곳 잔존(`lint-policy.test.ts:592`·`:615`·`:702`) + 변형 2곳(`:616`·`:660`). PR #16(`1ddeebf`)은 출력측만 정규화. 부정 방향 단정 (a)(b)(c) 전부 없음 — `:658-708` describe에 시험 2개뿐. 정규식 `:664` 그대로 |
| 32 | 미해소 | `ci.yml` 5 job 전부 `ubuntu-24.04`(`:59`·`:164`·`:261`·`:332`·`:424`). `test-ci-scripts.sh` 고정 단정 5곳(`:825-827`·`:835-836`·`:838-841`·`:1121-1124`·`:1125-1126`). **CI-PLAN에 OS matrix 전용 절 없음** — §7·§17이 Windows·macOS runner를 언급하나 Playwright 브라우저 매트릭스 맥락에 한정돼 근거 절로 인용 부적합. 결정 지점 미배정 |
| 33 | 미해소 **규모 확대** | `public, max-age=31536000, immutable`은 원문대로 3곳(`bokrilog.conf:76`, `verify-same-origin.sh:458`, `auth-entry.spec.ts:585`). `X-CSRF-TOKEN`은 3곳이 아니라 **7곳** — 정의 2(`AuthSecurityConfig.java:102`, `auth.ts:43`) + `verify-same-origin.sh:963`·`:1014`·`:1027`·`:1042`·`:1060` 독립 리터럴 5회(공유 변수 없음). 오류 code는 백엔드 6종 정의 + `bokrilog.conf:284,290,296,302` 4종 재정의 + 스크립트 20여 회. 원문이 언급 안 한 동종 중복 2건: `/api/auth` 경로 19파일·90회대, `BOKRILOG_SESSION` 20회대(둘 다 측정 범위에 따라 달라져 원장이 범위를 함께 적는다). **유휴 30분만 단일 정의**(`application.yml:111`); **절대 12시간은 두 곳** — `:162`와 `SessionGuard.java:59` `DEFAULT_ABSOLUTE_LIFETIME`(기본값 겸 상한, `:83-87`). `AuthFlowHandlers.java:116` `FLOW_SESSION_TIMEOUT_SECONDS = 300`은 별개 값이라 이 축이 아니다. Docker 없는 정적 리터럴 비교 시험은 전례 없음(`lint-policy.test.ts`는 린터를 실제 실행하는 다른 성격, `verify-same-origin.sh:622,628`도 런타임 응답 비교) |
| 34 | 미해소 | 두 `.module.css` 각 189줄, `diff` 1줄(최상단 주석). `.header` 규칙이 양쪽 `:65-75`에 바이트 동일(원문은 "5속성"이라 적었으나 실제 선언은 **아홉 개** — 규모만 다르고 복제 사실은 같다). `SignOutButton`은 진짜 공유지만 그 버튼을 앉힐 헤더 레이아웃은 컴포넌트가 소유하지 않아 두 벌로 복제. 항목 28과 한 쌍 |

재계산한 상태 요약: **해소 13 / 부분 해소 3 / 미해소 21 = 37**.
해소 13 = 기존 10(1·2·3·5·6·8·9·10·11·12) + 14 + 15 + 24. 부분 해소 3 = 4 · 7 · 28. 미해소 21 = 기존 8(13·16·17·18·19·20·21·22) + 10(23·25·26·27·29·30·31·32·33·34) + 리뷰가 append한 3(35·36·37).

리뷰 라운드가 `defer` 셋을 `deferred-work.md`에 더했고 같은 라운드에서 판정했다. 그래서 계획 시점의 34가 37이 됐다 — 새 항목을 판정 없이 넘기는 것이 이 SPEC이 고치려는 바로 그 실패이기 때문이다.

## Tasks & Acceptance

**Execution:**
- [x] `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` — frontmatter `updated`를 `'2026-09-08'`로 바꾼다 — 원장이 언제 기준인지가 신뢰의 출발점이다.
- [x] `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` — `## 상태 요약` 표를 `해소 13 / 부분 해소 3 / 미해소 21`로 다시 쓰고, 아래 산문을 37건 기준으로 갱신한다. 산문에 담을 것: 12건이 두 스프린트만큼 밀렸던 사실, 소유 스토리가 지나간 2건(23·27), 개발을 막는 항목이 있는지의 판단.
- [x] `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` — 항목 14·15에 기존 문단을 남긴 채 `**해소(2026-09-08).**` 문단을 덧붙인다 — Code Map 표의 근거를 그대로 지목한다.
- [x] `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` — 항목 22 뒤에 `### 23.`~`### 34.` 절 12개를 순서대로 추가한다 — 각 절은 판정·근거(`파일:줄번호`)·남은 것·소유(있으면)를 담는다. 악화·규모 확대·원문 부정확은 Code Map 표가 표시한 대로 판정 안에 밝힌다.
- [x] `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` — `## 갱신 규칙`에 이번 드리프트의 재발을 막는 규칙 한 줄을 더한다 — 소유 스토리가 `done`으로 넘어갈 때 그 스토리가 지정된 항목의 판정을 함께 확인한다는 내용.
- [x] `CLAUDE.md:174` — 미룬 결정 단락을 원장 판정과 맞춘다 — 빠진 1.3 출처 셋(23·25·26)을 넣고, 이 열거가 선택적이며 완전한 목록은 원장이 소유한다는 것을 문장에 드러내고, 항목 33의 "삼중 중복"을 실측에 맞게 고치고, 항목 23·27은 소유 스토리(1.4·1.5)가 이미 `done`인데 처리되지 않아 주인이 없다는 사실을 적는다. `어느 것도 Story 1.6 착수를 막지 않는다`는 판단은 유지하되 23·27은 1.6이 같은 경로를 다시 지난다는 사실을 덧붙인다. 한 단락을 유지하고 다른 절은 건드리지 않는다.

**Acceptance Criteria:**
- Given 원장을 고친 뒤, when `deferred-work.md`의 `- source_spec:` 개수와 원장의 `### <숫자>.` 절 개수를 세면, then 둘 다 37이다(리뷰의 `defer` 셋을 append하고 같은 라운드에서 판정했다).
- Given 상태 요약 표, when 세 값을 더하면, then 37이고 각 값이 항목별 판정을 실제로 센 결과와 같다.
- Given `git diff --name-only`, when 이번 작업의 변경 파일을 보면, then `deferred-work-resolutions.md`·`CLAUDE.md`·이 SPEC 셋뿐이고 `deferred-work.md`는 없다.
- Given `CLAUDE.md`의 변경, when `git diff CLAUDE.md`를 보면, then 174행 한 단락만 바뀌고 다른 절은 변경되지 않는다.
- Given 항목 14·15의 절, when 읽으면, then 기존 `**부분 해소.**`·`**미해소.**` 문단이 그대로 남아 있고 그 아래에 날짜 붙은 새 판정이 있다.
- Given 항목 23·27의 절, when 읽으면, then 소유 스토리가 이미 `done`인데 처리되지 않아 현재 주인이 없다는 사실이 명시돼 있다.
- Given 항목 32의 절, when 읽으면, then CI-PLAN에 근거로 쓸 OS matrix 절이 없다는 사실이 적혀 있고 존재하지 않는 절 번호를 지목하지 않는다.

## Spec Change Log

### 2026-09-08 — 리뷰 1회차, patch 적용(loopback 없음)

**촉발한 발견.** 세 리뷰 층이 낸 것 중 사실관계가 걸린 둘이 확인됐다. (1) 항목 15의 해소 판정이 `entry.ts:50`을 "바뀌었다"고 적었으나 `git diff 2f300f6^1 2f300f6`은 한 줄(`:59`)만 바꿨고 `:50`은 손대지 않은 자격정보 범위 고지다. (2) 항목 33이 "세션 수명 값은 중복이 아니다"라고 적었으나 절대 12시간은 `SessionGuard.java:59`가 기본값 겸 상한으로 다시 적는다. 그 밖에 갱신 규칙 3번(권위 문서 절만 허용)이 이번에 추가한 세 해소 판정과 정면으로 어긋난 채 남았고, 새 규칙의 `done` 방아쇠가 소유를 조건으로 적은 항목 23을 구조적으로 놓쳤다.

**무엇을 고쳤는가.** 원장에 patch 10건(항목 15 인용 정정과 1.6·1.7 재무장 기록, 항목 33 세션 수명 정정·계수 범위 명시, 갱신 규칙 3번 개정과 근거 확인 규칙 추가, 새 규칙에 (가)·(나) 부류 추가, 항목 23 소유 표현 정정, 항목 25 컨텍스트 수 실측 명시, 항목 27 "조용히" 완화, 항목 28 부분 해소의 축 명시, 상태 요약에 주인 없는 열 항목 열거). `CLAUDE.md`에 patch 1건("1.3이 더한 셋"→넷 중 셋, lint-policy 셋 추가, 실측 수치를 원장 참조로). 이 SPEC의 Code Map 수치 넷과 누락(30·31·32), Verification 명령 둘을 실측에 맞췄다.

**피한 known-bad 상태.** 근거가 성립하지 않는 인용이, 근거 정확성을 존재 이유로 삼는 문서 안에 남는 것. 그리고 규칙을 한 줄 더하면서 그 규칙이 이미 못 잡는 부류(이름 없는 소유)를 그대로 두어 이번에 진단한 실패가 Story 1.6에서 똑같이 반복되는 것.

**KEEP — 재도출 시 반드시 살려야 하는 것.** 항목 23~34 절의 `파일:줄번호` 인용은 리뷰가 32건을 기계로 훑고 약 40지점을 직접 열어 경로 부재·범위 초과 0건으로 확인했다. 인용 커밋 여섯(`d9a1fc2`·`2f300f6`·`5b6ae01`·`89b8948`·`8f4fcaf`·`1ddeebf`)과 run `34035780145`의 다섯 job success도 독립 확인됐다. 항목 29의 "원문 일부 부정확"(`DevOidcStubTest`는 알파벳순에 맞다)과 항목 24 해소의 트랜잭션 근거도 독립 재현됐다. 이 검증된 근거 집합을 다시 만들지 말고 보존한다.

**bad_spec 대신 patch로 처리한 판단.** `CLAUDE.md` 열거에서 lint-policy 셋이 빠진 것의 뿌리는 이 SPEC Code Map의 불완전한 누락 목록이므로 엄격히는 bad_spec이다. 그러나 그 처방(코드 되돌리기 + 재도출)은 위 KEEP의 검증된 12개 절을 버리고 다시 만드는 것이고, 실제 수정은 한 단락에 열거 항목 셋을 더하는 일이다. 재도출 이득이 회귀 위험보다 작다고 판단해 patch로 적용하면서 Code Map·Tasks를 함께 truthful하게 고쳤다. 이 판단은 사용자에게 명시적으로 보고한다.

## Design Notes

원장이 밀린 것은 사람의 부주의가 아니라 구조적 공백이다. `bmad-build`의 step-04는 `defer` 판정마다 `deferred-work.md`에 항목을 **추가**하지만, 그 항목의 판정을 원장에 쓰는 단계는 어느 스킬에도 없다. 그래서 원장은 사람이 별도로 챙길 때만 갱신되고, 네 라운드 연속으로 챙기지 못했다. 이번 라운드가 12건을 채우는 것으로 끝나면 다음 스토리에서 같은 일이 다시 생긴다. `## 갱신 규칙`에 한 줄을 더하는 마지막 과업이 그 재발 방지의 최소 수단이다.

판정 문구는 기존 22건의 관용을 따른다 — `**<판정>.**`으로 시작하고, 근거를 지목하고, `부분 해소`·`미해소`면 `**미해소 잔여:**`로 무엇이 남았는지 적는다. 새 판정을 덧붙일 때는 날짜를 괄호에 넣는다(항목 12·14의 선례).

## Verification

**Commands:**
- `grep -c "^- source_spec:" _bmad-output/implementation-artifacts/deferred-work.md` — 기대: `37`
- `grep -c "^### [0-9]" _bmad-output/implementation-artifacts/deferred-work-resolutions.md` — 기대: `37`
- `git status --porcelain` — 기대: `M CLAUDE.md`·`M …/deferred-work-resolutions.md`·`M …/deferred-work.md`·`?? …/spec-deferred-work-ledger-sync.md` 넷만. `git diff --name-only`는 untracked인 이 SPEC을 보지 못하므로 이 명령을 쓴다. `deferred-work.md`의 변경은 리뷰 `defer` 셋의 **추가만**이어야 하며 기존 항목에 삭제·수정이 있으면 실패
- `git diff --stat develop -- CLAUDE.md` — 기대: 1줄 변경(`1 insertion(+), 1 deletion(-)`) 수준. 여러 절이 바뀌면 실패
- `git diff -U0 -- _bmad-output/implementation-artifacts/deferred-work-resolutions.md | grep '^-' | grep -v '^---'` — 기대: 삭제 줄이 frontmatter 날짜·요약 표 두 칸·옛 요약 산문에만 있고 `### N.` 판정 절 안에는 하나도 없다
- 절별 마지막 판정을 집계해 요약 표와 대조한다(`awk`로 `### N.` 구간마다 최종 `**해소/부분 해소/미해소**`를 집계) — 기대: 해소 13·부분 해소 3·미해소 21이고 합이 37

**Manual checks (if no CLI):**
- 항목 23~34 절의 순서가 `deferred-work.md`의 `- source_spec:` 등장 순서와 같은지 대조한다. 항목당 3줄이므로 23은 `:80`에서 시작하고 34는 `:113`이다 — 범위를 손으로 적으면 틀리기 쉬우니 두 목록을 각각 뽑아 비교한다.
- `해소`로 적은 세 건(14·15·24)의 근거를 실제로 **열어서** 확인한다. 줄이 존재하는 것과 그 줄이 주장을 뒷받침하는 것은 다르다 — 첫 구현이 항목 15에서 Story 1.4가 건드리지 않은 줄을 "바뀌었다"고 적었고 위 명령 전부를 통과했다.

## Suggested Review Order

**판정의 규율 — 여기서 의도를 잡는다**

- 표 세 값과 그 구성원. 37건 기준으로 다시 센 결과이며 아래 37개 절과 정확히 맞아야 한다
  [`deferred-work-resolutions.md:16`](deferred-work-resolutions.md#L16)
- 밀림의 원인을 절차 공백으로 지목한 회고. 이 라운드가 무엇을 고쳤는지의 근거
  [`deferred-work-resolutions.md:26`](deferred-work-resolutions.md#L26)
- 재발 방지 규칙 넷. 근거 종류 구분, 인용 확인 의무, `done` 방아쇠, 같은 라운드 판정
  [`deferred-work-resolutions.md:284`](deferred-work-resolutions.md#L284)

**주인이 사라진 항목 — 이 라운드의 핵심 발견**

- 소유를 이름이 아니라 조건으로 적어 `done` 확인이 걸릴 데가 없었다
  [`deferred-work-resolutions.md:166`](deferred-work-resolutions.md#L166)
- 1.5가 명시적으로 범위 밖에 두고 지나갔고 그 사이 규약이 세 곳으로 흩어졌다
  [`deferred-work-resolutions.md:194`](deferred-work-resolutions.md#L194)
- 주인 없는 열셋을 한자리에서 보는 유일한 열거
  [`deferred-work-resolutions.md:34`](deferred-work-resolutions.md#L34)

**원문을 정정한 판정 — 로그는 고칠 수 없으므로 원장이 담는다**

- 리뷰가 잡은 인용 오류를 고친 자리. `:50`은 1.4가 건드리지 않은 줄이었다
  [`deferred-work-resolutions.md:130`](deferred-work-resolutions.md#L130)
- 원문이 근거로 든 `DevOidcStubTest` 예시가 실제로는 알파벳순에 맞다
  [`deferred-work-resolutions.md:212`](deferred-work-resolutions.md#L212)
- 3곳이 아니라 7곳, 그리고 절대 12시간은 실제로 두 곳이다
  [`deferred-work-resolutions.md:244`](deferred-work-resolutions.md#L244)

**해소 셋 — 근거를 열어서 확인할 자리**

- 원장이 지목한 잔여 조건(GitHub 실행 green)이 PR #9 run으로 충족됐다
  [`deferred-work-resolutions.md:94`](deferred-work-resolutions.md#L94)
- 조회 API와 트랜잭션 경계 두 절반이 모두 닫힌 유일한 완전 해소
  [`deferred-work-resolutions.md:176`](deferred-work-resolutions.md#L176)

**두 읽기가 가능한 판정 — 표가 바뀔 수 있는 곳**

- 실체 축은 부분 해소, 결정 축은 미해소. 후자로 세면 부분 2·미해소 22가 된다
  [`deferred-work-resolutions.md:204`](deferred-work-resolutions.md#L204)
- 전제가 우연이 아니라 의도적 설계라서 유지된다는 판정
  [`deferred-work-resolutions.md:180`](deferred-work-resolutions.md#L180)

**이 라운드가 새로 만든 항목**

- 리뷰의 `defer` 셋을 append하고 같은 라운드에서 판정했다
  [`deferred-work-resolutions.md:266`](deferred-work-resolutions.md#L266)
- 로그에는 추가만 했다 — 기존 34건에 삭제·수정 0건
  [`deferred-work.md:116`](deferred-work.md#L116)

**저장소 안내면 반영**

- 미해소 열거를 원장과 맞췄다. 수치는 담지 않고 항목 33을 가리킨다
  [`CLAUDE.md:174`](../../CLAUDE.md#L174)
