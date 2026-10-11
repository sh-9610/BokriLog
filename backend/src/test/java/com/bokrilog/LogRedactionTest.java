package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 로그의 필드 allowlist·redaction과 구성 비밀값 감지(Story 3.7 AC-1·AC-5·AC-6).
 *
 * <p>로그는 실제 Logback 구성({@code logback-spring.xml})을 지나 표준 출력으로 나간 바이트를 캡처해 본다. 구성 비밀값은 이
 * 컨텍스트가 실제로 가진 OIDC client secret이다({@link OidcStubConfiguration#CLIENT_SECRET}) — 시험 훅이 그 값을 로그 인자로
 * 쓴다. 신호는 비동기로 기록되므로 행이 생길 때까지 기다린 뒤에 시험을 끝낸다(다음 시험의 저장소 초기화 뒤로 새지 않게).
 *
 * <p>값 모양의 시험 입력(토큰·지갑)은 실행 시점에 만든다 — 리터럴로 적으면 gitleaks가 잡는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionTest extends OperatorTestBase {

    private static final Logger PROBE = LoggerFactory.getLogger("com.bokrilog.LogRedactionProbe");

    private static final Set<String> ALLOWED_KEYS = Set.of("ts", "level", "logger", "event", "code", "outcome",
            "reason", "kind", "correlationId", "durationMs", "count");

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern EMAIL = Pattern.compile("[^\\s@\"]+@[^\\s@\"]+\\.[^\\s@\"]+");

    private static final Pattern LONG_DIGITS = Pattern.compile("\\p{Nd}{6,}");

    @BeforeEach
    @AfterEach
    void resetGuard() {
        LogSecretGuard.resetSuppression();
        LogSecretGuard.useClock(null);
        MDC.clear();
    }

    @Test
    void 허용_키만_남고_본문의_민감값_모양과_식별자는_가려지며_일반_로그는_신호가_아니다(CapturedOutput output)
            throws Exception {
        String marker = "redaction-probe-" + "q".repeat(3);
        String email = "probe.user" + "@" + "example.com";
        String token = "q".repeat(12) + "7".repeat(4) + "w".repeat(8);
        String wallet = "0x" + "a".repeat(40);
        String userId = UUID.randomUUID().toString();
        String amount = "1" + "2".repeat(6);
        MDC.put("sessionId", "mdc-" + userId);
        MDC.put("kind", "probe_kind");
        PROBE.atInfo().addKeyValue("code", "PROBE_CODE").addKeyValue("userId", userId)
                .addKeyValue("correlationId", "probe-correlation").addKeyValue("count", 3)
                .addKeyValue("durationMs", "not-a-number")
                .log("{} email {} token={} wallet={} user={} amount={} bearer={}\nsecond line", marker, email, token,
                        wallet, userId, amount, "Bearer " + token);
        MDC.clear();

        Map<String, Object> record = recordWith(output, marker);
        assertThat(record.keySet()).isSubsetOf(ALLOWED_KEYS)
                .contains("ts", "level", "logger", "event", "code", "kind", "correlationId", "count")
                .doesNotContain("durationMs");
        assertThat(record).containsEntry("level", "INFO").containsEntry("code", "PROBE_CODE")
                .containsEntry("kind", "probe_kind").containsEntry("correlationId", "probe-correlation")
                .containsEntry("count", "3");
        String event = (String) record.get("event");
        assertThat(event).contains("email [REDACTED:EMAIL]").contains("token=[REDACTED:SECRET]")
                .contains("wallet=[REDACTED:WALLET]").contains("user=[REDACTED:ID]")
                .contains("amount=[REDACTED:DIGITS]").contains("bearer=[REDACTED:SECRET]")
                .contains("\nsecond line");
        // 한 줄이다: 본문의 줄바꿈은 이스케이프된다.
        assertThat(output.getAll().lines().filter(line -> line.contains(marker)).toList()).hasSize(1);
        String line = output.getAll().lines().filter(each -> each.contains(marker)).findFirst().orElseThrow();
        assertThat(UUID_TEXT.matcher(line).find()).as(line).isFalse();
        assertThat(EMAIL.matcher(line).find()).as(line).isFalse();
        assertThat(LONG_DIGITS.matcher(line).find()).as(line).isFalse();
        assertThat(line).doesNotContain(token).doesNotContain(wallet).doesNotContain("sessionId")
                .doesNotContain("userId");

        // 패턴 탐지는 가리기만 한다: 신호도 제한도 없다.
        Thread.sleep(QUIET_WINDOW);
        assertThat(signalOperations()).isZero();
    }

    @Test
    void 구성_비밀값이_나오면_그_줄을_가리고_신호_하나를_올리며_60초_안의_반복은_신호를_더하지_않는다(CapturedOutput output)
            throws Exception {
        String secret = OidcStubConfiguration.CLIENT_SECRET;
        assertThat(secret.length()).isGreaterThanOrEqualTo(LogSecretGuard.MIN_SECRET_CHARS);
        AtomicLong clock = new AtomicLong(0);
        LogSecretGuard.useClock(clock::get);

        PROBE.warn("probe configured secret {}", secret);
        awaitSignalOperations(1);
        Map<String, Object> record = recordWith(output, "[REDACTED:CONFIGURED_SECRET]");
        assertThat(record).containsOnlyKeys("ts", "level", "logger", "event")
                .containsEntry("event", RedactingJsonEncoder.CONFIGURED_SECRET_MARK);
        assertThat(output.getAll()).doesNotContain(secret);
        // 판정표 OPS-SIGNAL-1.0의 SECRET_EXPOSED: 신규 연결·동기화·계산 표시 전역, 신호 하나(operation 하나).
        assertThat(jdbc().sql("SELECT restriction_scope FROM operations_signal WHERE signal_type = 'SECRET_EXPOSED' "
                + "ORDER BY restriction_scope").query(String.class).list())
                .containsExactly("CALCULATION_DISPLAY:GLOBAL", "NEW_CONNECTION:GLOBAL", "SYNC:GLOBAL");
        assertThat(jdbc().sql("SELECT DISTINCT evidence->>'ruleId' FROM operations_signal").query(String.class).list())
                .containsExactly(LogSecretGuard.RULE_ID);
        assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction WHERE origin = 'SIGNAL' "
                + "AND effective_until IS NULL").query(Integer.class).single()).isEqualTo(3);

        // 60초 안의 반복(메시지·예외·버리는 구조 인자 어디든): 줄은 가려지고 신호는 하나 그대로다.
        clock.set(Duration.ofSeconds(59).toNanos());
        PROBE.atError().addKeyValue("detail", secret).log("probe repeated structured");
        PROBE.error("probe repeated exception", new IllegalStateException("cause " + secret));
        Thread.sleep(QUIET_WINDOW);
        assertThat(signalOperations()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(secret).doesNotContain("probe repeated");

        // 창이 지나면 다시 올린다(같은 대상의 활성 제한에 연결된다).
        clock.set(Duration.ofSeconds(61).toNanos());
        PROBE.info("probe after window {}", secret);
        awaitSignalOperations(2);
        assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction WHERE origin = 'SIGNAL'")
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal WHERE outcome = 'LINKED'").query(Integer.class)
                .single()).isEqualTo(3);
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void encoder는_예외_스택과_메시지를_내보내지_않고_짧은_값은_비밀로_등록하지_않는다() {
        LoggingEvent event = new LoggingEvent("com.bokrilog.LogRedactionProbe",
                ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("com.bokrilog.LogRedactionProbe"),
                Level.ERROR, "probe failure", new IllegalStateException("inner detail"), null);
        String rendered = RedactingJsonEncoder.render(event);
        // 예외는 클래스의 단순 이름 하나뿐이다(Story 3.7 진단 키 exception). 메시지·패키지·스택은 없다.
        assertThat(rendered).doesNotContain("inner detail").doesNotContain("java.lang.IllegalStateException")
                .doesNotContain("at com.bokrilog").doesNotContain("\n");
        assertThat(rendered).contains("\"event\":\"probe failure\"")
                .contains("\"exception\":\"IllegalStateException\"");
        assertThat(LogSecretGuard.register(List.of("short", ""), ruleId -> {})).isNull();
    }

    @Test
    void code류에_UUID_모양이_오면_키째_버리고_exception은_단순_이름_모양만_받는다(CapturedOutput output) {
        String marker = "redaction-keys-" + "z".repeat(4);
        PROBE.atInfo().addKeyValue("code", UUID.randomUUID().toString()).addKeyValue("reason", "PROBE_REASON")
                .addKeyValue("kind", "k-" + UUID.randomUUID()).addKeyValue("exception", "not a class name")
                .log(marker);
        Map<String, Object> record = LogLines.records(output.getAll()).stream()
                .filter(each -> marker.equals(each.get("event"))).findFirst().orElseThrow();
        assertThat(record).containsEntry("reason", "PROBE_REASON").doesNotContainKeys("code", "kind", "exception");
    }

    @Test
    void 처리하지_못한_스레드_예외는_스택_없이_한_줄이다(CapturedOutput output) throws Exception {
        String detail = "uncaught-detail-" + "x".repeat(6);
        Thread thread = Thread.ofVirtual().start(() -> {
            throw new IllegalStateException(detail);
        });
        thread.join();
        List<Map<String, Object>> found = pollUntil(Duration.ofSeconds(5), () -> LogLines.records(output.getAll())
                .stream().filter(each -> "처리하지 못한 스레드 예외".equals(each.get("event"))).toList(),
                records -> !records.isEmpty());
        assertThat(found).anySatisfy(record -> assertThat(record).containsEntry("exception", "IllegalStateException"));
        assertThat(output.getAll()).doesNotContain(detail).doesNotContain("at java.").doesNotContain("Exception in thread");
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> recordWith(CapturedOutput output, String marker) throws Exception {
        List<Map<String, Object>> found = pollUntil(Duration.ofSeconds(5), () -> LogLines.records(output.getAll())
                .stream().filter(record -> String.valueOf(record.get("event")).contains(marker)).toList(),
                records -> !records.isEmpty());
        assertThat(found).as(marker).isNotEmpty();
        return found.getFirst();
    }

    private int signalOperations() {
        return jdbc().sql("SELECT count(DISTINCT operation_id) FROM operations_signal WHERE signal_type = "
                + "'SECRET_EXPOSED'").query(Integer.class).single();
    }

    private void awaitSignalOperations(int expected) throws InterruptedException {
        int operations = pollUntil(Duration.ofSeconds(10), this::signalOperations, count -> count >= expected);
        assertThat(operations).isEqualTo(expected);
    }
}
