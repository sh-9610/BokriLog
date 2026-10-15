---
title: 'Story 2.17 뒤 낡은 연결 제한 javadoc 정정'
type: 'chore'
created: '2026-10-15'
status: 'done'
route: 'one-shot'
---

# Story 2.17 뒤 낡은 연결 제한 javadoc 정정

## Intent

**Problem:** Story 2.17(PR #100)의 리뷰 결정으로 이전 연결에 건 `PROVIDER_CONNECTION` 제한이 같은 Bybit 계정의 뒤 연결에도 적용되게 바뀌었는데, `plan`의 javadoc 두 곳은 "이전 연결에 건 제한은 그 연결에만 남는다·이 계획을 막지 않는다"는 옛 규칙을 적고 있었다(판정 라운드 중 발견). `PlanSyncGuard` 클래스 주석의 영어 문장도 2.17 뒤 문단과 어긋났다.

**Approach:** 주석만 실제 동작(`operations.RestrictionConnectionScope` — 같은 사용자·같은 provider account의 연결 전부, 다른 계정 제외)에 맞게 한국어로 고치고, 범위 규칙의 권위는 그 port로 가리킨다. 동작 변경 없음.

## Suggested Review Order

- 계획 대상의 `connectionId` 정의 — 제한 범위는 port가 정한다고 가리킴
  [`PlanConditionApi.java:66`](../../backend/src/main/java/com/bokrilog/plan/PlanConditionApi.java#L66)

- 지금 연결을 쓰는 이유와 같은 계정 제한의 효과·다른 계정 제외
  [`PlanConditionService.java:272`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java#L272)

- 2.17 뒤 문단과 어긋나던 영어 문장의 한국어 정정
  [`PlanSyncGuard.java:25`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncGuard.java#L25)
