/*
 * stylelint 설정.
 *
 * 정본: _bmad-output/implementation-artifacts/spec-frontend-lint-policy.md, DESIGN.md, DEVELOPMENT.md `## 프런트엔드`.
 * 규칙이 실제로 위반을 잡는지는 `lint-policy.test.ts`가 fixture로 고정한다.
 *
 * 핵심 규칙은 `scale-unlimited/declaration-strict-value`다. `DESIGN.md`가 소유하는 네 범주
 * (색, 타이포그래피, 모서리, 간격)의 속성에서 리터럴을 금지하고 `tokens.css`의 `var(--…)`만 허용한다.
 *
 *   - 예외 범주는 규칙이 아니라 속성 목록으로 표현한다. `DESIGN.md`에 토큰이 없는 범주
 *     (모션 duration·easing, `z-index`, 구조적 `1px` 테두리 두께)는 대상 속성이 아니므로
 *     disable 주석 없이 리터럴을 쓸 수 있다. 대상 속성에서 리터럴이 필요하면 이유 있는
 *     disable이 필요하고, 그 압력은 규칙 완화가 아니라 `DESIGN.md` 토큰 추가로 향한다.
 *   - `expandShorthand`로 `border`·`outline`·`background`·`font`·`padding`·`margin` 축약형을
 *     longhand로 펼친 뒤 대상 속성만 검사한다. `border: 1px solid var(--color-border-default)`는
 *     `border-color`만 검사되고 `1px`(두께)·`solid`(스타일)는 대상이 아니다.
 *   - `font` 축약형은 직접 대상에도 넣는다. 펼치기는 토큰 위치를 추정하므로 `bold 14px/1.5 sans-serif`처럼
 *     크기·행간이 한 토큰에 묶이면 longhand 검사가 새지만, 직접 검사는 토큰 하나하나를 본다.
 *   - 논리 border 축약형(`border-inline`, `border-block-start` 등)은 펼치기 표에 없다. 대신
 *     `declaration-property-value-disallowed-list`로 색 리터럴 패턴만 금지한다. 두께·스타일은 예외 범주다.
 *   - `box-shadow`·`text-shadow`(Elevation)는 DESIGN.md가 토큰이 아니라 문장으로 정한 값이라 대상 밖이다.
 *   - 함수는 토큰을 인용할 때만 허용한다. `ignoreFunctions: true`는 `rgb()`·`hsl()`까지 통과시켜
 *     색 규칙에 구멍을 내므로, `calc|max|min|clamp` 안에 `var(--`가 있는 값만 `ignoreValues`로 연다.
 *     값이 여러 줄이면 `.`이 개행을 못 넘으므로 `[\s\S]`를 쓴다.
 *   - 인라인 예외는 이유가 있는 disable만 허용한다(`reportDescriptionlessDisables`). 위반이 없는
 *     곳의 disable(`reportNeedlessDisables`)과 범위가 틀린 disable(`reportInvalidScopeDisables`)도 오류다.
 */

/** forced-colors 모드에서만 유효한 CSS 시스템 색 이름. 토큰이 아니지만 접근성 탈출구로 허용한다. 대소문자를 가리지 않는다. */
const SYSTEM_COLOR_KEYWORDS =
  '/^(AccentColor|AccentColorText|ActiveText|ButtonBorder|ButtonFace|ButtonText|Canvas|CanvasText|Field|FieldText|GrayText|Highlight|HighlightText|LinkText|Mark|MarkText|SelectedItem|SelectedItemText|VisitedText)$/i'

/** CSS 전역 키워드. 값을 상속·초기화할 뿐 새 리터럴을 들이지 않는다. */
const CSS_WIDE_KEYWORDS = '/^(initial|unset|revert|revert-layer|normal)$/'

/**
 * 토큰을 하나라도 인용하는 계산식은 허용한다. 예: `max(var(--space-page-mobile), env(safe-area-inset-left, 0px))`.
 * `calc(var(--space-1) + 13px)`처럼 토큰과 리터럴이 섞인 식도 통과하는 의도된 경계다. 식 안의 리터럴까지 막으면
 * `env()` 폴백·비율 계산이 모두 disable을 요구하게 된다.
 */
const TOKEN_MATH_FUNCTION = '/^(calc|max|min|clamp)\\([\\s\\S]*var\\(--[\\s\\S]*\\)$/'

/** 논리 border 축약형. `expandShorthand`가 펼치지 못해 색 리터럴 패턴을 직접 금지한다. */
const LOGICAL_BORDER_SHORTHAND = '/^border-(inline|block)(-(start|end))?$/'
const COLOR_LITERAL_PATTERNS = [
  '/#[0-9a-f]{3,8}\\b/i',
  '/\\b(rgba?|hsla?|hwb|lab|lch|oklab|oklch|color)\\(/',
]

/** @type {import('stylelint').Config} */
export default {
  extends: ['stylelint-config-standard'],
  plugins: ['stylelint-declaration-strict-value'],
  reportDescriptionlessDisables: true,
  reportNeedlessDisables: true,
  reportInvalidScopeDisables: true,
  rules: {
    'scale-unlimited/declaration-strict-value': [
      [
        // 색. `color`, `background-color`, `border-color`, `border-*-color`, `outline-color` 등과 SVG 채움·선.
        '/color$/',
        'fill',
        'stroke',
        // 타이포그래피. `DESIGN.md` typography가 소유하는 다섯 속성과 `font` 축약형 자체.
        'font',
        '/^font-(family|size|weight)$/',
        'line-height',
        'letter-spacing',
        // 모서리. `border-radius`와 네 longhand(`border-top-left-radius` 등).
        '/radius$/',
        // 간격. `padding-*`, `margin-*`, `gap`, `inset-*`, 물리 오프셋, 스크롤 여백, 포커스 링 두께·간격.
        '/^(padding|margin|gap|inset)/',
        'row-gap',
        'column-gap',
        '/^(top|right|bottom|left)$/',
        '/^scroll-(padding|margin)/',
        'outline-offset',
        'outline-width',
      ],
      {
        expandShorthand: true,
        ignoreFunctions: false,
        ignoreValues: [
          '0',
          'auto',
          'inherit',
          CSS_WIDE_KEYWORDS,
          '/^currentcolor$/i',
          'transparent',
          'none',
          '100%',
          SYSTEM_COLOR_KEYWORDS,
          TOKEN_MATH_FUNCTION,
        ],
        message:
          '"${property}"에 리터럴 "${value}"을(를) 쓸 수 없습니다. DESIGN.md가 소유하는 색·타이포그래피·모서리·간격은 src/styles/tokens.css의 var(--…) 토큰을 참조하세요.',
      },
    ],
    // 논리 border 축약형의 색 리터럴. 두께·스타일 리터럴은 예외 범주라 색 패턴만 막는다.
    'declaration-property-value-disallowed-list': [
      { [LOGICAL_BORDER_SHORTHAND]: COLOR_LITERAL_PATTERNS },
      {
        message: (property, value) =>
          `"${property}: ${value}"에 색 리터럴을 쓸 수 없습니다. 색은 src/styles/tokens.css의 var(--…) 토큰을 참조하거나 longhand(예: border-inline-color)로 분리하세요.`,
      },
    ],
    // 기존 `(min-width: 768px)` 표기를 유지한다. 범위 표기(`width >= 768px`)로 바꾸지 않는다.
    'media-feature-range-notation': 'prefix',
  },
  overrides: [
    {
      // CSS Module 클래스는 TSX에서 `styles.skipLink`로 참조하므로 camelCase다.
      files: ['**/*.module.css'],
      rules: {
        'selector-class-pattern': [
          '^[a-z][a-zA-Z0-9]*$',
          { message: (selector) => `CSS Module 클래스 "${selector}"는 camelCase여야 합니다.` },
        ],
      },
    },
    {
      // 토큰 정의 파일. 리터럴이 정의되는 유일한 장소이므로 토큰 강제 규칙만 제외한다.
      // `stylelint-config-standard`의 나머지 규칙은 그대로 적용된다.
      files: ['src/styles/tokens.css'],
      rules: {
        'scale-unlimited/declaration-strict-value': null,
        // 토큰 그룹(colors·typography·rounded·spacing·components) 사이 빈 줄은 DESIGN.md의 범주 구분이다.
        // 표준 규칙은 연속한 custom property 사이 빈 줄을 지우므로 이 파일에서만 끈다.
        'custom-property-empty-line-before': null,
        // 값 표기는 DESIGN.md frontmatter를 그대로 옮긴다. `#ffffff`를 `#fff`로 줄이거나
        // 글꼴 이름(`Menlo`, `BlinkMacSystemFont`)을 소문자로 바꾸지 않는다. 이 파일의 예외는 인라인
        // disable 대신 여기 한 곳에 모아 둔다.
        'color-hex-length': null,
        'value-keyword-case': null,
      },
    },
  ],
}
