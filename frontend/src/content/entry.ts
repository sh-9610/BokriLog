/*
 * 진입 화면 한국어 카피 정본.
 *
 * 화면과 시험이 같은 문자열을 참조해 문구 표류를 막는다.
 * 근거: epics.md Story 1.1 AC-1·AC-2, EXPERIENCE.md Voice and Tone,
 *      DESIGN.md Components/social-sign-in, UX-DR23.
 *
 * 규칙:
 *   - 판정 대신 사실·원인·다음 행동을 짧은 완전문으로 쓴다.
 *   - "실시간", "정확", "안전 보장", 수익 축하·손실 공포 표현을 쓰지 않는다.
 *   - 금융값을 넣지 않는다.
 */

export type EntrySectionTone = 'plain' | 'notice'

export interface EntrySection {
  /** heading 요소의 id. section의 aria-labelledby가 참조한다. */
  readonly id: string
  readonly heading: string
  readonly tone: EntrySectionTone
  readonly items: readonly string[]
}

const SECTIONS: readonly EntrySection[] = [
  {
    id: 'entry-scope',
    heading: '이 서비스가 하는 일',
    tone: 'plain',
    items: [
      '읽기 전용으로 연결한 Bybit UTA 계좌에서 마지막 정상값과 그 측정 시각을 확인합니다.',
      '기간·복리 주기·목표 주기율로 세운 계획과 실제 기록을 같은 스냅샷·계산 버전으로 비교합니다.',
      '값이 언제, 어떤 입력과 계산으로 만들어졌는지 근거와 조정 이력을 함께 보관합니다.',
    ],
  },
  {
    id: 'entry-non-goals',
    heading: '이 서비스가 하지 않는 일',
    tone: 'plain',
    items: [
      '거래 실행·투자 추천을 제공하지 않습니다.',
      '주문·출금·이체를 실행하지 않습니다. Bybit 연결은 조회 권한만 사용합니다.',
      '실시간 시세와 상시 폴링을 제공하지 않습니다. 동기화는 사용자가 직접 요청할 때만 실행합니다.',
    ],
  },
  {
    id: 'entry-auth-boundary',
    heading: '로그인 정보의 사용 범위',
    tone: 'notice',
    items: [
      'Google·네이버·카카오는 본인 확인에만 사용하며 Bybit 자격정보·금융 데이터·계획은 공유하지 않습니다.',
      '이 화면은 로그인 없이 열립니다. 화면을 여는 것만으로 외부 인증·광고 요청이나 브라우저 저장이 발생하지 않습니다.',
    ],
  },
  {
    id: 'entry-next-step',
    heading: '다음 단계',
    tone: 'plain',
    items: [
      '로그인 수단 선택은 아직 준비 중입니다. 준비되면 Google·네이버·카카오를 같은 위계로 이 화면에 제공합니다.',
      '가입하기 전에 이 안내만 읽고 화면을 닫아도 됩니다. 남는 기록은 없습니다.',
    ],
  },
]

/** 본문 랜드마크의 id. 건너뛰기 링크의 `href`와 `main`의 `id`가 같은 값을 쓴다. */
export const MAIN_LANDMARK_ID = 'entry-main'

export const entryContent = {
  brand: 'BokriLog',
  skipLink: '본문으로 건너뛰기',
  /** UX-DR23: 이 제목과 바로 아래 고지를 인접 배치한다. */
  pageTitle: '복리 계획 비교',
  disclaimer: '투자성과·계좌 수익률·투자 추천이 아닙니다.',
  lead: 'BokriLog는 읽기 전용 Bybit UTA 연결을 기준으로 복리 계획과 기록을 비교하는 서비스입니다.',
  sections: SECTIONS,
} as const

/** 화면에 렌더되어야 하는 모든 카피. 시험이 문구 표류를 감지하는 데 쓴다. */
export const entryCopyStrings: readonly string[] = [
  entryContent.brand,
  entryContent.skipLink,
  entryContent.pageTitle,
  entryContent.disclaimer,
  entryContent.lead,
  ...SECTIONS.flatMap((section) => [section.heading, ...section.items]),
]
