/**
 * 문맥형 광고 동의(Story 4.1)의 카피·경로·상태.
 *
 * 설정 화면의 `광고 동의` 영역(`components/AdConsentPanel`)과 시험이 같은 문자열을 참조한다. 금지 표현 목록은
 * `content/copyRules`가 소유하고 시험이 이 모듈의 문자열을 그것으로 훑는다.
 *
 * 데이터 흐름 설명(공급자·범위·전달될 수 있는 것·전달하지 않는 것)은 서버의 정책 버전(`AdConsentApi.POLICY_VERSION`) 한
 * 벌에 묶인다 — 설명을 바꾸면 그 버전도 함께 올린다. 화면은 이 설명의 버전을 결정 요청에 실어 보내고, 서버의 지금 버전과
 * 다르면 저장되지 않는다(409). 이 문구는 외부 게이트 G-4(개인정보 검토)가 확정하기 전의 초안이고 출시 전 검토 대상이다.
 *
 * 공급자의 활성 상태(계약·승인 전 `DISABLED`)는 이 화면에 드러내지 않는다(SPEC Ask First).
 */

import { noticeTimeText } from './serviceNotice'

/** 광고 동의 조회·변경. 백엔드 `AdConsentController.AD_CONSENT_PATH`와 같은 값이다. */
export const AD_CONSENT_PATH = '/api/engagement/ad-consent'

/** 이 화면의 설명이 묘사하는 정책 버전. 서버 `AdConsentApi.POLICY_VERSION`과 같은 값이어야 저장된다. */
export const AD_CONSENT_POLICY_VERSION = 'AD-CONSENT-1.0'

/** 지금 상태. 서버 `AdConsentApi.State`와 같은 닫힌 집합이다. */
export const AD_CONSENT_STATES = ['UNDECIDED', 'GRANTED', 'DENIED'] as const

export type AdConsentState = (typeof AD_CONSENT_STATES)[number]

/** 사용자의 선택. 서버 `AdConsentApi.Choice`와 같다. */
export type AdConsentChoice = 'GRANTED' | 'DENIED'

const STATE_SENTENCES: Readonly<Record<AdConsentState, string>> = {
  UNDECIDED: '아직 선택하지 않았습니다. 선택하기 전에는 광고를 요청하지 않습니다.',
  GRANTED: '문맥형 광고에 동의했습니다.',
  DENIED: '문맥형 광고에 동의하지 않았습니다. 광고를 요청하지 않습니다.',
}

export const adConsentContent = {
  /** 영역 제목의 id. landmark `region`의 이름이 된다. */
  id: 'ad-consent-heading',
  heading: '광고 동의',
  body: 'BokriLog는 무료 서비스를 운영하기 위해, 수동 동기화가 끝난 뒤 금융정보가 없는 별도 화면에서 닫을 수 있는 광고를 보여 줄 수 있습니다. 광고는 아래 내용에 동의한 경우에만 요청합니다.',
  providerLabel: '광고 공급자',
  provider: 'Google Ad Manager(Google Publisher Tag)',
  scopeLabel: '광고 범위',
  scope:
    '개인 맞춤 광고가 아닌 문맥형 광고만 요청합니다. 내 활동 기록이나 관심사로 광고를 고르지 않습니다.',
  sharedHeading: '광고 공급자에게 전달될 수 있는 것',
  shared: [
    'IP 주소',
    '브라우저 정보(User-Agent)',
    '광고 공급자의 쿠키와 브라우저 저장소',
    '광고가 표시되는 화면의 주소(URL)와 제목',
    '동의 여부와 광고 공급자 식별자',
  ],
  notSharedHeading: '전달하지 않는 것',
  notShared: [
    '잔고·현재가치 같은 금융정보',
    '복리 계획의 조건과 결과',
    '데이터 신뢰 상태',
    '계정·거래소 연결 같은 내 데이터',
  ],
  /** 동의 여부와 핵심 기능의 독립. */
  independence: '동의하지 않아도 거래소 연결, 동기화, 대시보드는 똑같이 동작합니다.',
  withdrawal: '동의는 이 화면에서 언제든 철회할 수 있고, 철회는 다음 광고 요청부터 적용됩니다.',
  stateLabel: '지금 선택',
  decidedAtLabel: '선택 시각',
  grant: '동의',
  deny: '동의하지 않음',
  withdraw: '동의 철회',
  regrant: '다시 동의',
  checking: '광고 동의 상태를 확인하고 있습니다.',
  unavailable: '광고 동의 상태를 확인하지 못했습니다. 확인하기 전에는 광고를 요청하지 않습니다.',
  retry: '광고 동의 상태 다시 확인',
  /** 다시 읽기가 실패했다. 아래 상태는 마지막으로 확인한 것이다. */
  refreshFailed: '최신 상태를 다시 확인하지 못했습니다. 마지막으로 확인한 상태입니다.',
  /** 서버의 정책 버전이 이 화면의 설명과 다르다. 바뀐 설명을 보기 전에는 고를 수 없다. */
  outdated:
    '광고 동의 안내가 바뀌었습니다. 화면을 새로 열어 바뀐 안내를 확인한 뒤 다시 선택해 주세요.',
  /** 결과 문장. 화면의 단일 live region이 말한다. */
  saving: '광고 동의 선택을 저장하고 있습니다.',
  savedGranted: '광고 동의를 저장했습니다.',
  savedDenied: '광고에 동의하지 않음을 저장했습니다.',
  withdrawn: '광고 동의를 철회했습니다. 다음 광고 요청부터 적용됩니다.',
  /** 같은 요청의 재전송 사이에 다른 곳(다른 탭)에서 선택이 바뀌었다. 저장 성공이 아니다. */
  changedElsewhere:
    '다른 곳에서 광고 동의 선택이 바뀌었습니다. 아래 지금 선택을 확인한 뒤 필요하면 다시 골라 주세요.',
  /** 저장하지 못했다(5xx·네트워크·시간 초과). 이전 선택을 그대로 둔다. 자동으로 다시 보내지 않는다. */
  failed: '선택을 저장하지 못했습니다. 이전 선택이 그대로 유지됩니다. 잠시 뒤 다시 눌러 주세요.',
  /** 서버가 거절했다(충돌·키·CSRF·계정 접근). */
  refused:
    '선택을 처리하지 못했습니다. 이전 선택이 그대로 유지됩니다. 화면을 새로 연 뒤 다시 선택해 주세요.',
} as const

/** 지금 상태의 문장. */
export function adConsentStateSentence(state: AdConsentState): string {
  return STATE_SENTENCES[state]
}

/** 선택 시각. 서울 시각에 `KST`를 붙인다. */
export function adConsentTimeText(value: string): string {
  return noticeTimeText(value)
}

/** 시험과 화면이 함께 쓰는 금지 표현 검사 대상. 화면이 그리는 모든 문장이다. */
export const adConsentCopyStrings: readonly string[] = [
  adConsentContent.heading,
  adConsentContent.body,
  adConsentContent.providerLabel,
  adConsentContent.provider,
  adConsentContent.scopeLabel,
  adConsentContent.scope,
  adConsentContent.sharedHeading,
  ...adConsentContent.shared,
  adConsentContent.notSharedHeading,
  ...adConsentContent.notShared,
  adConsentContent.independence,
  adConsentContent.withdrawal,
  adConsentContent.stateLabel,
  adConsentContent.decidedAtLabel,
  adConsentContent.grant,
  adConsentContent.deny,
  adConsentContent.withdraw,
  adConsentContent.regrant,
  adConsentContent.checking,
  adConsentContent.unavailable,
  adConsentContent.retry,
  adConsentContent.refreshFailed,
  adConsentContent.outdated,
  adConsentContent.saving,
  adConsentContent.savedGranted,
  adConsentContent.savedDenied,
  adConsentContent.withdrawn,
  adConsentContent.changedElsewhere,
  adConsentContent.failed,
  adConsentContent.refused,
  ...AD_CONSENT_STATES.map(adConsentStateSentence),
]
