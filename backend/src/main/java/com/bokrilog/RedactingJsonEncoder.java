package com.bokrilog;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.encoder.EncoderBase;
import com.bokrilog.operations.SensitiveText;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.KeyValuePair;

/**
 * 운영 로그의 유일한 출력 형식(Story 3.7 AC-1·AC-5): 필드 allowlist와 redaction을 거친 JSON 한 줄.
 *
 * <p><b>허용 키는 열둘이다.</b> {@code ts·level·logger·event·code·outcome·reason·kind·correlationId·durationMs·count·
 * exception}. {@code event}는 가린 메시지 본문이고 나머지 여덟은 SLF4J 구조 인자({@code addKeyValue})나 MDC에서 같은 이름으로 온다. 그 밖의
 * MDC·구조 인자는 버리고, 예외의 스택·메시지도 내보내지 않는다 — 스택 한 줄에 배포 경로와 내부 구조가 실린다
 * ({@code ApiErrorHandler}와 같은 근거). 진단에는 예외 클래스의 단순 이름 하나({@code exception})만 쓴다. 허용 키의 값도 모양을 본다: code류는 짧은 식별 code, {@code correlationId}는 영숫자·
 * {@code -}, 수치는 정수만 통과하고 아니면 그 키를 버린다.
 *
 * <p><b>가림은 세 겹이다.</b>
 * <ol>
 *   <li>실제 구성 비밀값({@link LogSecretGuard})이 메시지·인자·구조 인자·MDC·예외 문자열 어디에든 있으면 그 줄의 본문 전체를
 *       {@code [REDACTED:CONFIGURED_SECRET]}로 바꾸고 허용 키도 싣지 않는다. 그리고 강한 신호 3번으로 올린다(비동기·60초 억제).
 *   <li>메시지 본문은 문의 민감값 규칙(Story 3.6)의 SECRET·EMAIL·WALLET 모양을 {@code [REDACTED:<규칙>]}로 가린다
 *       ({@link SensitiveText}). 이것은 신호로 올리지 않는다.
 *   <li>telemetry에 식별자·금융값을 남기지 않는다(AC-6): 정규 UUID는 {@code [REDACTED:ID]}, 숫자 6자리 이상 연속은
 *       {@code [REDACTED:DIGITS]}로 바꾼다. 작업·사용자·계획 ID가 로그에 남으면 계정 삭제 뒤에도 원장과 잇는 근거가 된다.
 * </ol>
 *
 * <p>Logback이 리플렉션으로 만들므로 {@code public}이고 인자 없는 생성자를 가진다. Spring을 모른다.
 */
public final class RedactingJsonEncoder extends EncoderBase<ILoggingEvent> {

    /** 구성 비밀값이 나타난 줄의 본문. */
    static final String CONFIGURED_SECRET_MARK = "[REDACTED:CONFIGURED_SECRET]";

    static final String ID_MARK = "[REDACTED:ID]";

    /** 줄을 만들지 못했을 때의 본문. */
    static final String ENCODING_FAILED_MARK = "[REDACTED:UNRENDERABLE]";

    static final String DIGITS_MARK = "[REDACTED:DIGITS]";

    /** 구조 인자·MDC에서 옮기는 허용 키(이 순서로 쓴다). {@code ts·level·logger·event}는 encoder가 채운다. */
    static final List<String> PASS_THROUGH_KEYS =
            List.of("code", "outcome", "reason", "kind", "correlationId", "durationMs", "count", "exception");

    /** 본문 상한. 넘으면 자른다 — 한 줄이 무한히 커지지 않게. */
    static final int MAX_EVENT_CHARS = 4000;

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern LONG_DIGITS = Pattern.compile("\\p{Nd}{6,}");

    private static final Pattern CODE_VALUE = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,63}");

    /** 예외는 클래스의 단순 이름 하나뿐이다(메시지·스택 없음). */
    private static final Pattern EXCEPTION_VALUE = Pattern.compile("[A-Za-z0-9_$]{1,80}");

    private static final Pattern INTEGER_VALUE = Pattern.compile("0|-?[1-9][0-9]{0,17}");

    @Override
    public byte @Nullable [] headerBytes() {
        return null;
    }

    @Override
    public byte @Nullable [] footerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        String line;
        try {
            line = render(event);
        } catch (RuntimeException unrenderable) {
            // 로그 한 줄 때문에 로깅이 멈추지 않게 한다. 원문은 싣지 않는다.
            StringBuilder json = new StringBuilder(128).append('{');
            field(json, "ts", Instant.ofEpochMilli(event.getTimeStamp()).toString(), true);
            field(json, "level", String.valueOf(event.getLevel()), false);
            field(json, "logger", String.valueOf(event.getLoggerName()), false);
            field(json, "event", ENCODING_FAILED_MARK, false);
            line = json.append('}').toString();
        }
        return (line + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** 이벤트 하나의 JSON 한 줄(줄바꿈 없음). */
    static String render(ILoggingEvent event) {
        String formatted = String.valueOf(event.getFormattedMessage());
        Map<String, String> structured = structured(event);
        LogSecretGuard.Registration exposed = LogSecretGuard.exposedIn(screened(event, formatted, structured));

        StringBuilder json = new StringBuilder(256);
        json.append('{');
        field(json, "ts", timestamp(event), true);
        field(json, "level", String.valueOf(event.getLevel()), false);
        field(json, "logger", String.valueOf(event.getLoggerName()), false);
        if (exposed != null) {
            field(json, "event", CONFIGURED_SECRET_MARK, false);
            json.append('}');
            LogSecretGuard.report(exposed);
            return json.toString();
        }
        field(json, "event", redact(formatted), false);
        for (String key : PASS_THROUGH_KEYS) {
            String value = structured.get(key);
            if (value != null && acceptable(key, value)) {
                field(json, key, value, false);
            }
        }
        json.append('}');
        return json.toString();
    }

    /** 메시지 본문의 가림: 민감값 규칙 → UUID → 숫자 6자리 이상. 길면 자른다. */
    static String redact(String message) {
        String text = SensitiveText.redact(message);
        text = UUID_TEXT.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(ID_MARK));
        text = LONG_DIGITS.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(DIGITS_MARK));
        if (text.length() > MAX_EVENT_CHARS) {
            text = text.substring(0, MAX_EVENT_CHARS) + "…";
        }
        return text;
    }

    /** 허용 키의 구조 인자. 구조 인자({@code addKeyValue})가 MDC보다 앞선다. 허용 키 밖은 처음부터 읽지 않는다. */
    private static Map<String, String> structured(ILoggingEvent event) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> mdc = event.getMDCPropertyMap();
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        for (String key : PASS_THROUGH_KEYS) {
            String value = null;
            if (pairs != null) {
                for (KeyValuePair pair : pairs) {
                    if (pair != null && key.equals(pair.key) && pair.value != null) {
                        value = String.valueOf(pair.value);
                    }
                }
            }
            if (value == null && mdc != null) {
                value = mdc.get(key);
            }
            if (value != null) {
                values.put(key, value);
            }
        }
        // 예외를 함께 넘긴 줄(프레임워크의 오류 로그 포함)은 클래스의 단순 이름만 싣는다. 메시지·스택은 버린다.
        IThrowableProxy thrown = event.getThrowableProxy();
        if (!values.containsKey("exception") && thrown != null && thrown.getClassName() != null) {
            String name = thrown.getClassName();
            values.put("exception", name.substring(name.lastIndexOf('.') + 1));
        }
        return values;
    }

    /**
     * 구성 비밀값을 찾을 문자열들: 형식화한 메시지, 원 인자, <b>모든</b> 구조 인자·MDC 값(허용 키 밖의 것도 — 버리는 값이라도
     * 비밀이 거기 실렸다면 그것은 사건이다), 예외의 종류·메시지(원인·suppressed 사슬 포함).
     */
    private static List<String> screened(ILoggingEvent event, String formatted, Map<String, String> structured) {
        List<String> texts = new ArrayList<>();
        texts.add(formatted);
        texts.addAll(structured.values());
        Object[] arguments = event.getArgumentArray();
        if (arguments != null) {
            for (Object argument : arguments) {
                texts.add(String.valueOf(argument));
            }
        }
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) {
                if (pair != null) {
                    texts.add(String.valueOf(pair.value));
                }
            }
        }
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            texts.addAll(mdc.values());
        }
        throwableTexts(event.getThrowableProxy(), texts, 0);
        return texts;
    }

    private static void throwableTexts(@Nullable IThrowableProxy proxy, List<String> texts, int depth) {
        if (proxy == null || depth > 16) {
            return;
        }
        texts.add(String.valueOf(proxy.getMessage()));
        throwableTexts(proxy.getCause(), texts, depth + 1);
        IThrowableProxy[] suppressed = proxy.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy each : suppressed) {
                throwableTexts(each, texts, depth + 1);
            }
        }
    }

    private static boolean acceptable(String key, String value) {
        return switch (key) {
            // 문자 집합은 응답의 correlation ID 규칙과 한 벌이다(ApiErrorHandler.CORRELATION_ID).
            case "correlationId" -> ApiErrorHandler.CORRELATION_ID.matcher(value).matches();
            // 구조 수치(지연·건수)는 정수 그대로 둔다. AC-6 숫자 스캔의 대상이 아니다(DEVELOPMENT.md).
            case "durationMs", "count" -> INTEGER_VALUE.matcher(value).matches();
            case "exception" -> EXCEPTION_VALUE.matcher(value).matches() && !LONG_DIGITS.matcher(value).find();
            // code류는 식별자·금융값 모양(UUID·숫자 6자리 이상)이면 키째 버린다.
            default -> CODE_VALUE.matcher(value).matches() && !LONG_DIGITS.matcher(value).find()
                    && !UUID_TEXT.matcher(value).find();
        };
    }

    /** ms까지의 UTC ISO 시각. 나노초까지 쓰면 숫자 6자리 이상 연속이 생긴다(AC-6 단정). */
    private static String timestamp(ILoggingEvent event) {
        Instant instant = event.getInstant();
        return (instant != null ? instant : Instant.ofEpochMilli(event.getTimeStamp()))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
    }

    private static void field(StringBuilder json, String key, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(key).append("\":\"");
        escape(json, value);
        json.append('"');
    }

    /** JSON 문자열 이스케이프. 줄바꿈·제어 문자·줄 구분자를 이스케이프해 한 줄을 지킨다. 비ASCII는 그대로 둔다. */
    static void escape(StringBuilder json, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ' || c == '\u0085') {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
    }
}
