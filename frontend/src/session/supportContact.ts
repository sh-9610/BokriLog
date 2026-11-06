/**
 * 서버가 준 지원 연락처(`BOKRILOG_SUPPORT_CONTACT`)의 모양 판정 — 서비스 공지(3.5)·문의(3.6)·계정 삭제 진행·완료 화면(5.4)이 이 하나를 쓴다.
 * 화면은 그 값을 `mailto:`에 그대로 싣으므로 공백·꺾쇠·따옴표·쿼리가 없는 이메일 주소 하나여야 한다. 사본을 만들지 않는다.
 */

/** 이메일 주소 하나의 모양. 로컬 부분 64자·전체 254자 이내. */
const CONTACT = /^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/

/** 최대 길이(이메일 주소 상한). */
const MAX_CONTACT_LENGTH = 254

/** `mailto:`에 실어도 되는 지원 연락처인가. */
export function isSupportContact(value: unknown): value is string {
  return typeof value === 'string' && value.length <= MAX_CONTACT_LENGTH && CONTACT.test(value)
}
