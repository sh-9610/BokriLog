---
title: '첫 CI 기반 — 단일 ci-required job'
type: 'chore'
created: '2026-09-04'
status: 'done'
review_loop_iteration: 0
baseline_commit: '02acc80c6e62dda5460543c53fe1be1dc9ef03fc'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 저장소에 workflow가 없어 CI-PLAN이 정의한 필수 게이트가 존재하지 않는다. `playwright.config.ts`가 `CI` 환경변수로 동작을 바꾸지만 그 변수를 설정하는 주체가 없고, Story 1.1의 시험은 사람이 직접 입력할 때만 실행된다.

**Approach:** `.github/workflows/ci.yml`에 이름이 고정된 단일 `ci-required` job을 만들어 CI-PLAN §6의 실행 순서를 수행한다. 문서-only 변경은 같은 job 안에서 가벼운 경로를 탄다. GitHub-hosted Ubuntu에서 green을 확인한 뒤 `develop`·`main`에 브랜치 보호를 건다.

## Boundaries & Constraints

**Always:**
- job 이름은 `ci-required` 고정. 브랜치 보호가 이 이름을 참조한다.
- 외부 action은 full commit SHA로 고정한다. 러너는 `ubuntu-24.04`로 고정한다.
- 토큰 기본 권한은 `contents: read`. PR에 secret을 전달하지 않고 `pull_request_target`을 쓰지 않는다.
- workflow 전체에 top-level `paths` 필터를 두지 않는다. status가 생성되지 않는 분기를 막는다.
- PR은 `github.sha`(이벤트가 고정한 merge commit)를 checkout하고 `fetch-depth: 0`으로 받는다.
- 변경 분류는 fail-closed다. 규칙에 없는 경로는 전체 frontend 검사를 실행한다.
- Playwright는 재시도하지 않는다. 실패·timeout·취소 시 report·trace·screenshot만 7일 보관한다.
- npm 다운로드 캐시만 쓴다. `node_modules`와 Playwright 브라우저는 캐시하지 않는다.

**Ask First:**
- gitleaks가 기존 이력에서 결과를 내면 보고하고 결정을 받는다. 규칙을 임의로 완화하지 않는다.
- 실행시간이 CI-PLAN §4 예산(중앙값 3분·p95 5분)을 넘으면 필수 검사를 줄이지 말고 보고한다.

**Never:**
- 존재하지 않는 backend·container·Terraform 게이트를 미리 구현하지 않는다.
- `bmad-contract` 검사는 이번 범위 밖이다. 러너에 `uv` 설치가 필요해 별도 작업으로 분리한다.
- lint·stylelint·format 게이트를 여기서 도입하지 않는다. CI-PLAN §8이 Story 1.2 전 별도 정책 PR로 정했다.
- CI에서 의존 버전을 갱신하지 않는다.

## I/O & Edge-Case Matrix

| 시나리오 | 입력 / 상태 | 기대 동작 | 오류 처리 |
|---|---|---|---|
| 제품 변경 PR | `frontend/**` 변경 | secret scan → install → typecheck → build → Vitest → Chromium E2E 순서로 실행하고 `ci-required` success | 단계 실패 시 job 실패, 진단 artifact 업로드 |
| 문서-only PR | `_bmad-output/**`·최상위 `*.md`만 변경 | secret scan만 실행하고 success | secret 발견 시 실패 |
| workflow 변경 PR | `.github/workflows/**` 변경 | 문서-only로 분류하지 않고 전체 검사 실행 | 동일 |
| 분류 불가 | 규칙에 없는 경로 | 전체 frontend 검사 실행 | fail-closed |
| merge ref 불일치 | merge commit의 부모가 `base_sha`·`head_sha`와 다름 | job 실패 | 검증 실패로 즉시 중단 |
| E2E 실패 | 시험 하나 실패 | trace·screenshot·report 업로드 후 실패 | 재시도 없음 |
| 단계 초과 | Playwright 3분 초과 | `globalTimeout`이 job timeout보다 먼저 끊는다 | 진단 업로드 뒤 실패 |

</frozen-after-approval>

## Code Map

- `.github/workflows/ci.yml` -- 신규. 이 저장소의 유일한 workflow. 판정 논리는 담지 않고 `.github/scripts/`를 호출한다
- `.github/scripts/verify-merge-commit.sh` -- 신규. PR merge commit의 부모가 이벤트의 `base_sha`·`head_sha`와 같은 순서인지 검증. 입력 `GITHUB_SHA`·`BASE_SHA`·`HEAD_SHA`, 실패는 `::error::`와 종료 코드 1
- `.github/scripts/classify-changes.sh` -- 신규. 문서-only 판정. 입력 `GITHUB_EVENT_NAME`·`GITHUB_SHA`·`EVENT_BEFORE`, 출력 `GITHUB_OUTPUT`의 `docs-only`(`true`|`false`)와 `reason`(`docs-only`|`product-change`|`no-comparison-base`|`empty-diff`). 같은 값을 `GITHUB_STEP_SUMMARY`에도 남긴다. 변경 목록은 `git -c core.quotePath=false diff --no-renames -z --name-only`로 읽는다. 종료 코드는 항상 0이며 git 호출 실패도 `no-comparison-base`로 흡수한다
- `.github/scripts/test-ci-scripts.sh` -- 신규. 위 두 스크립트와 `ci.yml`의 계약 시험. 임시 git fixture를 만들어 스크립트를 실제로 실행하고 `docs-only`·`reason`·종료 코드·오류 메시지를 단정하며, `ci.yml`은 job id·`name`·`runs-on`·top-level `paths` 부재·고정 action SHA 4개·문서-only 게이트 수를 단정한다. CI-PLAN §5가 요구하는 "분류 규칙과 허용 사유 enum의 시험"이다. 종료 코드 0(통과)·1(단정 실패)·2(fixture 준비 실패)
- `frontend/package.json:16-17` -- `test:e2e`는 로컬용(빌드 포함), `test:e2e:run`은 CI용(빌드 생략). CI는 후자를 쓴다
- `frontend/playwright.config.ts:26-42` -- `retries: 0`, `globalTimeout: 180_000`, `trace: 'retain-on-failure'`. `workers`는 `CI`가 1로 만든다
- `frontend/playwright.config.test.ts` -- 신규. 위 값들과 `CI` 해석(`forbidOnly`·`workers`)을 고정하는 Vitest 시험. 저장소 루트에 둔다
- `frontend/vite.config.ts:23` -- `test.include`가 `src/**/*.test.{ts,tsx}`에 더해 `playwright.config.test.ts` 한 파일을 명시적으로 받는다
- `frontend/tsconfig.node.json` -- `playwright.config.test.ts`를 include에 추가. `process.env`를 읽는 설정 파일과 그 시험을 Node 타입이 있는 프로그램에 함께 둔다
- `frontend/.nvmrc` -- `24`. `setup-node`의 `node-version-file`
- `frontend/.npmrc` -- `engine-strict=true`. Node major가 다르면 설치가 즉시 실패한다
- `frontend/package-lock.json` -- npm 캐시 키(`cache-dependency-path`)
- `frontend/playwright-report/`·`frontend/test-results/` -- 진단 artifact 경로. 둘 다 `.gitignore` 대상
- `CI-PLAN.md` §5 §6 §11 §13 §16 -- 이벤트 정책, 실행 순서, 보안, 보호 규칙, 완료 조건의 정본
- `scripts/check-bmad-env.sh` -- 이번 범위 밖. `uv`와 관리 Python 다운로드가 필요하다

**고정한 action SHA** (2026-09-04 조회)
- `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1` (v7.0.1)
- `actions/setup-node@820762786026740c76f36085b0efc47a31fe5020` (v7.0.0)
- `actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` (v7.0.1)
- `gitleaks/gitleaks-action@e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e` (v3.0.0)

## Tasks & Acceptance

**Execution:** 아래 `[원격]` 표시 작업은 구현 단계에서 실행하지 않는다. 리뷰를 통과한 뒤 사람이 승인한 시점에만 수행한다.
- [x] `.github/workflows/ci.yml` -- `pull_request`(develop·main), `push`(develop·main), `workflow_dispatch` trigger와 단일 `ci-required` job 작성 -- CI-PLAN §5·§6의 계약을 실행 가능한 형태로 옮긴다
- [x] `.github/workflows/ci.yml` -- concurrency group에 workflow·target branch·PR 번호를 포함 -- 같은 PR의 이전 실행만 취소하고 다른 PR을 건드리지 않는다
- [x] `.github/scripts/verify-merge-commit.sh`·`classify-changes.sh` -- workflow의 인라인 스크립트를 동작 변경 없이 추출 -- 판정 논리를 시험 가능한 파일로 옮긴다
- [x] `.github/scripts/test-ci-scripts.sh` -- git fixture 기반 계약 시험 작성 -- CI-PLAN §5의 "분류 규칙과 허용 사유 enum은 시험 대상" 요구를 충족한다
- [x] `.github/workflows/ci.yml` -- `CI 스크립트 계약 시험` 단계를 분류 단계 앞에 조건 없이 배치 -- 분류기를 분류기로 게이트하지 않는다
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- `bmad-contract` 검사 분리를 항목으로 추가 -- CI-PLAN §14의 계약이 미구현임을 기록에 남긴다
- [x] `frontend/playwright.config.ts` -- `screenshot: 'only-on-failure'` 추가 -- 기본값 `off`라 실패해도 screenshot이 없었다. 매트릭스가 요구하는 진단물 세 가지를 실제로 만든다
- [x] `.github/scripts/classify-changes.sh` -- 변경 목록을 `core.quotePath=false` + `-z`로 읽고 NUL로 분리 -- 한글 파일명 문서가 C-quote 한 줄로 들어와 `product-change`로 오분류되던 것을 막는다. 공백·개행이 든 경로도 같은 이유로 안전해진다
- [x] `.github/scripts/classify-changes.sh` -- `git rev-parse ^1`과 `git diff` 실패를 붙잡아 `no-comparison-base`로 emit -- "종료 코드는 항상 0"이라는 헤더·SPEC의 단언을 실제로 지킨다. 128로 죽으면 `GITHUB_OUTPUT`이 빈 채로 남아 뒤 단계의 `!= 'true'` 게이트가 우연히 통과한다
- [x] `.github/scripts/verify-merge-commit.sh` -- `git rev-list` 실패를 붙잡아 종료 1 + `::error::`로 바꿈 -- 해석 불가능한 `GITHUB_SHA`에서 주석 없이 128로 죽던 경로를 계약에 맞춘다
- [x] `.github/workflows/ci.yml` -- PR 이벤트에서 `git show <base_sha>:.github/scripts/classify-changes.sh`로 target base의 분류기를 꺼내 실행 -- CI-PLAN §5의 "PR이 수정한 classifier가 자기 검사를 축소할 수 없다"를 구현한다. base에 파일이 없으면 head 것을 쓰되 경고를 남긴다
- [x] `.github/scripts/test-ci-scripts.sh` -- `ci.yml` 계약 단정 추가 -- job id·`name`·`runs-on`·top-level `paths` 부재·고정 action SHA 4개·문서-only 게이트 수를 고정한다. 이 단정이 없으면 job 이름을 바꾸는 것만으로 필수 검사가 조용히 사라진다
- [x] `.github/scripts/test-ci-scripts.sh` -- 비ASCII 경로, 공백 포함 경로, octopus merge, 비-merge·해석 불가 SHA 사례 추가 -- 위 스크립트 수정이 실제로 동작하는지 고정한다
- [x] `.github/scripts/test-ci-scripts.sh` -- fixture 준비 실패를 즉시 중단(종료 코드 2)으로 바꿈 -- `mktemp -d`·`git init`·`commit`·`git mv` 실패를 버리면 빈 fixture 위에서 단정이 돌아 "통과"가 무의미해진다
- [x] `.github/scripts/classify-changes.sh` -- 판정 결과를 `GITHUB_STEP_SUMMARY`에 기록 -- PR 화면에서 frontend 검사가 왜 건너뛰어졌는지 단계 로그를 펼치지 않고 보이게 한다
- [x] `.github/workflows/ci.yml` -- artifact 업로드를 `if-no-files-found: warn`으로 변경 -- `ignore`는 경로가 어긋나도 조용히 성공해 실패한 실행에 진단물이 없다는 사실을 감춘다
- [x] `frontend/playwright.config.test.ts` -- `retries: 0`·trace·screenshot·`globalTimeout`·`CI` 해석을 고정하는 Vitest 시험 추가 -- CI-PLAN §6이 "한 묶음"으로 못박은 값들이 설정 한 줄로 뒤집히는 것을 막는다
- [x] [원격] `CI-PLAN.md` -- Ubuntu 실측값으로 §3 기준선 갱신, 단계 timeout 여유 확인 -- §17 재보정의 출발점을 hosted 러너 기준으로 바꾸고, job 8분·Vitest 2분·Playwright 4분과 `globalTimeout` 3분이 실측 대비 얼마나 남는지 기록한다 -- 2026-09-04 첫 6회(전체 경로 5회) 기준으로 갱신. job 55–97초(중앙값 66초), Vitest 3–4초, E2E 8–14초. 가장 큰 단계는 Chromium 설치 20–53초. §16의 10회 조건은 미달로 표시했다
- [x] [원격] gitleaks 전수 기준선 스캔 1회 -- `workflow_dispatch`로 전체 이력을 한 번 훑고 결과를 보고한다. 필수 경로는 PR 커밋 범위만 검사하므로 기존 이력의 결과는 이 1회 스캔이 아니면 드러나지 않는다 -- 2026-09-04 실행(run 33881744463). 16 commit을 훑어 탐지 1건. `_bmad/_config/files-manifest.csv:48`의 SHA-256 파일 해시가 파일명 `key-screens.md`의 "key" 때문에 `generic-api-key`에 걸린 오탐. 규칙은 두고 `.gitleaksignore`에 그 fingerprint 한 줄만 등록했다
- [x] [원격] `develop`·`main` 브랜치 보호 -- `ci-required` 필수, 직접 push·force push·삭제 차단 -- 실행이 green임을 확인한 뒤에만 건다 -- 2026-09-04 develop push 실행 2건 green 확인 뒤 ruleset `protect-integration-branches`(active, bypass 없음)로 적용. PR 필수(승인 0, 허용 병합 방식 `merge`만), `ci-required` 필수(GitHub Actions 출처만 인정, 최신 base 통합 검증), 직접 push·force push·삭제 차단. classic branch protection이 아니라 ruleset이므로 `branches/<name>/protection`은 404를 반환한다
- [x] [원격] 열려 있는 토픽 브랜치 rebase -- CI-PLAN §13. `pull_request`의 workflow 정의는 PR head에서 읽으므로 workflow보다 먼저 분기한 브랜치는 `ci-required`를 만들지 않고 필수 검사가 영구 대기로 남는다 -- 2026-09-04 보호 적용 시점에 열린 토픽 브랜치가 없어 대상 없음. `chore/sprint-status-1-1-done`(PR #3)은 workflow 이전 분기였으나 보호 전에 병합됐다
- [x] [원격] `deferred-work-resolutions.md` -- "프런트엔드 시험을 자동 실행하는 CI 워크플로" 항목의 해소를 기록 -- CI-PLAN §16은 "검사가 실제로 성공한 뒤에만 해당 deferred CI 항목을 해결된 것으로 기록한다"고 요구하므로 hosted 러너 green 이후에만 쓴다 -- 2026-09-04 항목 11을 해소로 기록. 같은 시점에 원장에 없던 항목 12–16도 미해소로 추가해 `deferred-work.md`와 순서를 맞췼다

**Acceptance Criteria:**
- Given 열려 있는 PR, when `ci-required`가 실패하면, then 병합 버튼이 차단된다.
- Given 문서만 바꾼 PR, when workflow가 돌면, then `ci-required` status가 생성되고 성공한다.
- Given workflow 파일을 바꾼 PR, when 분류가 실행되면, then 문서-only가 아니라 전체 frontend 검사가 돈다.
- Given E2E가 실패한 실행, when job이 끝나면, then trace와 screenshot이 artifact로 남고 재시도 기록이 없다.
- Given 깨끗한 Node 24 환경, when 전체 경로가 돌면, then install·typecheck·build·Vitest·Chromium E2E가 모두 성공한다.
- Given 분류 규칙이나 허용 사유 enum을 바꾼 변경, when `ci-required`가 돌면, then `CI 스크립트 계약 시험` 단계가 그 변경을 잡아 job이 실패한다.
- Given `github.sha`가 이벤트의 `base_sha`·`head_sha`를 그 순서로 부모에 두지 않는 PR, when merge commit 검증이 돌면, then `::error::` 주석과 함께 job이 종료 1로 즉시 중단되고 분류·frontend 단계는 실행되지 않는다.
- Given committed secret이 있는 PR 커밋 범위, when secret 검사가 돌면, then 값이 `--redact`로 가려진 채 job이 실패하고 병합이 차단된다.
- Given 비교 기준이 없는 실행(수동 진단, 브랜치 신규 생성, force push로 사라진 이전 commit, 해석 불가능한 SHA), when 분류가 돌면, then 종료 코드 0으로 `docs-only=false`·`reason=no-comparison-base`를 내고 전체 frontend 검사가 실행된다.

## Design Notes

**gitleaks 도구 선택.** GitHub 기본 secret scanning은 private 저장소에서 GHAS가 필요해 쓸 수 없다. `gitleaks-action` v3는 조직 계정만 라이선스 키를 요구하며 이 저장소는 개인 계정(`sh-9610`, type `User`)이라 키가 필요 없다. 저장소를 조직으로 옮기면 키가 필요해지므로 그때 재검토한다.

**검사 범위는 PR 커밋 범위다.** 전체 이력을 차단 게이트로 두면 기존 커밋의 결과 하나가 모든 PR을 막는다. 전수 스캔은 1회 수동 기준선으로 분리한다.

**판정 논리를 workflow 밖으로 뺐다.** YAML 안의 인라인 `run` 블록은 저장소에서 실행할 수 없어 시험 대상이 되지 못한다. CI-PLAN §5가 분류 규칙과 허용 사유 enum을 시험 대상으로 지정하므로 `verify-merge-commit.sh`와 `classify-changes.sh`로 동작 변경 없이 추출하고, `test-ci-scripts.sh`가 임시 git fixture로 두 스크립트를 실제 실행해 계약을 고정한다. workflow는 `bash <경로>`로 호출한다. 실행 비트가 체크아웃에서 유실돼도 동작이 같기 때문이다.

**계약 시험은 조건 없이 실행한다.** `.github/**` 변경에서만 실행하려면 변경 파일을 다시 분류해야 하는데, 그러면 검증 대상인 분류기가 자기 시험의 실행 여부를 결정하게 된다. 시험은 러너에서 몇 초로 끝나므로 무조건 실행이 더 싸고 우회 경로가 없다. 위치는 분류 단계 바로 앞이다.

**분류에 `fetch-depth: 0`을 쓴다.** `pull-requests: read`를 더해 API로 목록을 받는 방법도 있지만, 전체 이력은 토큰 권한을 늘리지 않고 push·PR 양쪽에서 같게 동작하며 gitleaks도 이력을 요구한다. 이 저장소는 작아 비용이 낮다.

**분류기는 PR에서 target base의 정의로 실행한다.** 체크아웃된 head의 `classify-changes.sh`를 그대로 돌리면 분류기가 자기 검사 범위를 결정한다. `frontend/*` 한 줄을 허용 목록에 더한 PR은 그 PR 자신의 frontend 검사를 통째로 건너뛰고 병합될 수 있고, 병합 뒤에야 규칙이 바뀐 사실이 드러난다. CI-PLAN §5는 이 경로를 명시적으로 막는다("PR이 수정한 classifier가 자기 검사를 축소할 수 없다"). 그래서 `pull_request`에서는 `git show <base_sha>:.github/scripts/classify-changes.sh`로 base에 이미 승인돼 있는 정의를 꺼내 `RUNNER_TEMP`에 두고 그것을 실행한다. `fetch-depth: 0`이라 base commit이 이미 로컬에 있어 추가 fetch나 권한이 필요 없다(CI-PLAN §6의 "얕은 checkout만으로는 불가능하다"). 분류기 자체를 바꾸는 PR은 base 규칙으로 분류되는데, `.github/scripts/**`는 `*/*`에 걸려 항상 `product-change`이므로 전체 검사를 돈다. base에 파일이 없는 경우는 이 workflow를 최초 도입하는 PR뿐이며, 그때는 head의 정의를 쓰되 `::warning::`으로 어떤 정의를 썼는지 남긴다. `push`·`workflow_dispatch`에는 비교할 base가 없어 체크아웃된 정의를 쓴다. 이 선택 자체는 `test-ci-scripts.sh`가 `ci.yml`에서 `git show <base>:...` 호출의 존재를 단정해 고정한다.

**허용 목록은 접두어 하나와 최상위 `*.md` 둘뿐이다.** 규칙은 `_bmad-output/*`, 그다음 `*/*`(디렉터리가 있는 그 밖의 모든 경로), 그다음 최상위 `*.md` 순서로 평가한다. 이 형태에는 두 가지 의도된 결과가 있고 둘 다 fail-closed 쪽으로 기운다. 첫째, `_bmad-output/*`는 그 아래 **모든 파일 형식**을 문서로 통과시킨다. `.md`뿐 아니라 `.png`·`.yaml`·`.ts`도 마찬가지다. 산출물 디렉터리는 사람이 읽는 기획 문서만 담기로 한 경계이고 제품 코드는 여기 들어오지 않으므로 지금은 안전하지만, 이 디렉터리에 실행 가능한 것을 두기 시작하면 규칙을 좁혀야 한다. 둘째, `*/*`가 `_bmad-output/` 밖의 모든 중첩 경로를 잡으므로 미래에 `docs/`나 `_bmad-output/` 밖의 중첩 문서 디렉터리를 만들면 순수 문서 변경도 전체 frontend 검사를 돈다. 시간을 낭비할 뿐 잘못된 통과를 만들지 않으므로 그때 가서 허용 목록에 접두어를 추가한다. 접두어를 미리 늘리지 않는 이유는 존재하지 않는 경로에 대한 허용 규칙이 나중에 그 이름으로 만들어질 실제 제품 디렉터리를 조용히 통과시킬 수 있기 때문이다.

**`playwright.config.ts`는 Vitest로 고정했다.** CI-PLAN §6이 "한 묶음"으로 못박은 `retries: 0`과 `trace: 'retain-on-failure'`, 그리고 `screenshot: 'only-on-failure'`·`globalTimeout`·`CI` 해석은 설정 파일 한 줄로 뒤집힌다. 정적으로 문자열을 찾는 대신 설정을 실제로 평가해 단정하기로 했다. `CI` 값에 따라 `forbidOnly`·`workers`가 갈리는 분기는 모듈을 다시 평가해야만 검증되므로 정적 검사로는 절반만 확인된다. 시험 파일은 대상 옆인 저장소 루트 `frontend/playwright.config.test.ts`에 두고 `vite.config.ts`의 `test.include`가 이 한 파일을 더 받게 했다. `src/` 안으로 옮기면 `process.env`를 읽는 설정 파일이 앱 프로그램(`tsconfig.json`)에 끌려 들어와 앱 코드에까지 Node 타입을 열어야 한다. jsdom 환경에서도 `@playwright/test` import는 깨지지 않았다(대안이던 `@vitest-environment node`는 공용 `src/test/setup.ts`가 요구하는 jsdom 전역과 충돌해 쓰지 않았다). 따라서 `test-ci-scripts.sh`에서 파일을 정적으로 단정하는 우회는 필요하지 않았다.

**`ci.yml` 단정은 YAML 파서 없이 한다.** `test-ci-scripts.sh`는 러너와 개발 기기 양쪽에 항상 있는 도구(bash·grep·sed·awk)만 쓰고 들여쓰기를 지키는 정확한 줄 비교로 `ci.yml`을 읽는다. PyYAML은 GitHub hosted 러너의 PATH 위 python3(도구 캐시 Python)에 있다고 보장되지 않고, 개발 기기(Windows)의 `python3`는 Microsoft Store 스텁이라 실행되지 않는다. 계약 시험이 도구 유무에 따라 조용히 건너뛰어지면 시험이 없는 것과 같으므로 의존을 없애는 쪽을 택했다. `ci.yml`은 이 저장소가 소유하는 단일 파일이고 형식이 고정돼 있어 이 방식으로 충분하다.

## Verification

**Commands:**
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 56개 통과, 종료 코드 0
- `npm --prefix frontend ci --prefer-offline --no-audit --fund=false` -- expected: 성공
- `npm --prefix frontend run typecheck` -- expected: 성공
- `npm --prefix frontend run build` -- expected: `frontend/dist/` 생성
- `npm --prefix frontend run test` -- expected: 24개 통과(진입 화면 9 + `playwright.config.ts` 계약 15)
- `CI=1 npm --prefix frontend run test:e2e:run` -- expected: 13개 통과, 재시도 0
- `gh run list --branch chore/ci-foundation` -- expected: `ci-required` 성공
- `gh api repos/sh-9610/BokriLog/rules/branches/develop --jq '.[] | select(.type=="required_status_checks") | .parameters.required_status_checks[].context'` -- expected: `ci-required`. 보호는 ruleset이므로 classic 경로 `branches/develop/protection`은 404다
- `gh api repos/sh-9610/BokriLog/rules/branches/develop --jq '[.[].type] | sort'` -- expected: `deletion`, `non_fast_forward`, `pull_request`, `required_status_checks`

**Manual checks:**
- 문서-only 변경만 담은 PR에서 `ci-required` status가 생성되고 frontend 단계가 건너뛰어졌는지 확인한다.
- 일부러 실패시킨 실행에서 병합 버튼이 실제로 차단되는지 확인한다.

## Suggested Review Order

**필수 게이트의 형태**

- 이 이름이 브랜치 보호의 required status가 된다. 여기서 시작한다
  [`ci.yml:48`](../../.github/workflows/ci.yml#L48)
- top-level `paths` 필터를 두지 않는 이유. status 누락 분기를 막는다
  [`ci.yml:20`](../../.github/workflows/ci.yml#L20)
- 같은 PR의 이전 실행만 취소하고 다른 PR·push는 건드리지 않는다
  [`ci.yml:35`](../../.github/workflows/ci.yml#L35)

**판정 논리**

- 허용 목록 방식. 목록 밖 경로가 하나라도 있으면 전체 검사로 간다
  [`classify-changes.sh:112`](../../.github/scripts/classify-changes.sh#L112)
- `-z` + `core.quotePath=false`. 한글·공백 경로가 조용히 오분류되던 지점
  [`classify-changes.sh:97`](../../.github/scripts/classify-changes.sh#L97)
- 판정 불가는 실패가 아니라 `no-comparison-base`다. 종료 0 계약을 지킨다
  [`classify-changes.sh:76`](../../.github/scripts/classify-changes.sh#L76)
- 해석 불가 SHA도 종료 1 + `::error::`. `set -e`의 128 사망을 막는다
  [`verify-merge-commit.sh:28`](../../.github/scripts/verify-merge-commit.sh#L28)

**게이트가 스스로 약해지지 않게 하는 장치**

- PR은 head가 아니라 target base의 승인된 분류기를 실행한다
  [`ci.yml:124`](../../.github/workflows/ci.yml#L124)
- 계약 시험을 분류보다 앞에 조건 없이 둔다. 분류기를 분류기로 게이트하지 않는다
  [`ci.yml:103`](../../.github/workflows/ci.yml#L103)
- job 이름·러너·고정 SHA·게이트 개수를 단정한다. 이름 변경이 검사를 지우지 못한다
  [`test-ci-scripts.sh:10`](../../.github/scripts/test-ci-scripts.sh#L10)

**진단물 보존**

- retries 0과 `retain-on-failure`는 한 묶음이다. 따로 바꾸면 trace가 사라진다
  [`playwright.config.ts:28`](../../frontend/playwright.config.ts#L28)
- 기본값 `off`라 실패해도 screenshot이 없었다
  [`playwright.config.ts:42`](../../frontend/playwright.config.ts#L42)
- `ignore`면 경로가 어긋나도 빈 artifact로 조용히 성공한다
  [`ci.yml:194`](../../.github/workflows/ci.yml#L194)

**주변**

- 설정값 표류를 Vitest로 고정한다
  [`playwright.config.test.ts:1`](../../frontend/playwright.config.test.ts#L1)
- 준비 실패(2)와 단정 실패(1)를 구분해 보고한다
  [`test-ci-scripts.sh:62`](../../.github/scripts/test-ci-scripts.sh#L62)
- 로컬에서 이 시험을 돌리는 방법
  [`DEVELOPMENT.md:124`](../../DEVELOPMENT.md#L124)

