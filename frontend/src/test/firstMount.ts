/**
 * 큰 화면의 첫 마운트를 기다리는 대기의 시한(ms) — Epic 4 회고 AI-6, `deferred-work.md` 158.
 *
 * 158이 적은 네 파일만 쓴다. 그중 진짜 lazy chunk 대기는 `App.test.tsx`의 운영자 경로 하나다 — `App`이 운영자 화면을
 * `React.lazy`로 받고, Vitest에서는 그 동적 import가 시험 도중에 모듈 그래프를 변환·적재한다. 나머지 셋(`OperatorScreen`·
 * `OperatorNotice`·`SettingsConnectionRevoke`)은 화면을 정적으로 import하지만, 같은 실행에서 그 큰 화면의 첫 마운트(세션 확인과
 * 첫 조회 응답까지)를 기다리는 대기가 함께 넘었다. 여러 파일이 CPU를 나눠 쓰는 Windows 로컬 전체 실행에서 Testing Library 기본
 * 대기 1초를 넘긴 것이다(1030~1054ms).
 *
 * 그래서 `render` 바로 뒤의 첫 대기(`findBy*`, 첫 대기가 `waitFor`인 시험은 그 `waitFor`)에만 이 시한을 이름으로 준다. 그 뒤의
 * 대기는 이미 마운트된 화면의 것이라 기본 대기 그대로다. 전역 `asyncUtilTimeout`은 올리지 않는다(`DEVELOPMENT.md` "시험의
 * 대기") — 모든 대기를 늘리면 늦게 오는 효과를 가리는 시험까지 함께 무뎌진다. 값은 관측된 초과의 세 배쯤이고 Vitest 시험
 * 시한(5초) 안이다.
 */
export const FIRST_MOUNT_TIMEOUT_MS = 3_000
