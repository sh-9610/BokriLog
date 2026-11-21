# 경로와 화면

- **브라우저 경계.** 외부 폰트·CDN·분석 SDK는 두지 않는다. 인증된 대시보드의 수동 동기화 광고만 서버 광고 설정을 읽고 GPT 제한 광고 슬롯을 준비할 수 있다. 동일 출처 계측은 `/api/engagement/telemetry`를 이용한다. 앱은 `localStorage`·`sessionStorage`·IndexedDB에 쓰지 않으며, 인증 토큰과 금융 원본을 브라우저 영속 저장소에 두지 않는다. 진입 화면은 문서·번들 외 요청 0건을 시험으로 고정한다.
- **디자인 토큰.** `frontend/src/styles/tokens.css`가 정본이며 `DESIGN.md` frontmatter의 평면 이름을 그대로 옮긴 것이다. 색·타이포그래피·모서리·간격 리터럴을 컴포넌트에 직접 쓰지 않는다. stylelint가 강제하고, 인라인 예외는 `-- 이유`가 붙은 disable 주석만 허용한다. 없는 토큰을 지어내지 않는다.
- **화면 카피.** `frontend/src/content/`가 소유하고 화면과 시험이 같은 문자열을 참조한다. 모든 핵심 화면에 `복리 계획 비교`와 "투자성과·계좌 수익률·투자 추천이 아닙니다" 고지를 인접 배치한다. 실시간·정확 보장, 수익 축하, 손실 공포, 스트릭·배지 표현은 금지다.

**경로·화면 경계**
- 화면 경로는 여섯(`/onboarding`·`/dashboard`·`/settings`·`/plan-conditions`·`/operator`·`/ad-transition`)이고 `App.tsx` 경로 맵과 Nginx 허용 목록이 같은 집합이어야 한다. 그중 복귀 목적지는 넷(`/plan-conditions`·`/ad-transition` 제외, `/operator`는 운영자에게만 — 접기 규칙은 helper 하나 `OperatorAccess.foldDestination`)이고 서버 `ALLOWED_DESTINATIONS`, 세션 목적지 맵, 복귀 목적지 집합이 같은 집합이어야 한다 — 합쳐 **다섯 곳**이다. 타입 검사가 잡는 것은 하나뿐이다. 근거·조정 이력·종료·완료는 새 경로가 아니라 대시보드 패널 안에서 연다(2.9~2.14).
- 폼은 `fetch`로 보내고 `form-action 'none'`을 유지한다. 광고 공급자 origin은 정확히 `/dashboard` 경로에 허용하고 G-4에서 실측한다. 이전 `/ad-transition` 주소는 대시보드로 돌아가는 호환 경로다.
- 화면은 대시보드 응답 하나를 한 단위로 교체한다. 늦은 응답은 버리고, 버전 역행·충돌은 교체하지 않고, 실패한 다시 불러오기는 마지막 정상 결과를 유지하며, 401·403은 값을 메모리에서 지운다. 같은 계획의 종단(중도 종료·완료) 뒤 활성 묶음과 새 계획 시작 뒤 이전 계획 응답의 역행은 거절한다(2.8·2.13~2.15).
- 초점은 사용자가 누른 조작의 결과에만 옮긴다. 자동 트리거의 완료는 status 문장 한 번이다. 화면당 live region은 하나이고 대시보드 status는 `aria-atomic="false"`다(2.9·2.11).

## 프런트엔드 실행

`frontend/`는 React 19.2.x + Vite 8.2.x + TypeScript strict 정적 SPA다. 백엔드·DB·제공자 자격정보 없이 단독으로 빌드·실행·시험이 통과한다. Node는 `frontend/.nvmrc`의 24다. Node 25에서는 Vitest가 내장 `localStorage` 때문에 실패하므로 버전 관리자로 24를 맞춘다. 명령은 저장소 루트에서 `--prefix frontend`로 실행한다.

```bash
npm --prefix frontend ci
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run lint:css
npm --prefix frontend run format:check
npm --prefix frontend run build
npm --prefix frontend run test
npm --prefix frontend run test:e2e
```

`test:e2e`는 `vite build` 뒤 Playwright가 산출물을 `preview`로 띄운다. 빌드가 이미 끝난 CI에서는 `test:e2e:run`을 쓴다. 처음 한 번은 `npm --prefix frontend exec -- playwright install chromium`이 필요하다. `PLAYWRIGHT_BASE_URL`을 주면 자체 `preview`를 띄우지 않고 그 origin을 검증하며, `e2e/same-origin-csp.spec.ts`와 `e2e/auth-entry.spec.ts`의 성공 왕복 시험은 이때만 실행된다. 자동 정리는 `npm --prefix frontend run lint -- --fix`, `npm --prefix frontend run lint:css -- --fix`, `npm --prefix frontend run format`이다. 상세는 `DEVELOPMENT.md`의 `## 프런트엔드`.

커밋과 함께 성립해야 하는 효과(초점 이동·`replaceLocation`·타이머·리스너, 그 효과가 읽는 latest-ref)는 `useLayoutEffect`, 요청 시작과 `inert` 배경으로 돌려주는 대화상자의 초점 복귀는 근거 주석을 단 passive `useEffect`이며, 부하에서만 드러나는 시험 결함은 `bash frontend/scripts/stress-test.sh`(CPU 수 × 2 부하, 12회)로 재현·확인한다 — 상세는 `DEVELOPMENT.md`의 `### 구조와 규칙`.

프런트엔드 공통 부품은 다섯이다 — 운영 command 상태 기계 `session/useOperatorCommand.ts`(단계·멱등 키·이중 제출 방지·응답 없는 확정의 uncertain·문구 불일치의 무장 유지·목록 콜백·취소; 운영자 화면 하나의 훅 여섯(`useOperatorDeletion` 포함)이 command 설정만 넘긴다, 목록 읽기의 순번·중단은 `session/useSequencedRead.ts` — 현재 포인터를 참조하는 단건 조회의 엔진인 `useReferencedRequest`와 달리 참조·401/403/404 매핑 없이 "마지막 목록 읽기만 반영"하는 운영자 목록·상세·집계용이다), 확인 대화상자 메커니즘 `components/useConfirmationDialog.ts`(기본 포커스·가둠·`Esc`·스크롤 잠금·호출점 복귀; 네 대화상자가 위임하고 CSS는 `ConfirmationDialog.module.css` 하나), 현재 포인터를 참조하는 조회(근거·이력류)의 엔진 `session/useReferencedRequest.ts`(sequence·중단·키·401/403/404 매핑; 계산 근거·조정 이력이 위임하고 동시성 정책 `replace`/`ignore`는 훅 인자 — `useDashboard`·`useDashboardSync`·`NextPlanBaselinePanel`·`PlanHistoryPanel`·`PlanConditionPanel`의 읽기 경로를 이 엔진으로 옮기는 것은 별도 결정이다), 서버 시각 판정 `session/instant.ts`의 `isInstant`(엄격판 하나), 동일 출처 POST·JSON 판독 `session/http.ts`(`postJson`·본문 없는 204 묶음 `postNoContent`·`readJson`·`objectOf`·`JsonObject`; 요청 init 규칙 하나 — `POST`·`same-origin`·`no-store`·`Accept`·CSRF 헤더, 본문이 있을 때만 `Content-Type`, 받았을 때만 `Idempotency-Key` — 이고 시한·상태 매핑·결과 모양은 호출자가 가진다. 예외는 `keepalive` 계측 `telemetry/beacon.ts` 하나이며 `http.test.ts`가 운영 소스를 훑어 `method: 'POST'`·`new Request`·판독 helper 정의와 인라인 판독·CSRF 헤더 리터럴과 그 import 위치의 사본을 막는다) — 새 운영 command·확인 대화상자·참조 조회·시각 판정·상태 변경 요청은 이 다섯을 쓰고 사본을 만들지 않는다.
