/**
 * 금융 source 연결과 자격정보의 권위.
 *
 * <p>제공자 계정·연결·암호화된 자격정보와 제공자 어댑터를 소유한다(AD-15). "이 자격정보가 우리가
 * 받아들일 수 있는 읽기 전용 메인 계정의 것인가"와 "그 연결이 지금 살아 있는가"에 답하는 유일한
 * 곳이며, 다른 모듈은 이 패키지의 공개 application API로만 협력하고 {@code internal} 하위 패키지나
 * 소유 테이블에 직접 닿지 않는다. {@code ModuleBoundaryTest}가 이를 강제한다.
 *
 * <p>여기에는 HTTP 표면이 없다. 컨트롤러도 엔드포인트도 없고, 연결을 만드는 경로는 루트 패키지의
 * {@code BybitConnectionController} 하나다({@code identity}와 같은 배치). 재인증·허가·세션 회전은
 * 세션 개념을 요구하므로 이 모듈이 가질 수 없다.
 *
 * <p><b>제공자 타입이 밖으로 나가지 않는다.</b> 어댑터는 provider-neutral DTO만 돌려주고
 * ({@code SourceVerification}) Bybit 응답 원문·지갑 값·permission 배열은 이 패키지를 떠나지 않는다.
 * 계산 모듈이 Bybit 타입을 모른다는 계약이 여기서 시작된다.
 *
 * <p><b>평문 자격정보는 어디에도 남지 않는다.</b> 저장은 AES-256-GCM·매번 새 96비트 nonce·다섯
 * 필드 AAD·active key version뿐이고({@code internal.CredentialCipher}) 응답·로그·지표·오류에 API
 * Key와 Secret이 실리지 않는다. 성공 뒤에도 마스킹 값조차 재표시하지 않는다.
 *
 * <p>거부는 사용자에게 구분 가능하다. {@code identity}가 하나의 값으로 접는 것과 다른데, 이유는
 * 무엇이 노출되는가다 — 여기서 갈리는 것은 <b>사용자 자신이 제공자 콘솔에서 고칠 수 있는 사실</b>
 * 이지 남의 계정의 존재가 아니다. 예외 둘은 그대로다: 타 사용자·미존재 대상은 같은 외부 오류
 * 계약으로 접히고, 이미 다른 계정에 연결된 UID는 원인만 알리고 상대 계정을 드러내지 않는다.
 */
package com.bokrilog.source;
