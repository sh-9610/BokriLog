# Validation Report — BokriLog

- **DESIGN.md:** `DESIGN.md`
- **EXPERIENCE.md:** `EXPERIENCE.md`
- **Run at:** 2026-08-23T22:29:00+09:00

## Overall verdict

두 spine은 신뢰 상태, 원자적 갱신, 광고 격리, 반응형 상태 계약과 토큰·컴포넌트 정합성이 강하다. 초기 검증의 문서 내부 finding은 모두 해결됐다. Google·네이버·카카오 OIDC 인증, 명시적 다중 로그인 수단 연결, 재인증·복구와 계정 삭제 여정까지 번호형 흐름과 상태 계약으로 닫혔다.

현재 잔여 finding은 Critical 0 / High 0 / Medium 1 / Low 0이다. Medium 1은 유광고 베타 출시 전 실제 광고 공급자·소재로 수행할 키보드·스크린리더 승인 시험이다. 계정 삭제의 최소 법정 기록 대상·기간과 최대 백업 파기 기한은 문서 품질 finding이 아니라 제품·개인정보 책임자가 법률 검토로 확정할 외부 출시 차단 조건이다.

## Category verdicts

- Flow coverage — strong
- Token completeness — strong
- Component coverage — strong
- State coverage — strong
- Visual reference coverage — strong
- Bloat & overspecification — strong
- Inheritance discipline — strong
- Shape fit — strong

## Initial findings by severity (해결 전 기록)

### Critical (0)

없음.

### High (8)

**[Flow coverage] — 계정 삭제 Key Flow 부재** (`EXPERIENCE.md` Information Architecture, Component Patterns, Key Flows)
삭제 완료 후 세션 종료, 삭제·법정 보존 확인, 부분 실패와 재시도가 닫히지 않았다.
Fix: 이름 있는 번호형 계정 삭제 흐름과 완료·실패 계약을 추가한다.

**[Flow coverage] — 인증 방식과 회복 흐름 미확정** (`EXPERIENCE.md` Foundation, 인증 표면)
가입·로그인·세션 회복 방식이 출시 차단 상태이며 Key Flow가 없다.
Fix: 제품·보안 결정을 확정한 뒤 인증 흐름을 추가하고 그 전에는 draft를 유지한다.

**[Accessibility] — 전면광고 키보드 탈출 계약 부족** (`neutral-ad-transition`, Interaction Primitives)
제3자 iframe의 키보드 트랩과 닫기 접근 실패 시 결과로 이동하는 조건이 없다.
Fix: 닫기를 iframe 밖 첫 네이티브 조작으로 두고 접근성 실패·시간초과 시 광고를 건너뛴다.

**[Accessibility] — 전역 focus-visible·가림 방지 규칙 부재** (`DESIGN.md` Colors, `EXPERIENCE.md` Interaction Primitives)
동기화 버튼 외 조작의 포커스 외형과 고정 UI에 가려지지 않는 규칙이 없다.
Fix: 전역 포커스 토큰과 강제 색상·scroll-padding·가림 방지 시험을 추가한다.

**[Financial trust] — 광고 동의·제3자 데이터 경계 미완성** (`EXPERIENCE.md` Foundation, 설정, 광고 전환)
동의 전 SDK 요청 금지, 철회 효력, URL·제목·슬롯명 금지 데이터가 화면 계약으로 닫히지 않았다.
Fix: 광고 동의 상태 계약을 추가하고 공급자 미확정 시 광고 없이 출시한다.

**[Financial trust] — 미확정 오프라인 캐시와 개인정보 경계** (`EXPERIENCE.md` Responsive & Platform)
소스에 없는 캐시된 금융값 표시가 공유 기기·로그아웃·삭제 후 노출 위험을 만든다.
Fix: MVP 캐시 표시를 제거하거나 별도 출시 차단 정책으로 돌린다.

**[Financial trust] — 공식 지표 재현 근거 부족** (`trust-status-panel`, `history-row`)
스냅샷 ID, 공통 기준시점, 계산 버전, 조정 이력 버전의 연결이 없다.
Fix: `계산 근거 보기`에서 입력 계보와 불투명 ID를 제공한다.

**[Financial trust] — 계정 삭제 범위·완료 상태 미확정** (`confirmation-dialog`, 설정)
즉시·지연 삭제, 법정 보존 범위·기간, 부분 실패와 완료 후 재접근 차단이 없다.
Fix: 보존 정책 확정 전 삭제 UX를 최종화하지 않고 전용 상태·흐름을 추가한다.

### Medium (12)

**[Flow coverage] — UX 관련 NFR 추적 부족.** NFR-10·14·15·16을 Requirement Coverage에 연결하고 나머지는 아키텍처·운영으로 위임한다.

**[State coverage] — 변경 작업의 오프라인 규칙 부족.** 실행 전 차단, 입력 보존, 자동 재시도 금지, 온라인 복귀 후 사용자 재시도를 전역 원칙으로 둔다.

**[Accessibility] — 실제 색상 조합 대비 누락.** muted·subtle·destructive·기능 경계 조합과 광고 경계 토큰을 검증한다.

**[Accessibility] — 동적 공지 소유권 중복.** 화면당 단일 live region과 `aria-busy`를 사용해 원자적 갱신을 한 번만 알린다.

**[Accessibility] — 비동기 폼 포커스·단계 의미 부족.** 호출 버튼 포커스 유지, `aria-current="step"`, 오류 요약 이동을 규정한다.

**[Accessibility] — 비밀값 초기화와 입력 보존 충돌.** 실패 시 비밀만 지우고 나머지 입력을 보존하며 재입력 이유를 알린다.

**[Accessibility] — 조정 이력 표↔카드 의미 관계 부족.** caption, `th scope`, `aria-expanded`, 모바일 그룹명을 규정한다.

**[Accessibility] — 긴 식별자·고정 탐색 확대 동작 부족.** 400% 확대, 안전한 줄바꿈, 전체값 접근, safe-area와 scroll-padding을 추가한다.

**[Financial trust] — 계산 성격의 지표별 전파표 부재.** 정상·추정·보류·0·음수·격리 조건별 표시값과 성격을 표로 닫는다.

**[Financial trust] — 계획 비교와 투자성과 구분 부족.** `복리 계획 비교` 제목과 비성과·비추천 설명을 결과 화면에 상시 배치한다.

**[Financial trust] — 격리 유형별 표시 우선순위 불명확.** 연결·동기화·계산·광고 정지별 현재가치·파생값·이력·조작 처리를 표로 만든다.

**[Financial trust] — 재계산 버전 비교 계약 부족.** 원인 사건, 영향 시점·지표, 이전→새 값, 가격 근거를 읽기 전용으로 제공한다.

### Low (5)

**[Inheritance discipline] — EXPERIENCE의 토큰 직접 참조 부족.** 최소 목표 크기·포커스·여백을 `{path.to.token}`으로 연결한다.

**[Inheritance discipline] — 상태 용어 축약.** DESIGN의 `확인`·`검토`를 `확인됨`·`검토 필요`로 통일한다.

**[Accessibility] — 지표와 신뢰 축의 프로그램적 연결 부족.** 각 지표에 성격·원인 요약 또는 `aria-describedby` 참조를 둔다.

**[Accessibility] — 축소 모션에서 회전 스피너 허용.** 정적 진행 표지와 텍스트로 바꾼다.

**[Financial trust] — 큰 금액·비율 넘침 규칙 부족.** 부호·단위를 보존하고 축약 시 전체값을 시각·보조기술로 제공한다.

## Reviewer files

- `review-rubric.md`
- `review-accessibility.md`
- `review-financial-trust.md`

## Post-fix verification

- Rubric: Google·네이버·카카오 인증 결정 후 8개 범주 모두 `strong`; 계정 `user_id`와 외부 `(issuer, subject)` 매핑 및 제공자 공식 버튼 가이드 우선순위까지 재검증 완료.
- Accessibility: `strong`; 9건 해결, 광고 공급자의 실제 키보드·스크린리더 승인 시험 1건은 통합 게이트로 남음.
- Financial trust & privacy: `pass`; FT-01~FT-09 전부 해결.
- Residual findings: Critical 0 / High 0 / Medium 1 / Low 0.

남은 Medium은 유광고 베타 출시 전 실제 제3자 광고 공급자·소재로 수행할 보조기술 승인 시험이다. 공급자·동의·데이터 흐름 미확정 또는 시험 실패 시 베타 출시를 차단한다. 계정 삭제의 최소 법정 기록 대상·기간과 최대 백업 파기 기한은 제품·개인정보 책임자가 법률 검토로 확정할 외부 출시 차단 조건이다.
