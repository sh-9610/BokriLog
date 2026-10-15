/**
 * 광고 참여(engagement)의 소유 모듈 — Epic 4가 처음 만든 여덟 번째 모듈.
 *
 * <p><b>Story 4.1은 광고 동의를 둔다.</b> 사용자별 추가 전용 결정 행(정책 버전·선택·DB 시각)을 쌓고 지금 상태는 최신
 * 행에서 읽는다({@link com.bokrilog.engagement.AdConsentApi}). 정책 버전이 바뀌면 상태는 미결정으로 돌아가고 자동 재동의가
 * 없다. 쓰기 트랜잭션은 사용자 잠금 뒤 {@code identity}의 {@code AccountAccessApi.lockWriteAccess}로 삭제 상태를 다시 본다 —
 * 삭제가 시작된 계정의 결정이 다시 생기지 않는다.
 *
 * <p><b>Story 4.2는 광고 자격과 빈도 제한을 둔다</b>({@link com.bokrilog.engagement.AdEligibilityApi}). 후보는 공식 결과를 원자
 * 저장한 수동 동기화 attempt뿐이고({@code sync}의 공개 판정 하나로 본다), 관문(동의·전역 {@code ADVERTISING} 운영 제한·공급자
 * 활성)과 30분·{@code Asia/Seoul} 하루 3회 빈도 제한을 지나면 그 결정 행이 곧 reservation이다. 결과 하나에 결정 하나이고 재전송은
 * 새 시도가 아니다. 계측 어휘는 {@link com.bokrilog.engagement.AdTelemetry}다.
 *
 * <p>계정 삭제는 deletion port({@link com.bokrilog.engagement.EngagementDeletionApi})가 두 표의 행을 지운다.
 *
 * <p><b>이 모듈은 광고를 띄우지 않는다.</b> 공급자 adapter와 중립 전환 화면(Story 4.3), fail-open과 광고 전용 정지(Story 4.4)는 뒤
 * 스토리가 이 모듈에 더한다. 동의·자격은 연결·동기화·대시보드 어디에서도 읽지 않는다 — 광고는 결과 조회의 성공 조건이 아니다.
 * 의존은 이 모듈에서 {@code sync}·{@code operations}·{@code identity} 쪽으로만 난다.
 *
 * <p>이 모듈은 HTTP·세션을 모른다. 인증·CSRF·계정 접근 판정·본문 모양은 루트({@code AdConsentController}·
 * {@code AdEligibilityController})가 본다. 다른 모듈은 이 패키지의 공개 API로만 협력하고 {@code internal}이나 소유
 * 표({@code engagement_*})에 닿지 않는다({@code ModuleBoundaryTest}가 강제한다).
 */
package com.bokrilog.engagement;
