package com.bokrilog.operations.internal;

import com.bokrilog.operations.SupportInquiryApi;
import com.bokrilog.operations.SupportInquiryApi.SensitiveRule;
import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 문의 입력·운영자 답변의 규칙(Story 3.6 AC-1·AC-5). 순수 함수뿐이다.
 *
 * <p><b>민감값 규칙</b>은 서버 판정이 권위이고 화면({@code frontend/src/content/support.ts})이 같은 규칙으로 미리 안내한다.
 * 거절은 어느 규칙인지({@link SensitiveRule})만 말하고 맞은 조각을 돌려주지 않는다 — 원문이 응답·로그로 새지 않게 한다.
 *
 * <p>금융값 모양({@link #financialLike})은 서비스 공지 메시지 규칙(Story 3.5)과 <b>같은 한 벌</b>이다 — 공지
 * ({@code OperatorEnvelopeService.isAcceptableMessage})도 이것을 부른다.
 */
final class InquiryRules {

    /**
     * PEM 머리말과 {@code Bearer } 토큰. 둘 다 대소문자를 가리지 않고 낱말 경계를 보지 않는다 — 경계의 의미는 두 정규식
     * 엔진에서 다르다(한글 앞뒤). 화면 규칙({@code content/support.ts})과 같은 의미이고 공유 fixture 표
     * ({@code inquiryRules.fixture.json})가 그것을 강제한다.
     */
    private static final Pattern SECRET_MARKER =
            Pattern.compile("(?i)-----begin|bearer\\s", Pattern.UNICODE_CHARACTER_CLASS);

    /** 영숫자·{@code -_} 낱말. 토큰 모양 판정의 단위다. */
    private static final Pattern WORD_RUN = Pattern.compile("[A-Za-z0-9_-]+");

    /** 토큰 모양으로 보는 낱말의 최소 길이. 영문자와 숫자가 함께 있어야 한다. */
    private static final int SECRET_MIN_CHARS = 20;

    /** 정규 UUID(8-4-4-4-12). 화면 오류의 correlationId이므로 토큰으로 보지 않는다. */
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 대문자·숫자·밑줄만의 오류 code(예: {@code CALCULATION_DISPLAY_RESTRICTED}). 토큰으로 보지 않는다. */
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]+");

    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");

    /** EVM 지갑 주소: {@code 0x} + 40 hex. */
    private static final Pattern HEX_WALLET = Pattern.compile("0[xX][0-9a-fA-F]{40}");

    /** 영숫자 낱말(앞뒤가 영숫자가 아닌 연속). base58·bech32 판정의 단위다. */
    private static final Pattern ALNUM_TOKEN = Pattern.compile("[A-Za-z0-9]+");

    /** base58 알파벳(0·O·I·l 제외). */
    private static final Pattern BASE58 = Pattern.compile("[1-9A-HJ-NP-Za-km-z]+");

    /** bech32: 사람이 읽는 접두사 + 구분자 {@code 1} + 데이터 문자. */
    private static final Pattern BECH32 = Pattern.compile("(?i)[a-z]{1,10}1[02-9ac-hj-np-z]{6,}");

    private static final int WALLET_MIN_CHARS = 26;

    private static final int WALLET_MAX_CHARS = 62;

    /** 이메일 주소처럼 보이는 조각(운영 사유 규칙과 같은 모양). */
    private static final Pattern EMAIL_LIKE =
            Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** ISO 날짜({@code YYYY-MM-DD}). 금융값 판정 전에 지운다 — 날짜는 금액·계좌번호가 아니다. */
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\p{Nd})\\p{Nd}{4}-\\p{Nd}{2}-\\p{Nd}{2}(?!\\p{Nd})");

    /** 시각({@code HH:MM}, {@code HH:MM:SS}). 금융값 판정 전에 지운다. */
    private static final Pattern CLOCK_TIME =
            Pattern.compile("(?<![\\p{Nd}:])\\p{Nd}{1,2}:\\p{Nd}{2}(?::\\p{Nd}{2})?(?![\\p{Nd}:])");

    /** 지운 날짜·시각 자리. 숫자도 구분자도 아니라 앞뒤 숫자를 잇지 않는다. */
    private static final String ERASED = "#";

    /** 숫자 6자리 이상 연속. */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\p{Nd}{6,}");

    /**
     * 구분자({@code ,} {@code .} 유니코드 공백 {@code :} {@code -})로 이은 숫자 묶음. 묶음 안 숫자의 합이 6자리 이상이면
     * 금융값·식별자로 본다 — {@code 12,345,678}·{@code 1 234 567}처럼 끊어 쓴 값도 막는다.
     */
    private static final Pattern DIGIT_GROUP =
            Pattern.compile("\\p{Nd}+(?:[,.:\\s-]\\p{Nd}+)*", Pattern.UNICODE_CHARACTER_CLASS);

    /** 통화 기호·통화 code와 붙은 숫자. 정규화 뒤에 보므로 전각 ￦·＄도 걸린다. */
    private static final Pattern MONEY_LIKE = Pattern.compile(
            "(?i)[$₩€¥£]\\s*\\p{Nd}|\\p{Nd}[\\p{Nd},.]*\\s*(?:USDT|USD|원)|(?:USDT|USD)\\s*\\p{Nd}",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** 서식 문자(폭 없는 공백·soft hyphen·BOM 등). 판정 전에 지운다 — 사이에 끼워 규칙을 비켜 가지 못하게 한다. */
    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}");

    /** 제목의 금지 문자: 제어 문자 전부·서식 문자·줄·문단 구분자(한 줄 문장이다). */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}\\u2028\\u2029\\u0085]");

    /** 본문·답변의 금지 문자: 줄바꿈·탭을 뺀 제어 문자. */
    private static final Pattern CONTROL_EXCEPT_LINES = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");

    /** 회신 이메일의 모양. 한 주소이고 공백·꺾쇠·따옴표가 없다. */
    private static final Pattern REPLY_EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    /** 문의 reference의 모양: {@code Q-} + base32(RFC 4648 대문자) 10자. */
    static final Pattern REFERENCE = Pattern.compile("Q-[A-Z2-7]{10}");

    private InquiryRules() {}

    /**
     * 판정 앞의 정규화: NFKC(전각 숫자·통화 기호·＠를 보통 문자로)와 서식 문자 제거. 규칙은 이 값을 본다 — 저장하는 값은
     * 원문 그대로다.
     */
    static String normalized(String text) {
        return FORMAT.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
    }

    /**
     * 민감값 모양이 있으면 그 규칙. 판정 순서는 표지(PEM·Bearer) → 이메일 → 지갑 → 토큰 모양 낱말 → 금융값이다 — 순서는
     * 어느 code로 말하는지만 정하고, 하나라도 맞으면 거절이다. 판정은 정규화한 값으로 한다({@link #normalized}).
     */
    static Optional<SensitiveRule> sensitive(String raw) {
        String text = normalized(raw);
        if (SECRET_MARKER.matcher(text).find()) {
            return Optional.of(SensitiveRule.SECRET);
        }
        if (EMAIL_LIKE.matcher(text).find()) {
            return Optional.of(SensitiveRule.EMAIL);
        }
        if (walletLike(text)) {
            return Optional.of(SensitiveRule.WALLET);
        }
        if (tokenLike(text)) {
            return Optional.of(SensitiveRule.SECRET);
        }
        if (financialLike(text)) {
            return Optional.of(SensitiveRule.FINANCIAL);
        }
        return Optional.empty();
    }

    /**
     * 토큰 모양: 영문자와 숫자가 함께 있는 20자 이상 {@code [A-Za-z0-9_-]} 낱말. 정규 UUID(오류 화면의 correlationId)와
     * 대문자·숫자·밑줄만의 오류 code는 뺀다 — 사용자가 화면에서 옮겨 적는 값이다. 숫자 없는 긴 영단어도 토큰이 아니다.
     */
    static boolean tokenLike(String text) {
        Matcher words = WORD_RUN.matcher(text);
        while (words.find()) {
            String word = words.group();
            if (word.length() < SECRET_MIN_CHARS || UUID_TEXT.matcher(word).matches()
                    || ERROR_CODE.matcher(word).matches()) {
                continue;
            }
            if (HAS_LETTER.matcher(word).find() && HAS_DIGIT.matcher(word).find()) {
                return true;
            }
        }
        return false;
    }

    /** 지갑 주소 모양: {@code 0x}+40 hex, 또는 26~62자 영숫자 낱말이 base58이거나 bech32다. */
    static boolean walletLike(String text) {
        if (HEX_WALLET.matcher(text).find()) {
            return true;
        }
        Matcher tokens = ALNUM_TOKEN.matcher(text);
        while (tokens.find()) {
            String token = tokens.group();
            if (token.length() < WALLET_MIN_CHARS || token.length() > WALLET_MAX_CHARS) {
                continue;
            }
            if (BASE58.matcher(token).matches() || BECH32.matcher(token).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 금융값 모양: 숫자 6자리 이상 연속, 구분자로 이은 숫자 합 6자리 이상, 통화 기호·통화 code·{@code 원}과 붙은 숫자.
     * ISO 날짜와 시각은 먼저 지운다(날짜·시각은 금융값이 아니다). 서비스 공지 메시지 규칙(Story 3.5)과 같은 한 벌이다.
     */
    static boolean financialLike(String raw) {
        String text = normalized(raw);
        text = CLOCK_TIME.matcher(ISO_DATE.matcher(text).replaceAll(ERASED)).replaceAll(ERASED);
        if (LONG_DIGITS.matcher(text).find() || MONEY_LIKE.matcher(text).find()) {
            return true;
        }
        Matcher groups = DIGIT_GROUP.matcher(text);
        while (groups.find()) {
            long digits = groups.group().codePoints().filter(Character::isDigit).count();
            if (digits >= 6) {
                return true;
            }
        }
        return false;
    }

    /** 제목: 양 끝 공백을 걷은 값이 1~80자이고 제어 문자가 없다. 맞지 않으면 {@code null}. */
    static @Nullable String title(@Nullable String text) {
        String title = text == null ? "" : text.strip();
        if (title.isEmpty() || title.length() > SupportInquiryApi.MAX_TITLE_CHARS || CONTROL.matcher(title).find()) {
            return null;
        }
        return title;
    }

    /** 본문: 양 끝 공백을 걷은 값이 1~2000자이고 줄바꿈·탭 밖의 제어 문자가 없다. 맞지 않으면 {@code null}. */
    static @Nullable String description(@Nullable String text) {
        return multiline(text, SupportInquiryApi.MAX_DESCRIPTION_CHARS);
    }

    /** 운영자 답변: 양 끝 공백을 걷은 값이 1~1000자이고 줄바꿈·탭 밖의 제어 문자가 없다. 맞지 않으면 {@code null}. */
    static @Nullable String answer(@Nullable String text) {
        return multiline(text, SupportInquiryApi.MAX_ANSWER_CHARS);
    }

    private static @Nullable String multiline(@Nullable String text, int max) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty() || value.length() > max || CONTROL_EXCEPT_LINES.matcher(value).find()) {
            return null;
        }
        return value;
    }

    /** 회신 이메일: 양 끝 공백을 걷은 값이 254자 이내의 한 주소다. 맞지 않으면 {@code null}. */
    static @Nullable String replyEmail(@Nullable String text) {
        String email = text == null ? "" : text.strip();
        if (email.isEmpty() || email.length() > SupportInquiryApi.MAX_EMAIL_CHARS
                || !REPLY_EMAIL.matcher(email).matches()) {
            return null;
        }
        return email;
    }

    /**
     * 회신 이메일 마스킹({@code a***@d***}): 로컬 부분과 도메인의 첫 글자만 남긴다. 운영자 화면에 원문을 내지 않는다.
     */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return "***";
        }
        return email.charAt(0) + "***@" + email.charAt(at + 1) + "***";
    }
}
