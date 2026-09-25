package com.bokrilog.source;

/**
 * 제공자 API 자격정보 한 쌍. <b>요청 하나의 수명 동안만 산다.</b>
 *
 * <p>{@link #toString()}을 덮는다. record의 기본 구현은 모든 성분을 문자열에 담으므로, 이 타입이
 * 로그 한 줄이나 예외 메시지에 실리는 순간 Secret이 그대로 나간다 — "Secret은 URL·브라우저
 * 저장소·분석·로그·오류 추적·지표·광고 payload 어디에도 남기지 않는다"가 기본 구현으로는
 * 지켜지지 않는다. 값을 노출하는 유일한 경로는 접근자 둘이며, 그것을 부르는 곳은 서명 계산과
 * 암호화 둘뿐이다.
 *
 * @param apiKey 제공자 콘솔이 발급한 API Key
 * @param apiSecret 그 Key의 Secret. 저장은 암호문으로만 한다
 */
public record SourceCredentials(String apiKey, String apiSecret) {

    @Override
    public String toString() {
        // 길이도 담지 않는다. 길이 하나가 어떤 제공자의 키 형식인지를 좁혀 준다.
        return "SourceCredentials[redacted]";
    }
}
