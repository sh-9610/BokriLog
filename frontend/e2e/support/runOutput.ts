import type { FullConfig } from '@playwright/test'

/*
 * 로컬 e2e 실행의 출력 디렉터리 규칙과 보존 상한(Epic 4 회고 AI-6, `deferred-work.md` 155).
 *
 * 로컬 실행은 `test-results/<UTC 시각>`에 trace·screenshot을 남긴다(`playwright.config.ts`). 이 파일의 기본 export는 로컬
 * 실행의 globalSetup이고, 실행이 시작될 때 앞선 실행 디렉터리 가운데 최근 것만 남기고 지운다 — 실패한 실행의 trace는 다음
 * 실행 뒤에도 남고, 디렉터리가 끝없이 쌓이지는 않는다. CI는 이 globalSetup을 쓰지 않는다(러너가 run마다 깨끗하다).
 *
 * 시험 파일이 아니다. Playwright는 `*.spec.ts`만 시험으로 모은다.
 */
import { readdirSync, rmSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'

/** 실패 진단물의 뿌리. CI가 이 경로를 그대로 artifact로 올린다(`ci.yml`). */
export const RESULTS_ROOT = 'test-results'

/** 로컬 실행 하나를 가르는 표지를 담는 환경 변수. 주 프로세스가 정하고 worker가 물려받는다. */
export const E2E_RUN_ENV = 'BOKRILOG_E2E_RUN'

/** 남기는 실행 디렉터리 수(이번 실행 포함). */
export const KEPT_RUNS = 5

/** 실행 표지 모양 — UTC 시각을 파일 이름에 쓸 수 있게 바꾼 것(`2026-10-17T03-04-05-678Z`). 사전순이 곧 시간순이다. */
const RUN_STAMP = /^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d{3}Z$/

export function runStamp(now: Date): string {
  return now.toISOString().replace(/[:.]/g, '-')
}

export function isRunStamp(value: string): boolean {
  return RUN_STAMP.test(value)
}

/** 오류의 `code`(`ENOENT`·`EBUSY`…). 없으면 빈 문자열. */
function errorCode(error: unknown): string {
  return error instanceof Error && 'code' in error && typeof error.code === 'string'
    ? error.code
    : ''
}

/**
 * 뿌리 아래 실행 디렉터리 가운데 이번 실행을 빼고 새것부터 `KEPT_RUNS - 1`개만 남기고 지운다. 표지 모양이 아닌 항목(손으로
 * 둔 파일, `.last-run.json`, 이 규칙 이전의 시험별 디렉터리)은 건드리지 않는다. 지운 디렉터리 이름을 돌려준다.
 *
 * 정리는 시험 실행의 전제가 아니다. 뿌리가 없으면(`ENOENT`, 첫 실행) 조용히 끝나고, 그 밖의 읽기 실패나 지우기 실패(Windows의
 * `EBUSY`·`EPERM` — 다른 프로세스가 trace를 열고 있다)는 경고 한 줄만 남기고 실행을 막지 않는다.
 */
export function pruneRuns(root: string, current: string): string[] {
  let entries: string[]
  try {
    entries = readdirSync(root, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && isRunStamp(entry.name) && entry.name !== current)
      .map((entry) => entry.name)
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') {
      console.warn(
        `[e2e] 실행 디렉터리 정리를 건너뜀: ${root}을 읽지 못함(${errorCode(error) || String(error)})`,
      )
    }
    return []
  }
  const removed: string[] = []
  for (const name of entries
    .sort()
    .reverse()
    .slice(Math.max(KEPT_RUNS - 1, 0))) {
    try {
      rmSync(join(root, name), { recursive: true, force: true })
      removed.push(name)
    } catch (error) {
      console.warn(
        `[e2e] 지난 실행 디렉터리를 지우지 못함: ${name}(${errorCode(error) || String(error)})`,
      )
    }
  }
  return removed
}

export default function globalSetup(config: FullConfig): void {
  const outputDir = config.projects[0]?.outputDir
  if (outputDir === undefined) {
    return
  }
  const current = basename(outputDir)
  if (!isRunStamp(current)) {
    return
  }
  pruneRuns(dirname(outputDir), current)
}
