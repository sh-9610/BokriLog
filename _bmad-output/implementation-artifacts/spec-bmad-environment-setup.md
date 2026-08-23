---
title: 'BMAD 실행 환경 정리'
type: 'chore'
created: '2026-08-23'
status: 'done'
route: 'one-shot'
---

# BMAD 실행 환경 정리

## Intent

**Problem:** macOS 기본 Python 3.9로는 Python 3.11 이상을 요구하는 BMAD 도구를 안정적으로 실행할 수 없고, 로컬 캐시와 운영체제 메타데이터가 작업 트리를 어지럽혔다.

**Approach:** `uv` 관리 Python을 명시적으로 사용하고 실제 BMAD 핵심 경로를 한 번에 점검하는 스크립트, 실행 원칙 문서, Git 제외 규칙을 추가했다.

## Suggested Review Order

**실행 검증**

- 단일 진입점에서 필수 도구와 파일을 먼저 검증한다.
  [`check-bmad-env.sh:13`](../../scripts/check-bmad-env.sh#L13)

- 캐시 경로를 정규화해 출력 형식 차이로 인한 오탐을 막는다.
  [`check-bmad-env.sh:32`](../../scripts/check-bmad-env.sh#L32)

- 관리 Python과 중앙 설정값을 실제 실행·파싱으로 검증한다.
  [`check-bmad-env.sh:42`](../../scripts/check-bmad-env.sh#L42)

- 스킬 렌더링과 메모리 로그 기록까지 실제 핵심 경로를 시험한다.
  [`check-bmad-env.sh:60`](../../scripts/check-bmad-env.sh#L60)

**운영 원칙**

- 시스템 Python과 애플리케이션 런타임 결정을 분리한 실행 규칙을 설명한다.
  [`DEVELOPMENT.md:22`](../../DEVELOPMENT.md#L22)

**저장소 위생과 후속 추적**

- 캐시·가상환경·바이트코드·Finder 메타데이터를 Git에서 제외한다.
  [`.gitignore:1`](../../.gitignore#L1)

- 환경 변경 밖에서 발견된 제품 쟁점을 후속 작업으로 보존한다.
  [`deferred-work.md:1`](deferred-work.md#L1)
