import type { ReactElement } from 'react'

import { type SignInProvider } from '../content/auth'
import styles from './ProviderMark.module.css'

/**
 * 제공자 공식 로고·심볼.
 *
 * 두 자리가 같은 마크를 쓴다 — 진입 화면의 로그인 버튼과 재인증 패널의 확인 버튼이다. 경로
 * 데이터는 각 제공자의 공식 애셋에서 추출한 값이고 색은 그 가이드가 소유하므로, 두 번째 소비자가
 * 생기는 순간 그것을 복사하는 것이 가장 나쁜 선택이다. 한쪽만 고쳐지면 같은 서비스가 두 가지
 * 로고를 렌더하고, 그중 하나는 가이드가 금지하는 "변형된 로고"다.
 *
 * 인라인 SVG다. 외부 파일이면 브라우저가 매 로드마다 요청을 하나 만들고, 외부 CDN·외부 이미지는
 * 브라우저 경계를 통째로 넘는다.
 *
 * 모두 `aria-hidden`이다. 접근 가능한 이름은 옆의 텍스트가 제공하므로 로고를 다시 읽어 주면
 * 이름이 두 번 들린다. 로고만 있는 버튼은 DESIGN.md가 금지한다.
 */

/**
 * 제공자별 마크. 닫힌 맵이다.
 *
 * 2항 삼항식으로 두면 else가 곧 "나머지 제공자 전부"가 되어, 제공자가 늘 때 그 버튼이 남의
 * 로고로 렌더되고 타입 검사도 시험도 그것을 막지 못한다. `Record<SignInProvider, …>`는 누락된
 * 항목을 타입 검사가 잡는다.
 */
const MARKS: Readonly<Record<SignInProvider, () => ReactElement>> = {
  google: GoogleMark,
  naver: NaverMark,
  kakao: KakaoMark,
}

interface ProviderMarkProps {
  readonly provider: SignInProvider
}

/** 그 제공자의 마크. */
export function ProviderMark({ provider }: ProviderMarkProps): ReactElement {
  const Mark = MARKS[provider]
  return <Mark />
}

/**
 * Google 공식 "G" 마크.
 *
 * 인라인 SVG다. 외부 파일이면 브라우저가 매 로드마다 요청을 하나 만들고, 외부 CDN이면 브라우저
 * 경계를 통째로 넘는다. 색은 Google 가이드가 소유하는 값이며 `tokens.css`가 그 값을 기록한다.
 *
 * `aria-hidden`이다. 접근 가능한 이름은 옆의 텍스트("Google로 계속하기")가 제공하므로 로고를
 * 다시 읽어 주면 이름이 두 번 들린다. 로고만 있는 버튼은 DESIGN.md가 금지한다.
 */
function GoogleMark(): ReactElement {
  return (
    <svg
      aria-hidden="true"
      className={styles.googleMark}
      focusable="false"
      viewBox="0 0 48 48"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"
        fill="var(--component-social-sign-in-google-logo-red)"
      />
      <path
        d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"
        fill="var(--component-social-sign-in-google-logo-blue)"
      />
      <path
        d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"
        fill="var(--component-social-sign-in-google-logo-yellow)"
      />
      <path
        d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
        fill="var(--component-social-sign-in-google-logo-green)"
      />
    </svg>
  )
}

/**
 * 네이버 공식 "N" 로고.
 *
 * `GoogleMark`와 같은 방식의 인라인 SVG다. 외부 파일이면 브라우저가 매 로드마다 요청을 하나 만들고,
 * 외부 CDN·외부 이미지는 브라우저 경계를 통째로 넘는다. 색은 네이버 가이드가 소유하는 값이며
 * `tokens.css`가 그 값을 기록한다.
 *
 * **경로는 공식 애셋에서 추출한 값이다.** 손으로 그린 근사가 아니다. 출처는 공식 버튼 가이드
 * (`developers.naver.com/docs/login/bi/bi.md`)가 링크하는 한글 버튼 애셋
 * `https://developers.naver.com/inc/devcenter/downloads/bi/NAVER_login_KR.ai`(PDF 1.5, 2026-09-09
 * 내려받음, 애셋 내 버튼 파일 날짜 2025-12-04)이며, 그 안의 N 마크는 곡선 없는 10점 직선 경로다.
 * 아래 좌표는 그 애셋의 16×16 인스턴스를 SVG 좌표계로 변환한 것이다(y축 뒤집음).
 *
 * **bbox가 정확한 정사각형이라 24px 박스에서 잉크가 박스를 가득 채운다.** 경로의 bounding box는
 * x 0~16 · y 0~16이고 애셋의 16×16·18×18·20×20 인스턴스가 모두 같은 성질을 갖는다. 이것이
 * 손으로 그린 이전 경로와의 실질적 차이다 — 그 경로는 `viewBox="0 0 20 20"` 안에서 잉크가
 * 10×9만 차지해 24px 박스에서 실제 잉크 높이가 10.8px이었고, 그러면 가이드의 "텍스트는 로고
 * 높이보다 작은 크기"가 렌더 결과에서 깨진다(레이블 20px). 게다가 가이드가 금지하는 첫 항목이
 * 로고 변형이다.
 *
 * 로고를 변형·조합하지 않는다. 완성형(로고 + 레이블)의 공표된 하한은 16px이고 실제 크기는
 * `--component-social-sign-in-naver-logo-size`(24px)다 — "텍스트는 로고 높이보다 작은 크기"라는
 * 가이드 요구와 레이블의 WCAG 큰 텍스트 문턱이 함께 그 값을 정한다(근거는 tokens.css의 그 토큰
 * 주석). 잉크 높이를 실제로 재는 단정은 `e2e/auth-entry.spec.ts`가 갖는다.
 *
 * `aria-hidden`이다. 접근 가능한 이름은 옆의 텍스트("네이버로 계속하기")가 제공하므로 로고를 다시
 * 읽어 주면 이름이 두 번 들린다. 로고만 있는 버튼은 DESIGN.md가 금지한다.
 */
function NaverMark(): ReactElement {
  return (
    <svg
      aria-hidden="true"
      className={styles.naverMark}
      focusable="false"
      viewBox="0 0 16 16"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        d="M10.848 8.565 L4.916 0 L0 0 L0 16 L5.151 16 L5.151 7.438 L11.083 16 L16 16 L16 0 L10.848 0 Z"
        fill="var(--component-social-sign-in-naver-logo)"
      />
    </svg>
  )
}

/**
 * 카카오 공식 로그인 심볼(말풍선).
 *
 * `GoogleMark`·`NaverMark`와 같은 방식의 인라인 SVG다. 외부 파일이면 브라우저가 매 로드마다 요청을
 * 하나 만들고, 외부 CDN·외부 이미지는 브라우저 경계를 통째로 넘는다. 색은 카카오 가이드가 소유하는
 * 값이며 `tokens.css`가 그 값을 기록한다.
 *
 * **경로는 공식 애셋에서 추출한 값이다.** 손으로 그린 근사가 아니다. 카카오는 네이버와 달리 버튼
 * 애셋을 벡터로 배포하지 않는다 — 공식 디자인 가이드
 * (`developers.kakao.com/docs/ko/kakaologin/design-guide`)와 리소스 도구
 * (`developers.kakao.com/tool/resource/login`)가 내보내는 것은 PNG·PSD뿐이다. 그래서 그 가이드가
 * 싣는 원본 래스터(`developers.kakao.com/docs/img/login_button_component_ko.png`, 2026-09-09
 * 내려받음)의 심볼 영역을 부화소 정밀도로 추적해 기하로 복원했다.
 *
 * 복원된 기하는 셋이다. 몸통은 축 정렬 타원(반지름 12.695 × 9.912), 꼬리는 그 타원에서 좌하로
 * 내려가는 직선 두 개, 꼬리 끝은 반지름 0.454의 둥근 모서리다. 추적한 윤곽점 222개에 대해 이
 * 기하의 최대 편차는 **0.42px**, RMS는 **0.11px**이다(원본 심볼 높이 52.9px 기준 0.8% · 0.2%).
 * 렌더 크기 16px에서는 최대 0.13px에 해당한다.
 *
 * 심볼의 형태·비율·색을 바꾸지 않는다 — 그 셋이 가이드가 금지하는 첫 항목들이다. `viewBox`의 비율
 * 25.39 : 24는 원본 잉크의 가로세로비(1.0579)를 그대로 옮긴 값이고, 아래 CSS는 높이만 정하고 폭을
 * `auto`로 두어 그 비율이 렌더에서도 유지된다.
 *
 * `aria-hidden`이다. 접근 가능한 이름은 옆의 텍스트("카카오로 계속하기")가 제공하므로 심볼을 다시
 * 읽어 주면 이름이 두 번 들린다. 심볼만 있는 버튼은 DESIGN.md가 금지하고 가이드도 "심볼 제거"와
 * 함께 그런 형태를 다루지 않는다.
 */
function KakaoMark(): ReactElement {
  return (
    <svg
      aria-hidden="true"
      className={styles.kakaoMark}
      focusable="false"
      viewBox="0 0 25.39 24"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        d="M11.097 19.746L4.834 23.924A0.454 0.454 0 0 1 4.144 23.430L5.557 18.110A12.695 9.912 0 1 1 11.097 19.746Z"
        fill="var(--component-social-sign-in-kakao-logo)"
      />
    </svg>
  )
}
