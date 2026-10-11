package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.SupportInquiryApi.Status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 3.6: 문의 입력의 민감값 규칙·필드 규칙·상태 전이의 단위 시험. 데이터베이스 없이 규칙만 본다.
 *
 * <p>민감값 규칙의 입력 → 기대 code는 화면과 공유하는 표 하나({@code frontend/src/content/inquiryRules.fixture.json})가
 * 소유한다. 자격정보·지갑 모양 값은 그 표의 {@code {{repeat:X:N}}} 표기로 실행 시점에 만든다 — 리터럴로 적으면 gitleaks가
 * 잡는다(값이 가짜여도).
 */
class InquiryRulesTest {

    /** 서버와 화면이 함께 읽는 공유 표. 화면 시험({@code SupportInquiryPanel.test.tsx})이 같은 파일을 읽는다. */
    private static final Path SHARED_FIXTURE = Path.of("../frontend/src/content/inquiryRules.fixture.json");

    private static final Pattern REPEAT = Pattern.compile("\\{\\{repeat:([^:}]*):(\\d+)\\}\\}");

    /** {@code {{repeat:X:N}}}을 X를 N번 이은 값으로 편다 — 토큰 모양 값을 리터럴로 두지 않기 위한 표기다. */
    static String expand(String input) {
        Matcher matcher = REPEAT.matcher(input);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(expanded,
                    Matcher.quoteReplacement(matcher.group(1).repeat(Integer.parseInt(matcher.group(2)))));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    @Test
    void 공유_표의_모든_입력에_화면과_같은_규칙_code를_낸다() throws Exception {
        JsonNode cases = JsonMapper.builder().build()
                .readTree(Files.readString(SHARED_FIXTURE, StandardCharsets.UTF_8)).get("cases");
        assertThat(cases.size()).isGreaterThan(30);
        for (JsonNode row : cases) {
            String input = expand(row.get("input").asString());
            JsonNode expected = row.get("rule");
            var actual = InquiryRules.sensitive(input).map(Enum::name).orElse(null);
            assertThat(actual).as(row.get("input").asString())
                    .isEqualTo(expected == null || expected.isNull() ? null : expected.asString());
        }
    }

    @Test
    void 판정은_정규화한_값으로_하고_원문은_바꾸지_않는다() {
        assertThat(InquiryRules.normalized("１２\u200b３")).isEqualTo("123");
        assertThat(InquiryRules.normalized("￦５")).isEqualTo("₩5");
        // 제목은 서식 문자·줄·문단 구분자를 받지 않는다(한 줄 문장).
        for (String bad : List.of("제\u200b목", "제\u2028목", "제\u2029목", "제\u0085목", "제\uFEFF목")) {
            assertThat(InquiryRules.title(bad)).as(bad).isNull();
        }
    }

    @Test
    void 금융값_규칙은_서비스_공지_메시지와_같은_한_벌이다() {
        for (String bad : List.of("주문 1234567", "12,345,678", "$12,345.67", "100 USDT", "5,000원")) {
            assertThat(InquiryRules.financialLike(bad)).as(bad).isTrue();
            assertThat(OperatorEnvelopeService.isAcceptableMessage(bad)).as(bad).isFalse();
        }
        assertThat(InquiryRules.financialLike("15:30부터 1.4.2 배포 확인 중")).isFalse();
    }

    @Test
    void 필드_규칙은_길이와_제어_문자를_본다() {
        assertThat(InquiryRules.title("  제목  ")).isEqualTo("제목");
        assertThat(InquiryRules.title("가".repeat(80))).isNotNull();
        assertThat(InquiryRules.title("가".repeat(81))).isNull();
        assertThat(InquiryRules.title("   ")).isNull();
        assertThat(InquiryRules.title("줄\n바꿈")).isNull();
        assertThat(InquiryRules.description("가".repeat(2000))).isNotNull();
        assertThat(InquiryRules.description("가".repeat(2001))).isNull();
        assertThat(InquiryRules.description("줄\n바꿈\t탭")).isEqualTo("줄\n바꿈\t탭");
        assertThat(InquiryRules.description("벨\u0007")).isNull();
        assertThat(InquiryRules.answer("가".repeat(1000))).isNotNull();
        assertThat(InquiryRules.answer("가".repeat(1001))).isNull();
        assertThat(InquiryRules.replyEmail(" me@example.com ")).isEqualTo("me@example.com");
        assertThat(InquiryRules.replyEmail("not-an-email")).isNull();
        assertThat(InquiryRules.replyEmail("a@" + "b".repeat(250) + ".io")).isNull();
        assertThat(InquiryRules.replyEmail("a b@example.com")).isNull();
    }

    @Test
    void 회신_이메일은_첫_글자만_남기고_마스킹한다() {
        assertThat(InquiryRules.maskEmail("alice@domain.test")).isEqualTo("a***@d***");
        assertThat(InquiryRules.maskEmail("broken")).isEqualTo("***");
    }

    @Test
    void reference는_Q_대시_base32_10자다() {
        assertThat(InquiryRules.REFERENCE.matcher("Q-ABCDEFGH23").matches()).isTrue();
        assertThat(InquiryRules.REFERENCE.matcher("Q-ABCDEFGH01").matches()).isFalse();
        assertThat(InquiryRules.REFERENCE.matcher("q-abcdefgh23").matches()).isFalse();
    }

    @Test
    void 확인_문구의_상태_낱말은_사용자_화면의_상태_표지와_같다() throws Exception {
        String content = Files.readString(Path.of("../frontend/src/content/support.ts"), StandardCharsets.UTF_8);
        for (Status status : Status.values()) {
            Matcher label = Pattern.compile(status.name() + ": '([^']+)'").matcher(content);
            assertThat(label.find()).as(status.name()).isTrue();
            assertThat(OperatorEnvelopeService.inquiryStatusWord(status)).isEqualTo(label.group(1));
        }
    }

    @Test
    void 상태는_앞으로만_가고_답변_완료를_건너뛰지_않는다() {
        assertThat(Status.RECEIVED.canMoveTo(Status.IN_PROGRESS)).isTrue();
        assertThat(Status.RECEIVED.canMoveTo(Status.ANSWERED)).isTrue();
        assertThat(Status.IN_PROGRESS.canMoveTo(Status.ANSWERED)).isTrue();
        assertThat(Status.ANSWERED.canMoveTo(Status.RESOLVED)).isTrue();
        assertThat(Status.RECEIVED.canMoveTo(Status.RESOLVED)).isFalse();
        assertThat(Status.IN_PROGRESS.canMoveTo(Status.RESOLVED)).isFalse();
        assertThat(Status.ANSWERED.canMoveTo(Status.IN_PROGRESS)).isFalse();
        assertThat(Status.ANSWERED.canMoveTo(Status.ANSWERED)).isFalse();
        assertThat(Status.RESOLVED.canMoveTo(Status.ANSWERED)).isFalse();
    }
}
