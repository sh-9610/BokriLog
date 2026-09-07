---
title: 'lint 정책 시험의 경로 구분자 가정 제거'
type: 'bugfix'
created: '2026-09-07'
status: 'done'
route: 'one-shot'
---

# lint 정책 시험의 경로 구분자 가정 제거

## Intent

**Problem:** `frontend/lint-policy.test.ts`의 `lint:css` 범위 시험이 `path.relative`의 결과를 POSIX 형태 정본 경로와 직접 비교해, Windows에서 `src\styles\global.css`가 나와 그 플랫폼에서만 실패했다. CI는 `ubuntu-24.04`만 돌아 green이므로 결함이 로컬에만 남고, `npm run test`가 영구히 빨간 상태가 되어 진짜 실패가 그 안에 섞여도 눈에 띄지 않는다.

**Approach:** 절대경로를 `cwd` 기준 POSIX 상대경로로 옮기는 이름 있는 헬퍼를 두고 범위 시험이 그것을 쓰게 한다. 치환은 `path.sep`으로만 하며(POSIX에서 `\`는 합법적인 파일명 문자다) 정본 경로는 사람이 읽는 상대경로 형태로 남긴다. 같은 가정이 다시 들어오지 않도록 시험 파일 머리 주석과 `DEVELOPMENT.md`의 두 기기 개발 절에 규칙으로 적는다.

## Suggested Review Order

**경로 정규화**

- 헬퍼 하나가 규칙을 소유한다. `sep`으로만 자르는 이유가 docblock에 있다
  [`lint-policy.test.ts:55`](../../frontend/lint-policy.test.ts#L55)

- 호출 지점. `?? ''` 폴백을 없애 source 없는 결과가 조용히 통과하지 않는다
  [`lint-policy.test.ts:670`](../../frontend/lint-policy.test.ts#L670)

- 비교값이 POSIX 형태라는 계약. Windows에서만 회귀를 잡는다는 한계도 함께 적었다
  [`lint-policy.test.ts:689`](../../frontend/lint-policy.test.ts#L689)

**재발 방지**

- 저장소 규칙. CI가 Linux만 돌아 이 종류를 못 본다는 사실을 함께 적었다
  [`DEVELOPMENT.md:43`](../../DEVELOPMENT.md#L43)

- 시험 파일이 스스로 그 전제를 갖는다
  [`lint-policy.test.ts:25`](../../frontend/lint-policy.test.ts#L25)
