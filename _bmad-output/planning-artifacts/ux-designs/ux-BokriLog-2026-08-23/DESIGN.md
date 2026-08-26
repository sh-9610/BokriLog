---
name: BokriLog
description: 근거와 이력을 먼저 보여주는 차분한 복리 계획 기록 도구의 시각 계약.
status: final
created: 2026-08-23
updated: 2026-08-23
sources:
  - ../../prds/prd-BokriLog-2026-08-23/prd.md
  - ../../prds/prd-BokriLog-2026-08-23/addendum.md
colors:
  surface-base: '#F7F8F6'
  surface-raised: '#FFFFFF'
  surface-subtle: '#EEF1EE'
  ink-primary: '#17211B'
  ink-secondary: '#47564D'
  ink-muted: '#64726A'
  border-default: '#C7D0CA'
  border-strong: '#87958C'
  primary: '#245C48'
  on-primary: '#FFFFFF'
  focus-ring: '#1D4F91'
  trust-confirmed: '#1F6A4D'
  trust-confirmed-bg: '#E7F4ED'
  trust-review: '#7A4B00'
  trust-review-bg: '#FFF4D6'
  trust-unavailable: '#9F2F2F'
  trust-unavailable-bg: '#FDECEC'
  info: '#1D4F91'
  info-bg: '#EAF2FF'
  destructive: '#9F2F2F'
  on-destructive: '#FFFFFF'
typography:
  page-title:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 28px
    fontWeight: '700'
    lineHeight: '1.3'
    letterSpacing: -0.02em
  section-title:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 20px
    fontWeight: '700'
    lineHeight: '1.4'
    letterSpacing: -0.01em
  metric:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 28px
    fontWeight: '650'
    lineHeight: '1.25'
    letterSpacing: -0.02em
  body:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.6'
  label:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 14px
    fontWeight: '600'
    lineHeight: '1.45'
  meta:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 13px
    fontWeight: '400'
    lineHeight: '1.5'
  mono:
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'
    fontSize: 13px
    fontWeight: '500'
    lineHeight: '1.5'
rounded:
  sm: 6px
  md: 10px
  lg: 14px
  full: 9999px
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 24px
  '6': 32px
  '7': 48px
  page-mobile: 16px
  page-tablet: 24px
  page-desktop: 32px
  target-min: 44px
  focus-width: 2px
  focus-offset: 2px
components:
  app-shell:
    background: '{colors.surface-base}'
    foreground: '{colors.ink-primary}'
    max-content-width: '1120px'
  trust-status-panel:
    background: '{colors.surface-raised}'
    border: '{colors.border-default}'
    radius: '{rounded.lg}'
    gap: '{spacing.3}'
  metric-card:
    background: '{colors.surface-raised}'
    border: '{colors.border-default}'
    radius: '{rounded.md}'
    value-font-size: '{typography.metric.fontSize}'
    value-font-weight: '{typography.metric.fontWeight}'
    value-line-height: '{typography.metric.lineHeight}'
  sync-control:
    background: '{colors.primary}'
    foreground: '{colors.on-primary}'
    radius: '{rounded.md}'
    focus: '{colors.focus-ring}'
  social-sign-in:
    layout: 'full-width vertical stack'
    min-target-size: '{spacing.target-min}'
    gap: '{spacing.3}'
    provider-treatment: 'official brand guideline'
    focus: '{colors.focus-ring}'
  secure-input-form:
    background: '{colors.surface-raised}'
    foreground: '{colors.ink-primary}'
    border: '{colors.border-strong}'
    radius: '{rounded.sm}'
  confirmation-dialog:
    background: '{colors.surface-raised}'
    foreground: '{colors.ink-primary}'
    radius: '{rounded.lg}'
    destructive: '{colors.destructive}'
  history-row:
    background: '{colors.surface-raised}'
    divider: '{colors.border-default}'
    meta: '{colors.ink-secondary}'
  neutral-ad-transition:
    background: '{colors.surface-base}'
    foreground: '{colors.ink-secondary}'
    border: '{colors.border-strong}'
  service-notice:
    background: '{colors.info-bg}'
    foreground: '{colors.info}'
    border: '{colors.info}'
  operator-control:
    background: '{colors.surface-raised}'
    foreground: '{colors.ink-primary}'
    border: '{colors.border-strong}'
    destructive: '{colors.destructive}'
---

## Brand & Style

BokriLog는 거래를 자극하는 화면이 아니라, 계획과 그 근거를 오래 보관하는 장부에 가깝다. 화면은 “얼마를 벌었는가”보다 “이 값은 언제, 어떤 입력과 계산으로 만들어졌는가”를 먼저 말한다. 시각적 성격은 차분하고 검증 가능하며 반복해서 열어도 피로하지 않아야 한다.

MVP는 라이트 모드의 커스텀 토큰 시스템을 사용한다. 장식적 일러스트, 실시간 시세판을 연상시키는 네온, 수익 축하 효과, 손실 공포 연출, 스트릭과 배지는 사용하지 않는다. 작은 상태 변화도 과장된 모션보다 문구·시각·근거의 일관성으로 신뢰를 만든다. 다크 모드는 후속 범위이며 별도 대비 검증 전에는 제공하지 않는다.

## Colors

- `{colors.surface-base}`는 장시간 읽는 기본 바탕이며 `{colors.surface-raised}`는 근거·지표·입력 영역을 구분한다. 계층은 색조 차이와 테두리로 만들고 색면을 남용하지 않는다.
- `{colors.primary}`는 수동 동기화, 다음 단계처럼 사용자가 명시적으로 시작하는 주요 행동에만 쓴다. 수익이 양수라는 뜻으로 사용하지 않는다.
- 신뢰 상태는 `확인됨` `{colors.trust-confirmed}` / `검토 필요` `{colors.trust-review}` / `사용 불가` `{colors.trust-unavailable}` 계열을 쓰되, 반드시 상태명·원인·아이콘 또는 텍스트 표지를 함께 둔다. 녹색을 “수익”, 빨강을 “손실” 의미로 쓰지 않는다.
- `{colors.info}`는 계산 기준과 서비스 공지처럼 중립적인 설명에 쓴다.
- 핵심 대비 목표는 WCAG 2.2 AA다. `{colors.ink-primary}`/`{colors.surface-base}` 15.52:1, `{colors.ink-secondary}`/`{colors.surface-raised}` 7.76:1, `{colors.ink-muted}`/`{colors.surface-raised}` 5.05:1, `{colors.ink-primary}`/`{colors.surface-subtle}` 14.53:1, `{colors.primary}`/`{colors.on-primary}` 7.78:1, `{colors.destructive}`/`{colors.on-destructive}` 7.19:1이다. 상태 전경/배경은 `확인됨` 5.75:1·`검토 필요` 6.76:1·`사용 불가` 6.29:1이다. `{colors.focus-ring}`/흰 바탕은 8.14:1, 기능 경계인 `{colors.border-strong}`/흰 바탕은 3.13:1이다. `{colors.border-default}`는 장식적 구분에만 쓰며 입력·선택·광고 분리·포커스처럼 의미가 있는 경계에는 쓰지 않는다. 일반 텍스트 4.5:1, 큰 텍스트와 비텍스트 UI 3:1을 하한으로 유지한다.

## Typography

한국어 시스템 산세리프를 사용해 로드 지연과 플랫폼 차이를 줄인다. 숫자는 탭형 숫자(`font-variant-numeric: tabular-nums`)를 적용하되 숫자만 과도하게 키우지 않는다. `{typography.metric}`은 현재가치 및 여섯 핵심 지표의 값에, `{typography.label}`은 지표명·상태명에, `{typography.meta}`는 측정 시각·복리 주기·계산 버전에 쓴다. 긴 설명은 `{typography.body}`로 제공하고 200% 확대에서 잘리거나 겹치지 않아야 한다.

API Key, 스냅샷 ID, 계산 버전처럼 기계적 식별을 구분할 때만 `{typography.mono}`를 쓴다. 공백 없는 식별자는 안전한 줄바꿈을 허용한다. 시각적으로 축약할 때는 포커스를 받으면 전체값을 읽을 수 있게 하거나, 명시적 조작으로 전체값을 열고 복사할 수 있게 한다. 보조기술에는 처음부터 전체값을 제공한다.

## Layout & Spacing

4px 기반 간격척도를 쓰되 페이지 여백은 모바일 `{spacing.page-mobile}`, 태블릿 `{spacing.page-tablet}`, 데스크톱 `{spacing.page-desktop}`이다. 콘텐츠 최대 폭은 `{components.app-shell.max-content-width}`이며 모바일에서는 한 열, 768px 이상에서는 핵심 지표를 두 열, 1024px 이상에서는 신뢰 패널과 지표 영역을 최대 4열 그리드 안에서 배치한다.

## Elevation & Depth

계층은 배경색과 1px 테두리로 표현한다. 기본 카드에는 그림자를 쓰지 않고, 포커스된 대화상자에만 `0 12px 32px rgba(23, 33, 27, 0.14)`를 허용한다. 광고 전환 화면은 대시보드 카드와 같은 입체 언어를 쓰지 않아 금융 결과로 오인되는 것을 막는다.

## Shapes

입력은 `{rounded.sm}`, 카드·버튼은 `{rounded.md}`, 큰 패널·대화상자는 `{rounded.lg}`를 쓴다. `{rounded.full}`은 짧은 상태 라벨에만 허용하며 색만으로 의미를 만들지 않는다. 지나치게 둥근 소비자 앱 형태나 날카로운 거래 터미널 형태를 피한다.

## Components

| Component | Visual specification |
|---|---|
| `app-shell` | `{components.app-shell.background}` 위에 상단 제품명과 현재 위치, 본문, 모바일 하단 탐색/데스크톱 측면 탐색을 배치한다. 금융값은 내비게이션에 노출하지 않는다. |
| `trust-status-panel` | 마지막 정상값 아래에 현재가치 측정 시각과 자금흐름 확인 완료 시각을 구분하고, 신선도, 입력 신뢰도, 계산 성격을 동등한 세 행/열로 둔다. 각 항목은 상태명, 짧은 원인, “자세히” 링크를 포함한다. 최근 입출금 미반영 가능성과 재동기화 행동을 인접 안내하며 종합 점수나 단일 배지는 금지한다. |
| `metric-card` | 지표명 → 값 → 기준 주기·시각 → 계산 성격 순서다. 양수/음수 모두 `{colors.ink-primary}`를 기본으로 하며 색으로 성과를 평가하지 않는다. 계산 불가는 값 자리에 문구와 이유 링크를 둔다. |
| `sync-control` | `{spacing.target-min}` 이상의 명확한 주요 버튼. 진행 중에는 레이아웃을 유지하고 진행 문구와 상태 표지를 보인다. 진행 중에는 중복 조작을 차단하고 같은 요청을 다시 실행할 수 있는 상태로 보이지 않게 한다. |
| `social-sign-in` | Google·네이버·카카오 버튼을 전체 폭 세로 목록과 `{components.social-sign-in.gap}`으로 배치하고 세 제공자에 추천·우선순위 표지를 두지 않는다. 로고, 명칭, 채움색, 글자색, 테두리, 모서리와 내부 여백은 각 제공자의 최신 공식 버튼 가이드가 BokriLog 토큰보다 우선한다. BokriLog는 고정 순서, 동등한 시각 면적, 최소 `{components.social-sign-in.min-target-size}`, 바깥 간격과 `{components.social-sign-in.focus}` 포커스만 소유한다. 로고만 있는 버튼, 임의로 바꾼 브랜드 색, 마지막 사용 제공자 강조는 금지한다. 버튼 아래에는 “소셜 계정은 본인 확인에만 사용하며 Bybit·금융·계획 데이터는 공유하지 않습니다”를 `{typography.meta}`와 `{colors.ink-secondary}`로 둔다. |
| `secure-input-form` | 영구 라벨, 도움말, 오류를 필드 가까이에 둔다. 비밀값은 입력 중 가리며 저장 뒤 마스킹 값조차 재표시하지 않는다. 브라우저 자동완성 사용 여부는 보안 결정에 따른다. |
| `confirmation-dialog` | 제목에 대상과 결과를 쓰고, 보존되는 데이터와 삭제되는 데이터를 분리한다. 취소가 기본 포커스다. 계정 삭제처럼 범위가 큰 작업은 대상명 재입력을 요구한다. |
| `history-row` | 사건 시각, 유형, 원자산·수량, USD 가치, 분류 근거, 가격 출처·추정 여부, 반영 시각/버전을 읽기 순서로 제공한다. 지연 재계산은 이전 근거를 덮지 않고 변경 표지를 추가한다. |
| `neutral-ad-transition` | 금융값·계획값·수익률·신뢰 상태가 없는 별도 표면. “광고” 표지와 즉시 식별 가능한 닫기 조작을 광고 콘텐츠 및 동기화 조작과 충분히 분리한다. |
| `service-notice` | 영향 범위, 시작 시각, 현재 가능한 행동, 다음 안내 경로를 표시한다. 경고색만 쓰지 않고 제목과 문장으로 상태를 전달한다. |
| `operator-control` | 일반 사용자 UI와 분리된 보호 표면. 연결·동기화·계산 표시·광고 범위를 독립된 제어로 보여주며, 위험 동작은 `{colors.destructive}`와 확인 단계를 사용한다. |

모든 상호작용 요소는 `{colors.focus-ring}`으로 `{spacing.focus-width}` 두께, `{spacing.focus-offset}` 간격의 `focus-visible` 외곽선을 공유한다. 외곽선은 인접 배경과 3:1 이상 대비를 유지하며 강제 색상 모드에서는 시스템 포커스 색을 허용한다. 고정 탐색·오버레이·화면 키보드가 포커스 요소를 완전히 가리지 않도록 안전 영역과 스크롤 여백을 확보한다.

## Do's and Don'ts

| Do | Don't |
|---|---|
| 마지막 정상값과 측정 시각을 먼저 보여준다. | 실패를 0원이나 빈 카드로 바꾼다. |
| 세 신뢰 축을 상태명·원인·다음 행동과 함께 분리한다. | 세 축을 하나의 “정상/오류” 배지로 합친다. |
| 동일 스냅샷·계산 버전의 지표를 한 번에 교체한다. | 새 값과 이전 값을 카드별로 섞어 갱신한다. |
| 부호와 100% 초과 값을 중립적으로 그대로 표시한다. | 수익·손실을 축하·공포 색상이나 모션으로 평가한다. |
| 광고를 중립 전환 표면에 격리한다. | 광고를 지표 카드나 동기화 결과처럼 보이게 한다. |
| 기록·근거·버전이 보존된다는 사실을 화면에서 확인시킨다. | 스트릭, 배지, 푸시 압박으로 재방문을 유도한다. |

## Key Mockups

최종 시각 참조는 [소셜 로그인](mockups/key-social-login.html), [모바일 대시보드 정상 상태](mockups/key-mobile-dashboard.html), [입력 신뢰도 검토 필요](mockups/key-trust-review.html), [중립 광고 전환](mockups/key-neutral-ad-transition.html)의 네 화면이다. 목업보다 이 문서의 토큰과 컴포넌트 규칙이 우선하며, 실제 소셜 버튼과 광고 소재는 구현 시 제공자 공식 가이드 및 접근성 승인 결과에 맞춘다. 나머지 화면은 `EXPERIENCE.md`의 spine 사양으로 구현한다.
