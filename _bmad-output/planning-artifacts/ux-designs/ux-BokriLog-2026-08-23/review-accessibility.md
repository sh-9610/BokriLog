# 접근성 검토 — BokriLog

## Overall verdict

**조건부 적합(adequate), 구현 전 보완 필요.** WCAG 2.2 AA 목표, 44×44 CSS px 터치 목표, 200% 텍스트 확대와 320 CSS px 리플로, 색상 외 상태 표지, 오류 연결, 대화상자 포커스, 축소 모션, 원자적 갱신 공지는 이미 계약에 포함되어 있다. 특히 세 신뢰 축을 이름·원인·다음 행동과 함께 독립적으로 노출하고 동기화 실패 시 마지막 정상값을 유지하는 구조는 인지 접근성과 금융정보 신뢰에 강하다.

다만 키보드 사용자가 광고를 빠져나오는 경로와 전역 포커스 표시 계약은 구현자 재량이 너무 크다. 동적 공지의 단일 소유권, 비동기 폼의 포커스 유지, 표→카드 변환의 프로그램적 관계도 구체화해야 한다. 현재 문서만으로는 모든 핵심 흐름의 WCAG 2.2 AA 적합을 재현 가능하게 보장할 수 없다.

## Scope

- 검토 대상: `DESIGN.md`, `EXPERIENCE.md`, `.memlog.md`, `.working/source-extract.md`, 확정 PRD와 `addendum.md`
- 기준: WCAG 2.2 AA의 시각·행동 계약. 대비와 토큰 의미, 확대/리플로, 키보드와 포커스, 대화상자·광고 전환, 스크린리더 공지, 동적·원자적 갱신, 오류와 보안 입력, 표/카드 변환, 축소 모션, 터치 목표, 색상 독립성, 세 신뢰 축의 인지 가능성을 검토했다.
- 제한: 인증 방식과 광고 공급자가 미결정이므로 실제 로그인 위젯, 세션 시간 제한, 광고 iframe/SDK의 접근성은 최종 구현 단계에서 별도 검증해야 한다 (`EXPERIENCE.md` Foundation, lines 19–26).

## Findings

### 1. 전면광고의 포커스 경계와 실패 탈출 조건이 안전하게 정의되지 않았다

- **Severity:** high
- **Location:** `EXPERIENCE.md` State Patterns line 118; Interaction Primitives line 131; `DESIGN.md` Components line 181
- **Evidence:** 광고는 별도 라우트/전체 화면으로 정의되지만 동시에 “포커스 가둠”을 요구한다. 닫기의 위치·탭 순서, 최초 포커스, 제3자 iframe에 포커스가 들어간 뒤 상위 문서의 닫기로 돌아오는 방법, 공급자가 키보드 트랩을 만들 때의 실패 개방(fail-open) 조건은 없다.
- **Consequence:** 키보드·스위치·스크린리더 사용자가 제3자 광고 안에 갇혀 저장된 결과로 이동하지 못할 수 있다. 이는 핵심 결과 접근을 막으며 WCAG 2.1.2, 2.4.3과 PRD NFR-16의 즉시 닫기 요구를 훼손한다.
- **Fix:** 광고 라우트 진입 시 페이지 제목 또는 상위 문서의 `광고 닫기` 버튼으로 포커스를 보내고, 닫기를 DOM 첫 조작이자 광고 iframe 밖의 네이티브 버튼으로 유지한다. 전체 페이지라면 일반 문서 탭 순서를 사용하고, 모달로 구현할 때만 상위 문서 범위에서 포커스를 순환시킨다. iframe 탈출 불가·레이블 누락·키보드 트랩·로드 시간 초과가 검출되면 광고를 즉시 건너뛰고 결과 라우트로 이동하는 공급자 승인 기준과 실제 보조기술 시험을 계약에 추가한다.

### 2. 전역 키보드 포커스 표시와 ‘가려지지 않음’ 규칙이 없다

- **Severity:** high
- **Location:** `DESIGN.md` tokens lines 21, 104–108 and Colors line 150; `EXPERIENCE.md` Interaction Primitives lines 127–131; Responsive & Platform lines 147–151
- **Evidence:** `{colors.focus-ring}`과 대비는 `sync-control`에만 연결된다. 링크, 하단/측면 탐색, 신뢰 축 펼치기, 이력 행, 입력, 닫기, 파괴 확인 등 모든 조작의 포커스 외형·두께·오프셋·고대비 모드 동작이 없고, 고정 하단/측면 탐색이나 오버레이가 포커스된 요소를 가리지 않아야 한다는 규칙도 없다.
- **Consequence:** 키보드 사용자가 현재 위치를 잃거나 고정 UI 아래의 조작을 확인하지 못해 연결, 검토, 종료·삭제 같은 핵심 과업을 수행하지 못할 수 있다(WCAG 2.4.7, 2.4.11).
- **Fix:** 모든 상호작용 컴포넌트가 상속하는 전역 `focus-visible` 토큰(예: 최소 2 CSS px 외곽선, 명시적 offset, 인접 배경 대비 3:1 이상)을 정의한다. 강제 색상 모드에서 시스템 색을 허용하고 포커스가 sticky/fixed 영역이나 열린 오버레이에 완전히 가려지지 않도록 스크롤 여백·레이아웃 규칙과 테스트 항목을 추가한다.

### 3. 색상 대비 계약이 일부 실제 사용 조합을 빠뜨린다

- **Severity:** medium
- **Location:** `DESIGN.md` color tokens lines 10–31, component tokens lines 92–135, Colors lines 146–150
- **Evidence:** 핵심 조합의 수치는 제시했지만 `{colors.ink-muted}`, `{colors.surface-subtle}`, `{colors.destructive}`/`{colors.on-destructive}`의 실제 조합과 모든 포커스 인접색 조합은 명시되지 않았다. `{colors.border-default}`는 장식 전용이라고 선언하면서 `neutral-ad-transition`의 경계에도 사용한다. 광고와 금융 결과의 구획 식별에 이 경계가 필요하다면 장식이 아니다.
- **Consequence:** 구현자가 미검증 토큰을 본문·메타·경고·컨트롤 경계에 사용하거나 낮은 대비의 광고 구분선에 의미를 맡겨 WCAG 1.4.3/1.4.11을 위반할 수 있다.
- **Fix:** 모든 허용된 전경/배경·아이콘·컨트롤 경계·포커스 조합을 토큰 표로 확정하고 AA 최소 대비를 기록한다. `border-default`는 의미 전달 금지를 컴포넌트별로 명시하고, 광고 닫기·입력·선택·금융/광고 구획처럼 기능 경계가 필요한 곳은 검증된 `border-strong` 또는 동등한 3:1 토큰을 사용한다.

### 4. 동적 상태 공지의 소유권이 겹쳐 중복·순서 역전 가능성이 있다

- **Severity:** medium
- **Location:** `EXPERIENCE.md` Component Patterns line 70; Atomic Update lines 99–105; Accessibility Floor line 139
- **Evidence:** `trust-status-panel` 자체가 상태 변화를 `aria-live`로 알리고 Accessibility Floor도 동기화 시작·성공·실패를 공지한다. 원자적 렌더 커밋에서 세 축과 여섯 지표가 동시에 바뀔 때 어느 리전이 공지를 소유하는지, `aria-atomic`/`aria-busy` 사용 여부와 자동 진입 동기화의 공지 정책이 없다.
- **Consequence:** 스크린리더가 “갱신 완료”와 축별 변화를 중복 낭독하거나 DOM 갱신 순서에 따라 오래된 상태와 새 상태를 섞어 알릴 수 있다. 사용자는 원자적 스냅샷이라는 핵심 신뢰 보장을 오해할 수 있다.
- **Fix:** 화면당 하나의 전용 `role="status"`/polite live region이 동기화 공지를 소유하도록 한다. 상태 패널과 지표 묶음에는 `aria-live`를 두지 말고 갱신 중 컨테이너에 `aria-busy="true"`, 완료 시 한 문장짜리 원자 공지만 넣는다. 수동·자동 동기화, 실패, 검토 필요, 서비스 정지 사이의 공지 우선순위와 중복 억제 규칙을 명시한다.

### 5. 비동기 폼 검증 중 포커스 유지와 단계 진행의 프로그램적 표현이 불명확하다

- **Severity:** medium
- **Location:** `EXPERIENCE.md` Component Patterns line 73; State Matrix lines 111–114; Accessibility Floor line 140
- **Evidence:** 인증은 “제출 버튼과 필드를 잠그고”, 연결 검증은 권한→UTA→UID/IP→조회 단계를 표시한다. 현재 포커스된 제출 버튼을 실제 `disabled`로 바꿀지, 진행 목록을 어떻게 낭독할지, 비동기 실패 후 오류 요약으로 포커스를 보낼지 또는 호출점에 유지할지 정의되지 않았다.
- **Consequence:** 포커스가 문서 시작으로 튀거나 진행 상태를 알 수 없고, 여러 단계 뒤 발생한 오류를 찾지 못할 수 있다. 인지 부담이 큰 API 연결에서 활성화 실패로 이어진다.
- **Fix:** 요청 중 포커스는 호출 버튼에 유지하고 필요하면 `aria-disabled="true"`와 중복 실행 차단을 함께 사용한다. 검증 단계는 이름 있는 순서 목록으로 제공하고 현재 단계는 `aria-current="step"`, 결과는 중앙 live region에서 한 번 알린다. 실패 시 오류 요약에 프로그램적으로 포커스를 보내고 요약 링크가 정확한 필드 또는 해결 안내로 이동하도록 한다.

### 6. 비밀값 초기화 시점과 실패 회복 계약이 충돌한다

- **Severity:** medium
- **Location:** `DESIGN.md` Components line 178; `EXPERIENCE.md` Component Patterns line 73; UJ-2 failure path lines 214–216
- **Evidence:** API 비밀은 “제출 뒤” 비우지만 UJ-2 실패 경로는 유효 입력을 보존한다고 한다. 읽기 권한·UTA·UID/IP·조회처럼 필드 값 자체와 무관한 후단 실패에서도 비밀을 무조건 지우면 어떤 값이 보존되는지, 포커스와 재입력 안내가 어떻게 제공되는지 불명확하다.
- **Consequence:** 운동·시각·인지 제약이 있는 사용자가 긴 비밀값을 반복 입력하거나 붙여넣어야 하고, 원인을 수정하지 않은 채 같은 실패를 반복할 수 있다.
- **Fix:** 보안 결정에 따라 초기화 시점을 하나로 확정한다. 실패 시 비밀을 지워야 한다면 나머지 유효 필드는 보존하고, 비밀 필드로 포커스를 이동해 “보안을 위해 비웠으며 다시 입력해야 함”을 오류가 아닌 안내로 연결한다. 붙여넣기를 막지 않고 보이기/숨기기 버튼의 상태·목적·키보드 이름을 유지한다. 성공 시에는 현재 규칙대로 원문과 마스킹 재표시를 모두 금지한다.

### 7. 조정 이력의 표↔카드 변환은 시각 구조만 정의하고 의미 관계를 확정하지 않았다

- **Severity:** medium
- **Location:** `EXPERIENCE.md` Component Patterns line 75; Accessibility Floor line 143; Responsive & Platform lines 149–151; `DESIGN.md` Components line 180
- **Evidence:** 데스크톱 표, 모바일 카드형 행, 반복 라벨과 키보드 펼치기는 정했지만 표 caption·열/행 머리글 연결, 펼치기 버튼 이름과 `aria-expanded`/`aria-controls`, 카드의 사건별 그룹명, 정렬 변경 공지 규칙이 없다.
- **Consequence:** 스크린리더 사용자는 사건 시각·수량·USD 가치·가격 출처가 어느 사건과 열에 속하는지 잃을 수 있고, 펼친 상세나 재계산 버전의 변화를 발견하지 못할 수 있다.
- **Fix:** 데스크톱은 실제 표와 caption, 적절한 `th scope`를 사용하고 각 행의 펼치기에는 사건을 포함한 접근 가능한 이름과 `aria-expanded`/`aria-controls`를 준다. 모바일 카드는 사건별 heading/group과 프로그램적으로 연결된 반복 라벨을 사용한다. DOM 순서는 화면 크기에 관계없이 의미 읽기 순서를 유지하고 정렬 결과는 짧게 공지한다.

### 8. 확대·리플로에서 긴 식별자와 고정 탐색의 동작이 열려 있다

- **Severity:** medium
- **Location:** `DESIGN.md` Typography lines 154–156; Layout lines 158–160; `EXPERIENCE.md` Accessibility Floor line 137; Responsive & Platform lines 147–151
- **Evidence:** 200% 확대와 320 CSS px 리플로 목표는 있으나 API Key, 스냅샷 ID, 계산 버전 같은 공백 없는 긴 문자열의 줄바꿈·축약·전체값 접근 방법, 모바일 고정 하단 탐색과 화면 키보드가 콘텐츠/포커스를 가리지 않는 규칙은 없다. 320 CSS px 검증이 400% 브라우저 확대 시나리오를 포함하는지도 명시되지 않았다.
- **Consequence:** 확대 사용자가 가로 스크롤을 강요받거나 식별자의 차이를 확인하지 못하고, 입력·대화상자 조작이 화면 키보드나 고정 탐색 아래 가려질 수 있다(WCAG 1.4.10, 2.4.11).
- **Fix:** 공백 없는 식별자는 안전한 줄바꿈을 허용하고 시각 축약 시 전체값을 텍스트로 열거나 복사할 수 있게 한다. 1280 CSS px 기준 400% 확대와 320 CSS px viewport를 모두 시험 항목으로 명시하고, sticky/fixed UI·화면 키보드에서 포커스 요소가 보이도록 safe-area, scroll-padding, modal max-height/내부 스크롤 규칙을 추가한다.

### 9. 세 신뢰 축과 개별 지표 사이의 프로그램적 연결이 충분히 명시되지 않았다

- **Severity:** low
- **Location:** `DESIGN.md` Components lines 175–176; `EXPERIENCE.md` State Patterns lines 91–97; Accessibility Floor lines 138–139
- **Evidence:** 세 축은 독립 그룹과 제목을 갖고 지표 카드에는 계산 성격을 둔다. 그러나 각 지표가 어떤 입력 신뢰도·계산 성격의 영향을 받는지 카드와 상태 상세 사이에서 접근 가능한 설명 관계로 연결하라는 규칙은 없다.
- **Consequence:** 시각적으로 떨어진 상태 패널을 보지 못하는 사용자는 “최신”을 모든 지표가 “공식”이라는 뜻으로 오해하거나 `계산 불가`가 어느 값에 적용되는지 놓칠 수 있다. 이는 PRD SM-5/SM-C2의 이해도 목표에도 영향을 준다.
- **Fix:** 축 이름과 상태를 항상 함께 읽히게 하고(예: “입력 신뢰도 — 검토 필요”), 각 지표 카드가 적용되는 계산 성격과 원인 요약을 직접 포함하거나 상태 상세 ID를 `aria-describedby`로 참조하게 한다. 보조기술 사용자를 포함한 과업 평가에서 `최신 + 검토 필요 + 계산 불가` 조합을 별도 검증한다.

### 10. 축소 모션에서 스피너 예외가 불필요한 지속 애니메이션을 허용한다

- **Severity:** low
- **Location:** `EXPERIENCE.md` Accessibility Floor line 142; `DESIGN.md` Components line 177
- **Evidence:** `prefers-reduced-motion`에서도 스피너를 예외로 남긴다. 텍스트 진행 상태가 이미 있으므로 회전 애니메이션이 정보를 전달하는 데 필수적이지 않다.
- **Consequence:** 지속 회전에 민감한 사용자가 동기화나 연결 검증 중 불편을 겪을 수 있다.
- **Fix:** 축소 모션에서는 회전 스피너를 정적 진행 아이콘 또는 비애니메이션 상태 표지로 바꾸고 텍스트를 유지한다. 애니메이션이 켜진 경우에도 깜박임 금지와 진행 종료 시 제거를 명시한다.

## Coverage summary

| Area | Verdict | Notes |
|---|---|---|
| 대비와 토큰 의미 | adequate | 주요 조합은 강하나 실제 사용 조합과 기능 경계의 완전한 매트릭스가 필요하다. |
| 확대·리플로 | adequate | 200%/320 px 목표는 있으나 400% 시나리오, 긴 식별자, 고정 UI가 미정이다. |
| 키보드·포커스 | thin | 기본 입력 규칙은 있으나 전역 포커스 외형·가림 방지·광고 탈출이 불충분하다. |
| 스크린리더·동적 갱신 | adequate | 단일 요약 공지는 좋으나 live region 소유권과 비동기 단계 의미가 미정이다. |
| 오류·폼·비밀값 | adequate | 라벨·오류 연결은 강하나 진행 중 포커스와 비밀값 실패 회복을 확정해야 한다. |
| 표·카드 변환 | thin | 정보 보존은 명시됐지만 프로그램적 헤더·그룹·펼침 관계가 없다. |
| 모션·터치 | strong | 44×44 CSS px와 모션 감소가 명시됐으며 스피너 예외만 보완하면 된다. |
| 색상 외 상태 표현 | strong | 상태명·원인·아이콘/텍스트 표지가 일관되게 요구된다. |
| 세 신뢰 축 이해 | adequate | 독립 표현은 강하나 개별 지표와의 접근 가능한 관계를 더 명확히 해야 한다. |

## Finding counts

- Critical: 0
- High: 2
- Medium: 6
- Low: 2

## Post-fix verification

### Revised verdict

**대체로 적합(strong), 광고 공급자 통합 게이트 1건 잔여.** 최초 10개 지적 중 9개는 `DESIGN.md`와 `EXPERIENCE.md`의 구현 계약으로 해결됐다. 키보드 포커스, 단일 동적 공지, 비동기 폼, 비밀값 실패 회복, 표/카드 의미 구조, 400% 확대, 신뢰 축 연결, 축소 모션은 하류 구현자가 재현할 수 있는 수준으로 구체화됐다.

전면광고도 iframe 밖 첫 닫기와 fail-open이 추가되어 사용자 탈출 경로는 크게 강화됐다. 다만 제3자 iframe 내부의 키보드 트랩·레이블 문제를 배포 전 어떻게 검출하고 공급자를 승인/차단하는지에 대한 실행 가능한 시험 게이트가 아직 명시되지 않아, 해당 항목만 **부분 해결**로 남긴다.

### Finding-by-finding status

1. **부분 해결 — 전면광고 포커스 경계와 실패 탈출.** `EXPERIENCE.md` lines 80, 161, 176은 iframe 밖 네이티브 닫기를 DOM 첫 조작·최초 포커스로 두고, 전체 화면은 일반 탭 순서, 모달만 상위 문서 포커스 순환, 트랩·레이블 누락·시간초과 시 결과로 fail-open하도록 확정했다. 이는 즉시 탈출과 포커스 순서 위험을 해소한다. 다만 “감지되면”의 시점·방법과 실제 키보드/스크린리더 공급자 승인 시험이 없어 cross-origin iframe 내부 트랩의 사전 차단은 재현 불가하다. **잔여 fix:** 광고 공급자 승인 체크에 키보드 진입·탈출, 닫기 접근 이름, VoiceOver/TalkBack/NVDA 최소 조합을 넣고 한 항목이라도 실패하면 해당 공급자/형식을 출시하지 않는다고 명시한다. 잔여 심각도는 **medium**.

2. **해결 — 전역 키보드 포커스.** `DESIGN.md` lines 87–89, 188과 `EXPERIENCE.md` line 172가 모든 조작에 공유되는 2 CSS px `focus-visible`, 2 CSS px offset, 3:1 인접 대비, 강제 색상 모드, 고정 UI·화면 키보드 비가림을 확정했다.

3. **해결 — 색상 대비 조합.** `DESIGN.md` line 153이 muted·subtle·destructive 조합을 추가하고 의미 경계에서 `border-default` 사용을 금지했다. 광고 경계도 line 129에서 `{colors.border-strong}`으로 교체됐으며 모든 의미 있는 경계와 포커스에 AA 하한이 적용된다.

4. **해결 — 동적 공지 소유권.** `EXPERIENCE.md` line 74가 상태 패널의 자체 공지를 제거하고, line 184가 화면당 단일 `role="status"` polite 리전, `aria-busy`, 완료·실패·검토·정지 우선순위와 단일 문장 공지를 확정했다.

5. **해결 — 비동기 폼 포커스와 단계 의미.** `EXPERIENCE.md` line 185가 호출 버튼 포커스 유지, `aria-disabled`, 순서 목록, `aria-current="step"`, 실패 시 오류 요약 포커스와 정확한 대상 연결을 명시했다.

6. **해결 — 비밀값 초기화와 실패 회복.** `EXPERIENCE.md` line 77이 실패 시 Secret만 지우고 나머지 유효 입력을 보존하며, 비밀 필드로 포커스를 보내 재입력 이유를 알리고 붙여넣기를 허용하도록 확정했다. 성공 시 원문·마스킹 비재표시도 유지된다.

7. **해결 — 표↔카드 의미 관계.** `EXPERIENCE.md` lines 79, 188이 실제 표 caption, `th scope`, 사건별 heading/group, 반복 라벨 관계, 사건명이 포함된 펼치기 이름, `aria-expanded`/`aria-controls`, 정렬 단일 공지를 정의했다.

8. **해결 — 확대·리플로와 긴 식별자.** `DESIGN.md` line 159가 긴 식별자의 안전한 줄바꿈·전체값 접근·복사를 정했고, `EXPERIENCE.md` lines 182, 189–190이 1280 CSS px 기준 400% 확대, 320 CSS px viewport, safe-area, `scroll-padding`, 대화상자 제한 높이·내부 스크롤을 계약화했다.

9. **해결 — 세 신뢰 축과 지표 연결.** `EXPERIENCE.md` line 75가 각 지표에 계산 성격·원인 요약을 직접 포함하거나 신뢰 상세를 `aria-describedby`로 참조하도록 했고, lines 133–139와 183이 축별 이름·상태·원인 및 독립 그룹/제목을 유지한다.

10. **해결 — 축소 모션의 스피너.** `EXPERIENCE.md` line 187이 모션 감소 설정에서 회전 스피너까지 제거하고 정적 진행 표지와 텍스트로 대체하도록 수정했다.

### Revised counts

- Status: resolved 9 / partially resolved 1 / open 0
- Residual severity: Critical 0 / High 0 / Medium 1 / Low 0

### Release verification note

문서 계약의 잔여 접근성 위험은 광고 공급자 통합에 한정된다. 광고 없이 출시하는 PRD 기본 동작을 유지하거나, 위 보조기술 승인 게이트를 통과한 공급자만 활성화하면 기존 high 위험은 해소된 것으로 닫을 수 있다.
