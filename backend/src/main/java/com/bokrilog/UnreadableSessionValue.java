package com.bokrilog;

import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 세션 저장소가 풀 수 없던 속성 값의 표지(운영 command 공통화 SPEC의 "배포 전 무장", 리뷰 patch).
 *
 * <p>{@link SessionAttributeConversion}이 읽을 수 없는 {@code ATTRIBUTE_BYTES}를 예외도 {@code null}도 아니라 이 값으로 읽는다.
 * {@code null}로 읽으면 "없는 속성"이 되어 재인증 표지처럼 <b>있다는 사실 자체가 판정인</b> 속성이 조용히 풀린다(fail-open).
 * 이 값은 "있지만 읽을 수 없다"를 말한다 — 각 판독이 자기 규칙으로 접는다:
 *
 * <ul>
 *   <li>세션 무장({@link SessionArming})은 없는 무장과 같고 그 속성 하나만 지운다.
 *   <li>재인증 표지({@link SessionGuard#isMarked})는 표시된 것으로 본다(fail-closed).
 *   <li>로그인 문맥은 {@code SecurityContext}가 아니므로 Spring Security의 세션 저장소가 미인증으로 읽는다.
 *   <li>그 밖의 판독은 타입을 보고 읽으므로 없는 값과 같다.
 * </ul>
 *
 * <p>값의 내용은 어디에도 남지 않는다 — 원인 예외의 단순 이름 하나다. 경고는 세션·속성마다 프로세스에서 한 번이다
 * ({@link #report}).
 *
 * <p>세션 저장소가 이 값을 다시 쓰는 일은 없다(속성을 쓸 때만 쓰고, 이 값은 판독 결과다). 그래도 직렬화할 수 있게 둔다.
 */
final class UnreadableSessionValue implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UnreadableSessionValue.class);

    /** 경고를 이미 남긴 (세션 id, 속성)의 상한. 넘으면 가장 오래된 것부터 잊는다. */
    private static final int REPORTED_LIMIT = 512;

    private static final Map<String, Boolean> REPORTED = new LinkedHashMap<>(64, 0.75f, true) {

        @Serial private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REPORTED_LIMIT;
        }
    };

    /** 원인 예외의 단순 이름. 값의 내용은 담지 않는다. */
    private final String cause;

    UnreadableSessionValue(String cause) {
        this.cause = cause;
    }

    String cause() {
        return this.cause;
    }

    /**
     * 세션 속성 하나를 읽는다. 읽을 수 없던 값이면 그 사실을 세션·속성마다 한 번 경고로 남기고 표지를 그대로 돌려준다 — 접는
     * 규칙은 판독의 몫이다(재인증 표지는 {@link SessionGuard#isMarked}).
     */
    static @org.jspecify.annotations.Nullable Object read(HttpSession session, String attribute) {
        Object value = session.getAttribute(attribute);
        if (value instanceof UnreadableSessionValue unreadable) {
            report(session, attribute, unreadable);
        }
        return value;
    }

    /**
     * 그 세션의 그 속성이 읽을 수 없었다는 경고를 프로세스에서 한 번만 남긴다. 세션 id는 로그에 싣지 않는다(키로만 쓴다).
     */
    static void report(HttpSession session, String attribute, UnreadableSessionValue value) {
        String key = session.getId() + "\n" + attribute;
        boolean first;
        synchronized (REPORTED) {
            first = REPORTED.put(key, Boolean.TRUE) == null;
        }
        if (first) {
            LOG.atWarn().addKeyValue("exception", value.cause())
                    .log("session attribute could not be read: attribute={}", attribute);
        }
    }

    /** 로그·Spring Security의 경고에 실려도 내용을 드러내지 않는다. */
    @Override
    public String toString() {
        return "unreadable session value";
    }
}
