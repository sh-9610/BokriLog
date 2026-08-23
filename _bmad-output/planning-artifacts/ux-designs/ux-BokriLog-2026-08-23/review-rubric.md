# Spine Pair Review — BokriLog

## Overall verdict

두 spine은 신뢰 상태, 원자적 갱신, 광고 격리, 모바일·데스크톱 상태 계약을 일관되게 전달하며 토큰과 컴포넌트의 기계적 정합성도 높다. 다만 현재 `draft`를 downstream 구현 계약으로 넘기기에는 인증 방식이 명시적으로 차단 상태이고, 계정 삭제의 고위험 여정이 닫히지 않았으며, UX 관련 NFR 추적과 전역 오프라인 상태에 공백이 있어 전체적으로는 **adequate**에 가깝지만 흐름 범주는 **thin**이다.

## 1. Flow coverage — thin

소스의 UJ-1~UJ-3과 FR-1~FR-17을 추출해 Information Architecture, Requirement Coverage, Key Flows와 대조했다. UJ-1~UJ-3은 원문 제목을 그대로 사용하고 이름 있는 주인공, 번호 단계, 명시적 climax, 실패 경로를 모두 갖췄으며 FR-1~FR-17도 기존 여정 또는 Flow 4~6으로 연결된다.

### Findings

- **[high]** 계정 삭제는 IA와 `confirmation-dialog`에 포함되지만, 실제 삭제 성공 후 세션 종료·민감 화면 차단·삭제/법정 보존 확인·실패 복구를 잇는 번호형 Key Flow가 없다. 현재 Flow 6의 실패 문장에 “별도 더 강한 확인”으로만 언급되어 NFR-10의 고위험 사용자 경험을 downstream 소비자가 완성할 수 없다 (`EXPERIENCE.md` §Information Architecture, line 42; §Component Patterns, line 74; §State Patterns, line 121; §Key Flows, lines 243–250; source `prd.md` line 403). *Fix:* 이름 있는 주인공, 번호 단계, climax, 실패 경로를 갖춘 계정 삭제 흐름을 추가하고 삭제 완료 시 이동·세션 상태·보존 고지·재시도 규칙을 명시한다.
- **[high]** 가입·인증·세션 회복 방식이 “UX·아키텍처 확정 및 베타 출시” 차단 항목으로 남아 있는데 인증 표면은 IA와 상태 한 행만 있고 Key Flow가 없다 (`EXPERIENCE.md` §Foundation, lines 19–26; §Information Architecture, line 32; §State Patterns, line 111; source `prd.md` lines 501–504). *Fix:* 제품·보안 결정을 먼저 확정한 뒤 가입/로그인/회복/세션 만료를 하나 이상의 이름 있는 Key Flow로 닫는다. 결정 전에는 최종 downstream 계약으로 승격하지 않는다.
- **[medium]** Requirement Coverage는 FR-1~FR-17만 추적하고 NFR-1~NFR-21을 전혀 식별하지 않는다. NFR 대부분은 아키텍처 소유지만 NFR-10(계정 삭제), NFR-14(반응형 성능), NFR-15(키보드·비색상 상태), NFR-16(광고 닫기)은 UX 계약에 직접 반영되어도 ID 연결이 없어 source-extract 시 누락으로 오인될 수 있다 (`EXPERIENCE.md` §Requirement Coverage, lines 157–177; source `prd.md` lines 391–420). *Fix:* UX에 직접 영향을 주는 NFR만 기존 표면·상태·흐름에 연결하고, 나머지는 아키텍처/운영 계약으로 명시적으로 위임한다.

## 2. Token completeness — strong

DESIGN.md frontmatter의 colors, typography, rounded, spacing, components 전체와 두 문서의 모든 `{path.to.token}` 참조를 확인했다. 모든 색 토큰은 hex이며 모든 참조가 정의된 경로로 해석되고, 명시한 핵심 대비 수치도 계산값과 일치한다.

### Findings

- 없음.

## 3. Component coverage — strong

명명된 9개 컴포넌트(`app-shell`, `trust-status-panel`, `metric-card`, `sync-control`, `secure-input-form`, `confirmation-dialog`, `history-row`, `neutral-ad-transition`, `service-notice`, `operator-control`)를 추출했다. 모두 DESIGN.md Components에 시각 규칙이 있고 EXPERIENCE.md Component Patterns에 동일한 이름의 실질적 행동 규칙이 있다.

### Findings

- 없음.

## 4. State coverage — adequate

IA의 13개 표면을 표면별 상태 매트릭스와 대조했다. 각 표면은 cold/empty, 진행·focus, 성공, error/degraded/restricted를 상황에 맞게 다루며, 권한 거부·세션 만료·광고 실패·격리·계산 불가 같은 제품 특화 상태도 포함한다.

### Findings

- **[medium]** 오프라인 계약은 대시보드의 마지막 정상값 캐시만 정의하고, 연결 검증·계획 생성/종료·연결 해제·계정 삭제·운영자 제어 같은 네트워크 변경 작업이 오프라인일 때의 차단, 입력 보존, 재시도 여부는 표면별로 닫히지 않았다 (`EXPERIENCE.md` §State Patterns, lines 109–123; §Responsive & Platform, line 153). *Fix:* 전역 오프라인 원칙을 추가하고 각 변경 표면에 “실행 전 차단/실패 후 입력 보존/자동 재시도 금지/온라인 복귀 후 사용자 재시도” 중 적용 규칙을 명시한다.

## 5. Visual reference coverage — strong

`imports/`, `mockups/`, `wireframes/`를 열거했으며 현재 파일이 하나도 없다. 따라서 orphan 또는 불명확한 inline reference도 없다.

### Findings

- 없음.

## 6. Bloat & overspecification — strong

픽셀값은 DESIGN.md의 토큰·레이아웃 규칙에 집중되어 있고 EXPERIENCE.md는 IA, 행동, 상태, 접근성, 여정 중심이다. 계산·보안 관련 문장은 대체로 화면 행동을 결정하는 불변조건만 남겼으며 Requirement Coverage와 상태 매트릭스도 downstream 검색성을 높인다.

### Findings

- 없음.

## 7. Inheritance discipline — adequate

두 spine의 `sources` 경로는 모두 실제 PRD와 addendum으로 해석된다. UJ 제목은 원문과 일치하고, 컴포넌트 이름은 frontmatter·시각 표·행동 표 전반에서 일치하며, DESIGN.md의 모든 토큰 참조가 해석된다.

### Findings

- **[low]** EXPERIENCE.md는 시각 소유권을 DESIGN.md에 위임하지만 `{path.to.token}` 형식의 직접 cross-reference가 하나도 없다. 특히 최소 조작 크기와 반응형 여백처럼 두 계약이 만나는 규칙은 downstream이 이름으로 연결할 수 없다 (`EXPERIENCE.md` §Foundation, line 15; §Accessibility Floor, lines 136–143; §Responsive & Platform, lines 145–155). *Fix:* 필요한 시각 의존값을 DESIGN.md 토큰으로 만들고 EXPERIENCE.md가 해당 토큰을 `{...}`로 참조하게 한다.
- **[low]** DESIGN.md의 상태색 설명이 소스의 확정 용어 `확인됨`·`검토 필요`를 `확인`·`검토`로 줄여 쓴다 (`DESIGN.md` §Colors, line 148; `EXPERIENCE.md` §Voice and Tone, line 61; source `prd.md` lines 294–296). *Fix:* 시각 계약에서도 사용자 표시 용어를 `확인됨`·`검토 필요`·`사용 불가`로 통일한다.

## 8. Shape fit — strong

DESIGN.md는 Brand & Style → Colors → Typography → Layout & Spacing → Elevation & Depth → Shapes → Components → Do's and Don'ts의 고정 순서를 지킨다. EXPERIENCE.md는 Foundation, Information Architecture, Voice and Tone, Component Patterns, State Patterns, Interaction Primitives, Accessibility Floor, Key Flows를 모두 포함하며 반응형 웹과 명시적 거부 패턴에 맞춰 Responsive & Platform 및 Inspiration & Anti-patterns도 갖췄다. Requirement Coverage는 17개 FR의 downstream 추적성을 위해 존재 이유가 분명하다.

### Findings

- 없음.

## Mechanical notes

- `DESIGN.md`와 `EXPERIENCE.md` frontmatter의 PRD·addendum 상대 경로는 모두 해석된다.
- UJ-1~UJ-3 제목은 소스 원문과 일치한다. 세 여정과 Flow 4~6은 모두 이름 있는 주인공, 번호 단계, climax, failure를 갖췄다.
- FR-1~FR-17은 Requirement Coverage에 모두 한 번 이상 나타난다. NFR-1~NFR-21은 ID 단위 추적이 없다.
- 9개 명명 컴포넌트는 DESIGN.md Components와 EXPERIENCE.md Component Patterns 양쪽에 동일한 kebab-case 이름으로 존재한다.
- DESIGN.md의 색상은 모두 hex이고, 문서에 기재된 핵심 대비 수치는 계산 결과와 일치한다. 발견된 `{...}` 토큰 참조는 모두 정의되어 있다.
- `imports/`, `mockups/`, `wireframes/`에는 파일이 없다. 시각 참조 orphan도 없다.
- Mermaid 블록이 없어 Mermaid 구문 검증 대상은 없다.
- 두 spine의 상태는 `draft`이며, Foundation에 남은 phase blocker와 `[ASSUMPTION]` 태그가 있으므로 현재 상태는 적절하다.

## Post-fix verification — 2026-08-23

수정된 `DESIGN.md`와 `EXPERIENCE.md`를 YAML, 토큰 경로, 컴포넌트 양쪽 계약, IA 표면별 상태, 요구사항 추적, Key Flow 구조 기준으로 다시 검사했다. 최초 6개 finding 중 5개는 해결됐고 인증 결정·여정만 높은 영향의 미해결 항목으로 남았다. 새 토큰을 도입한 뒤 남은 44px 리터럴 1건은 낮은 영향의 정리 항목이다.

### Resolved findings

- **계정 삭제 Key Flow — resolved.** 독립 IA 표면, 상태 매트릭스 행, Flow 7이 추가됐고 삭제 대상·법률상 보존 고지·재입력 확인·진행 중 접근 차단·세션 종료·뒤로 가기 차단·부분 실패를 모두 닫는다 (`EXPERIENCE.md` lines 45, 165, 312–320).
- **UX 관련 NFR 추적 — resolved.** NFR-2·10·13~16이 표면과 흐름에 연결되고, 나머지 NFR의 소유 계약도 명시됐다 (`EXPERIENCE.md` lines 226–237).
- **전역 오프라인 상태 — resolved.** 지속 캐시 금지, 네트워크 변경 사전 차단, 입력 보존, 자동 재시도 금지, 온라인 복귀 후 사용자 재실행이 전역 계약으로 추가됐고 대시보드 신규 진입 상태도 정리됐다 (`EXPERIENCE.md` lines 116–118, 157, 200).
- **EXPERIENCE.md 토큰 cross-reference — resolved.** 포커스 색·두께·간격과 최소 조작 크기가 `{colors.focus-ring}`, `{spacing.focus-width}`, `{spacing.focus-offset}`, `{spacing.target-min}`으로 직접 연결된다 (`DESIGN.md` lines 87–89, 188; `EXPERIENCE.md` lines 172, 182).
- **신뢰 상태 용어 — resolved.** DESIGN.md도 소스와 동일한 `확인됨`·`검토 필요`·`사용 불가`를 사용한다 (`DESIGN.md` line 151).

### Remaining findings

- **[high] Flow coverage:** 가입·인증·세션 회복 방식은 여전히 제품·보안 결정 대기 상태이고, 자체 Key Flow가 없어 FR-1 사용자 경계의 진입·회복 경험을 구현 계약으로 확정할 수 없다 (`EXPERIENCE.md` lines 19–27, 33, 153; source `prd.md` lines 501–504). *Fix:* 제품·보안 결정을 확정한 뒤 가입/로그인/회복/세션 만료를 이름 있는 주인공, 번호 단계, climax, failure를 가진 흐름으로 추가한다.
- **[low] Bloat & overspecification:** `{spacing.target-min}`을 도입했지만 `sync-control` 시각 사양은 동일 값을 여전히 `44px`로 직접 적어 토큰과 리터럴이 이중 소유한다 (`DESIGN.md` lines 87, 180). *Fix:* `44px 이상`을 `{spacing.target-min} 이상`으로 바꾼다.

### Revised category verdicts

- Flow coverage — **adequate**
- Token completeness — **strong**
- Component coverage — **strong**
- State coverage — **strong**
- Visual reference coverage — **strong**
- Bloat & overspecification — **adequate**
- Inheritance discipline — **strong**
- Shape fit — **strong**

### Revised finding counts

- Critical: **0**
- High: **1**
- Medium: **0**
- Low: **1**

### Post-fix mechanical notes

- DESIGN.md YAML은 파싱되며 colors 21개, typography 역할 7개, rounded 4개, spacing 13개, components 10개를 포함한다.
- 두 문서에서 발견된 `{...}` 참조 39개는 모두 DESIGN.md frontmatter 경로로 해석된다.
- 명명 컴포넌트 10개는 DESIGN.md Components와 EXPERIENCE.md Component Patterns 양쪽에 동일한 이름으로 존재한다.
- IA의 15개 표면은 상태 매트릭스에 모두 존재한다.
- UJ-1~UJ-3과 Flow 4~8은 모두 이름 있는 주인공, 번호 단계, climax, failure를 갖춘다. 인증 표면만 독립 Key Flow가 없다.
- 추가된 대비 수치(`ink-muted`/raised 5.05:1, `ink-primary`/subtle 14.53:1, destructive/on-destructive 7.19:1)는 계산값과 일치한다.
- `imports/`, `mockups/`, `wireframes/`는 여전히 비어 있고 Mermaid 블록은 없다.

## Post-auth-decision verification — 2026-08-23

인증 관련 잔여 High finding을 Google·Naver·Kakao OIDC 결정, `social-sign-in` 양쪽 계약, 계정 식별·연결·재인증·복구, Flow 9, FR-1 추적에 한정해 다시 검사했다. 인증 제공자와 여정 부재라는 기존 High는 해결됐지만, 다중 제공자 연결을 불가능하게 만드는 내부 계정 키 정의 충돌이 새 High로 남아 인증 계약 전체는 아직 구현 인계 준비가 되지 않았다.

### Verified as sufficient

- **3-provider protocol decision.** Google·Naver·Kakao 모두 OIDC Discovery와 Authorization Code를 공식 지원하고, Naver·Kakao 공식 문서도 PKCE S256 지원을 명시한다. 문서의 provider set과 기본 흐름 선택은 실행 가능한 결정이다 ([Google OIDC reference](https://developers.google.com/identity/openid-connect/reference), [Naver Login OIDC guide](https://developers.naver.com/docs/login/devguide/devguide.md), [Kakao Login REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api)).
- **Authentication surface and states.** Foundation에서 비밀번호·이메일 링크·SMS를 제외하고 세 제공자로 범위를 닫았으며, 인증 IA와 상태 매트릭스가 첫 가입, 재방문, 세션 만료, 민감 작업 재인증, 취소·거부·콜백 오류·제공자 장애를 다룬다 (`EXPERIENCE.md` lines 19, 34, 168).
- **Explicit linking, reauthentication, recovery.** 로그인된 설정에서 현재 수단 재인증과 새 제공자 인증이 모두 성공해야 연결되며, 마지막 수단 해제 금지, 이메일 기반 자동 병합 금지, 최근 인증이 필요한 민감 작업, 연결된 대체 제공자 또는 제공자 공식 복구 경로가 명시됐다 (`EXPERIENCE.md` lines 93–99).
- **Flow 9 and FR-1 traceability.** Flow 9는 이름 있는 주인공, 번호 단계, climax, failure를 갖추고 가입→온보딩→명시적 두 번째 수단 연결→대체 수단 복구를 닫는다. FR-1은 인증, `social-sign-in`, `app-shell`, Flow 9와 UJ-2로 연결된다 (`EXPERIENCE.md` lines 224, 347–357).
- **Previous low finding.** `sync-control`의 `44px` 리터럴이 `{spacing.target-min}`으로 교체돼 이중 소유가 해소됐다 (`DESIGN.md` line 186).

### Remaining auth findings

- **[high] Account identity model conflict:** “내부 사용자 키는 `issuer + subject` 조합”이라는 규칙은 Naver와 Google처럼 여러 `(issuer, subject)`를 하나의 BokriLog 계정에 연결한다는 바로 다음 규칙 및 Flow 9와 양립하지 않는다. 이대로면 두 제공자는 서로 다른 내부 사용자가 되거나, 구현자가 문서 밖에서 별도 계정 키를 발명해야 한다 (`EXPERIENCE.md` lines 93, 95, 347–355). *Fix:* BokriLog 계정은 독립된 불투명 `user_id`를 기본 키로 갖고, 로그인 수단은 유일한 `(issuer, subject)`를 `user_id`에 매핑하는 별도 identity record라고 명시한다. 같은 identity의 중복 연결, 이미 다른 `user_id`에 묶인 identity, unlink의 원자성도 이 모델로 처리한다.
- **[medium] Social sign-in visual contract ambiguity:** `social-sign-in` frontmatter는 공통 `{colors.surface-raised}` 배경과 `{colors.ink-primary}` 전경을 기계 토큰으로 고정하지만 Components 본문은 각 제공자의 공식 버튼 가이드를 따르라고 한다. 제공자별 공식 표면이 공통 토큰과 다르면 downstream 소비자는 어느 규칙을 따라야 할지 결정할 수 없다 (`DESIGN.md` lines 112–117, 187). *Fix:* frontmatter에는 공통 container/size/gap/focus만 두고 provider-owned button surface는 `note`로 상속하거나, Google/Naver/Kakao variant별 토큰을 공식 가이드에 맞춰 분리한다.

### Auth-focused revised verdict

**Adequate, not implementation-ready.** 제공자 선택, 프로토콜, 사용자 여정, 재인증·복구와 FR-1 연결은 충분하다. 내부 `user_id`와 provider identity의 관계를 교정한 뒤에야 계정 연결·복구 계약을 안전하게 구현할 수 있다.

### Auth-focused counts

- Critical: **0**
- High: **1**
- Medium: **1**
- Low: **0**

## Post-auth-resolution verification — 2026-08-23

`social-sign-in` 소유권과 계정 식별 모델 수정분을 다시 검사했다. 직전 High와 Medium은 모두 해소됐으며, 최초 리뷰 이후 제기된 finding은 현재 남아 있지 않다.

### Resolved

- **Account identity model conflict — resolved.** 불투명 내부 `user_id`가 사용자 데이터 경계를 소유하고, 유일한 `(issuer, subject)` identity record 여러 개가 하나의 `user_id`로 연결되는 many-to-one 모델이 명시됐다. 일반 로그인은 기존 identity를 조회하고, 로그인된 설정에서 시작한 명시적 연결 트랜잭션만 새 identity를 현재 계정에 추가하며 다른 계정에 이미 연결된 identity는 정보 노출 없이 거부한다 (`EXPERIENCE.md` lines 93–94). 이 모델은 재인증·마지막 수단 해제 금지·Flow 9의 다중 복구 수단과 모순 없이 이어진다 (`EXPERIENCE.md` lines 96–100, 350–360).
- **Social sign-in visual contract ambiguity — resolved.** frontmatter는 전체 폭 세로 배치, 최소 타깃, 바깥 간격, 포커스와 provider-owned treatment만 정의한다. 본문은 제공자 공식 가이드가 로고·명칭·채움색·글자색·테두리·모서리·내부 여백에서 BokriLog 토큰보다 우선한다고 명시해 기계 토큰과 서술 계약의 소유권이 일치한다 (`DESIGN.md` lines 112–117, 187).

### Final category verdicts

- Flow coverage — **strong**
- Token completeness — **strong**
- Component coverage — **strong**
- State coverage — **strong**
- Visual reference coverage — **strong**
- Bloat & overspecification — **strong**
- Inheritance discipline — **strong**
- Shape fit — **strong**

### Final finding counts

- Critical: **0**
- High: **0**
- Medium: **0**
- Low: **0**

### Final mechanical summary

- DESIGN.md YAML은 파싱되며 components 11개를 포함한다.
- 두 spine의 `{...}` 참조 42개는 모두 DESIGN.md frontmatter 경로로 해석된다.
- `social-sign-in`은 frontmatter, DESIGN.md Components, EXPERIENCE.md Component Patterns에서 동일한 이름과 상호보완적 소유권을 사용한다.
- Flow 9는 가입, 명시적 추가 로그인 수단 연결, 대체 수단 복구, 취소·콜백 실패·중복 계정 시도를 포함하고 FR-1 coverage로 연결된다.
- PRD에서 아직 결정되지 않은 계산·광고·운영·법률 정책은 Foundation에 책임자와 차단 범위로 계속 표시되며, 이번 인증 finding의 재발은 아니다.
