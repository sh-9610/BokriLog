package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.SupportInquiryApi;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Story 3.6: 사용자 문의를 접수하고 영업일 3일 답변 목표를 추적한다 — SPEC I/O 매트릭스 전 행.
 *
 * <p>사용자는 {@code POST /api/support/inquiries}로 접수하고 {@code GET}으로 내 문의를 본다. 운영자는 운영 command
 * {@code update-inquiry}(3.1 envelope·재인증·허가·멱등 키·세션 회전·감사)로 상태·답변을 기록하고, 보호 표면의
 * {@code /api/operator/inquiries}로 목록·상세·집계를 본다. 달력 계산의 경계 시각은 {@code BusinessDayCalendarTest}가 고정
 * fixture로 보고, 여기서는 접수가 그 계산을 DB 시각으로 한 INSERT에 쓰는지를 본다. 시각이 필요한 행(달성률·보존)은 행 시각을
 * 옮겨 만든다.
 *
 * <p>자격정보·지갑 모양 값은 실행 시점에 만든다 — 리터럴로 적으면 gitleaks가 잡는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class SupportInquiryTest extends OperatorTestBase {

    private static final String INQUIRIES = SupportInquiryController.INQUIRIES_PATH;

    private static final String UPDATE = OperatorCommandApi.UPDATE_INQUIRY;

    private static final Pattern REFERENCE = Pattern.compile("Q-[A-Z2-7]{10}");

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    @Autowired
    private OperationsDeletionApi deletion;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    // ------------------------------------------------------------------ 접수

    @Test
    void 접수는_201이고_한_행에_reference_목표_달력_시간대_상태를_쓴다() throws Exception {
        Signed user = signedIn("inquiry-receive");
        String key = key("inquiry-receive");

        var created = submit(user, draft("SYNC_PLAN", "동기화가 멈췄어요", "어제부터 대시보드가 갱신되지 않습니다.", "IN_APP",
                null), key);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("no-store"));
        Map<String, Object> receipt = topLevelFieldsOf(created.body());
        assertThat(receipt).containsOnlyKeys("reference", "receivedAt", "targetAt", "targetState", "status")
                .containsEntry("status", "RECEIVED");
        String reference = (String) receipt.get("reference");
        assertThat(reference).matches(REFERENCE);
        Instant receivedAt = Instant.parse((String) receipt.get("receivedAt"));
        Instant expected = expectedTarget(receivedAt);
        if (expected == null) {
            assertThat(receipt).containsEntry("targetState", "CALENDAR_PENDING").containsEntry("targetAt", null);
        } else {
            assertThat(receipt).containsEntry("targetState", "SET");
            assertThat(Instant.parse((String) receipt.get("targetAt"))).isEqualTo(expected);
        }

        // 한 행: 접수 시각·목표·달력 버전·시간대·상태가 같은 INSERT에 있다.
        assertThat(jdbc().sql("SELECT calendar_version || '|' || timezone || '|' || status || '|' || target_state "
                        + "|| '|' || user_id FROM support_inquiry WHERE reference = :r").param("r", reference)
                .query(String.class).single())
                .isEqualTo(SupportInquiryApi.CALENDAR_VERSION + "|Asia/Seoul|RECEIVED|"
                        + (expected == null ? "CALENDAR_PENDING" : "SET") + "|" + user.user());
        assertThat(jdbc().sql("SELECT received_at FROM support_inquiry WHERE reference = :r").param("r", reference)
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(receivedAt);

        // 내 목록에 보인다.
        var mine = get(INQUIRIES, user.cookie());
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(mine.body())).containsEntry("supportContact",
                OperatorTestProperties.SUPPORT_CONTACT);
        assertThat(itemsIn(mine.body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("reference", reference).containsEntry("category", "SYNC_PLAN")
                .containsEntry("title", "동기화가 멈췄어요").containsEntry("status", "RECEIVED")
                .containsEntry("answer", null).containsEntry("answeredAt", null)
                .containsEntry("description", "어제부터 대시보드가 갱신되지 않습니다."));
    }

    @Test
    void CSRF_없음은_403_키_없음과_필드_오류는_422이고_아무것도_쓰지_않는다() throws Exception {
        Signed user = signedIn("inquiry-guard");
        String body = draft("ACCOUNT", "로그인 문의", "로그인이 안 됩니다.", "IN_APP", null);

        var noCsrf = sendJson("POST", INQUIRIES, body, user.cookie(), headers(null, key("inquiry-csrf")));
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        var noKey = submit(user, body, null);
        assertRefused(noKey, 422, "IDEMPOTENCY_KEY_REQUIRED");

        assertField(submit(user, draft("BILLING", "제목", "내용", "IN_APP", null), key("inquiry-f1")), "category");
        assertField(submit(user, draft("ACCOUNT", "가".repeat(81), "내용", "IN_APP", null), key("inquiry-f2")),
                "title");
        assertField(submit(user, draft("ACCOUNT", " ", "내용", "IN_APP", null), key("inquiry-f3")), "title");
        assertField(submit(user, draft("ACCOUNT", "제목", "가".repeat(2001), "IN_APP", null), key("inquiry-f4")),
                "description");
        assertField(submit(user, draft("ACCOUNT", "제목", "내용", "PHONE", null), key("inquiry-f5")), "contact");
        assertField(submit(user, draft("ACCOUNT", "제목", "내용", "EMAIL", null), key("inquiry-f6")), "replyEmail");
        assertField(submit(user, draft("ACCOUNT", "제목", "내용", "EMAIL", "not-an-email"), key("inquiry-f7")),
                "replyEmail");
        // 서비스 안 회신에 이메일을 함께 보내면 받지 않는다 — 쓰지 않을 개인정보를 남기지 않는다.
        assertField(submit(user, draft("ACCOUNT", "제목", "내용", "IN_APP", "me@example.test"), key("inquiry-f8")),
                "replyEmail");
        var malformed = sendJson("POST", INQUIRIES, "{\"title\":3}", user.cookie(),
                headers(user.csrf(), key("inquiry-shape")));
        assertThat(malformed.statusCode()).isEqualTo(400);

        assertThat(inquiryCount()).isZero();
    }

    @Test
    void 민감값은_422이고_응답과_로그에는_규칙_code만_있고_원문이_없다(CapturedOutput output) throws Exception {
        Signed user = signedIn("inquiry-sensitive");
        String token = "Zq9".repeat(7);
        String wallet = "0x" + "c4".repeat(20);
        Map<String, List<String>> cases = new LinkedHashMap<>();
        cases.put("SECRET", List.of("title", "키 " + token));
        cases.put("WALLET", List.of("description", "입금 주소는 " + wallet + " 입니다"));
        cases.put("FINANCIAL", List.of("description", "잔고가 12,345,678 만큼 차이 납니다"));
        cases.put("EMAIL", List.of("title", "연락 hidden-mail@example.test"));

        for (var entry : cases.entrySet()) {
            String field = entry.getValue().get(0);
            String raw = entry.getValue().get(1);
            String body = "title".equals(field) ? draft("OTHER", raw, "내용입니다", "IN_APP", null)
                    : draft("OTHER", "제목입니다", raw, "IN_APP", null);
            var refused = submit(user, body, key("inquiry-rule"));
            assertRefused(refused, 422, "INQUIRY_SENSITIVE_CONTENT");
            assertThat(topLevelFieldsOf(refused.body())).containsEntry("rule", entry.getKey())
                    .containsEntry("field", field)
                    .containsOnlyKeys("code", "message", "correlationId", "field", "rule");
            assertThat(refused.body()).doesNotContain(raw);
            assertThat(output.getOut() + output.getErr()).contains("rule=" + entry.getKey());
        }
        assertThat(inquiryCount()).isZero();
        String logs = output.getOut() + output.getErr();
        assertThat(logs).doesNotContain(token).doesNotContain(wallet).doesNotContain("12,345,678")
                .doesNotContain("hidden-mail");
    }

    @Test
    void 같은_키는_같은_reference이고_같은_키_다른_입력은_409다() throws Exception {
        Signed user = signedIn("inquiry-idem");
        String key = key("inquiry-idem");
        String body = draft("CONNECTION", "연결 문의", "연결 상태를 확인하고 싶어요.", "IN_APP", null);
        var first = submit(user, body, key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var again = submit(user, body, key);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(again.body())).isEqualTo(topLevelFieldsOf(first.body()));
        var other = submit(user, draft("CONNECTION", "연결 문의", "다른 내용입니다.", "IN_APP", null), key);
        assertRefused(other, 409, "IDEMPOTENCY_CONFLICT");
        assertThat(inquiryCount()).isEqualTo(1);

        // 같은 키라도 다른 사용자의 것은 별개다 — 멱등의 권위는 (user_id, 키 digest)다.
        Signed another = signedIn("inquiry-idem-other");
        var theirs = submit(another, body, key);
        assertThat(theirs.statusCode()).isEqualTo(201);
        assertThat(topLevelFieldsOf(theirs.body()).get("reference"))
                .isNotEqualTo(topLevelFieldsOf(first.body()).get("reference"));
    }

    @Test
    void 최근_24시간_안_6번째는_429이고_행이_없으며_창이_지나면_다시_받는다() throws Exception {
        Signed user = signedIn("inquiry-rate");
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < SupportInquiryApi.DAILY_LIMIT; index++) {
            String key = key("inquiry-rate");
            keys.add(key);
            var accepted = submit(user, draft("OTHER", "문의 " + index, "내용 " + index, "IN_APP", null), key);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        }
        var sixth = submit(user, draft("OTHER", "문의 여섯", "내용 여섯", "IN_APP", null), key("inquiry-rate"));
        assertRefused(sixth, 429, "INQUIRY_RATE_LIMITED");
        assertThat(inquiryCount()).isEqualTo(SupportInquiryApi.DAILY_LIMIT);
        // 앞선 접수의 재전송은 상한과 무관하게 같은 결과다.
        var replay = submit(user, draft("OTHER", "문의 0", "내용 0", "IN_APP", null), keys.getFirst());
        assertThat(replay.statusCode()).isEqualTo(200);

        // 가장 오래된 접수를 25시간 앞으로 옮기면(DB 시각 기준 창 밖) 한 건을 더 받는다.
        jdbc().sql("UPDATE support_inquiry SET received_at = received_at - interval '25 hours', target_at = NULL, "
                        + "target_state = 'CALENDAR_PENDING' WHERE reference = (SELECT reference FROM support_inquiry "
                        + "WHERE user_id = :u ORDER BY received_at LIMIT 1)")
                .param("u", user.user()).update();
        var later = submit(user, draft("OTHER", "문의 여섯", "내용 여섯", "IN_APP", null), key("inquiry-rate"));
        assertThat(later.statusCode()).as(later.body()).isEqualTo(201);
    }

    @Test
    void 달력이_그_연도를_담지_않으면_접수는_되고_답변_목표는_미정이다() throws Exception {
        Signed user = signedIn("inquiry-pending");
        var years = jdbc().sql("SELECT year FROM support_calendar_year WHERE calendar_version = :v")
                .param("v", SupportInquiryApi.CALENDAR_VERSION).query(Integer.class).list();
        var holidays = jdbc().sql("SELECT year || '|' || holiday_date || '|' || name FROM support_business_calendar "
                + "WHERE calendar_version = :v").param("v", SupportInquiryApi.CALENDAR_VERSION).query(String.class).list();
        assertThat(years).containsExactlyInAnyOrder(2026, 2027);
        try {
            // 달력 버전이 담은 연도를 잠시 비운다 — 다음 해 자료가 없는 날과 같다.
            jdbc().sql("DELETE FROM support_business_calendar WHERE calendar_version = :v")
                    .param("v", SupportInquiryApi.CALENDAR_VERSION).update();
            jdbc().sql("DELETE FROM support_calendar_year WHERE calendar_version = :v")
                    .param("v", SupportInquiryApi.CALENDAR_VERSION).update();

            var created = submit(user, draft("OTHER", "목표 미정 문의", "달력이 없는 해의 접수", "IN_APP", null),
                    key("inquiry-pending"));
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            assertThat(topLevelFieldsOf(created.body())).containsEntry("targetAt", null)
                    .containsEntry("targetState", "CALENDAR_PENDING").containsEntry("status", "RECEIVED");
            assertThat(itemsIn(get(INQUIRIES, user.cookie()).body(), "items")).singleElement()
                    .satisfies(item -> assertThat(item).containsEntry("targetAt", null)
                            .containsEntry("targetState", "CALENDAR_PENDING"));
        } finally {
            for (Integer year : years) {
                jdbc().sql("INSERT INTO support_calendar_year (calendar_version, year) VALUES (:v, :y)")
                        .param("v", SupportInquiryApi.CALENDAR_VERSION).param("y", year).update();
            }
            for (String row : holidays) {
                String[] parts = row.split("\\|", 3);
                jdbc().sql("INSERT INTO support_business_calendar (calendar_version, year, holiday_date, name) "
                                + "VALUES (:v, :y, CAST(:d AS date), :n)")
                        .param("v", SupportInquiryApi.CALENDAR_VERSION).param("y", Integer.parseInt(parts[0]))
                        .param("d", parts[1]).param("n", parts[2]).update();
            }
        }
        assertThat(jdbc().sql("SELECT count(*) FROM support_business_calendar").query(Long.class).single())
                .isEqualTo(holidays.size());
    }

    /**
     * 첫 답변 목표 계측(Story 3.7): 접수 때 목표가 미정이었어도 답변 전에 달력이 그 연도를 담게 됐으면 답변 확정이 목표를 먼저
     * 채운다 — 첫 답변이 {@code pending}이 아니라 목표 달성 여부로 세진다. 대기 gauge는 첫 답변을 기다리는 문의 수다.
     */
    @Test
    void 미정_목표는_답변_전에_채워져_달성_여부로_세지고_대기_gauge는_대기_문의_수다() throws Exception {
        Signed user = signedIn("inquiry-metric");
        var years = jdbc().sql("SELECT year FROM support_calendar_year WHERE calendar_version = :v")
                .param("v", SupportInquiryApi.CALENDAR_VERSION).query(Integer.class).list();
        var holidays = jdbc().sql("SELECT year || '|' || holiday_date || '|' || name FROM support_business_calendar "
                + "WHERE calendar_version = :v").param("v", SupportInquiryApi.CALENDAR_VERSION).query(String.class).list();
        String pendingReference;
        try {
            jdbc().sql("DELETE FROM support_business_calendar WHERE calendar_version = :v")
                    .param("v", SupportInquiryApi.CALENDAR_VERSION).update();
            jdbc().sql("DELETE FROM support_calendar_year WHERE calendar_version = :v")
                    .param("v", SupportInquiryApi.CALENDAR_VERSION).update();
            pendingReference = reference(submit(user, draft("OTHER", "목표 미정 문의", "계측 확인", "IN_APP", null),
                    key("inquiry-metric-pending")));
        } finally {
            for (Integer year : years) {
                jdbc().sql("INSERT INTO support_calendar_year (calendar_version, year) VALUES (:v, :y)")
                        .param("v", SupportInquiryApi.CALENDAR_VERSION).param("y", year).update();
            }
            for (String row : holidays) {
                String[] parts = row.split("\\|", 3);
                jdbc().sql("INSERT INTO support_business_calendar (calendar_version, year, holiday_date, name) "
                                + "VALUES (:v, :y, CAST(:d AS date), :n)")
                        .param("v", SupportInquiryApi.CALENDAR_VERSION).param("y", Integer.parseInt(parts[0]))
                        .param("d", parts[1]).param("n", parts[2]).update();
            }
        }
        reference(submit(user, draft("OTHER", "두 번째 문의", "대기 확인", "IN_APP", null), key("inquiry-metric-second")));
        // 대기 gauge: 첫 답변을 기다리는 문의 둘.
        TelemetryProbe.run(this.context, "supportPendingMetrics", "measurePendingNow");
        assertThat(pendingGauge()).isEqualTo(2.0);

        Map<String, Double> before = Map.of("true", firstAnswers("true"), "false", firstAnswers("false"),
                "pending", firstAnswers("pending"));
        Signed operator = operator("inquiry-metric-operator", "command");
        update(operator, pendingReference, "ANSWERED", "확인했습니다.");
        Map<String, Object> row = jdbc().sql("SELECT target_state, target_met FROM support_inquiry WHERE reference = :r")
                .param("r", pendingReference).query().singleRow();
        assertThat(row).containsEntry("target_state", "SET").containsEntry("target_met", true);
        assertThat(firstAnswers("true")).isEqualTo(before.get("true") + 1);
        assertThat(firstAnswers("pending")).as("미정으로 세지 않는다").isEqualTo(before.get("pending"));
        assertThat(firstAnswers("false")).isEqualTo(before.get("false"));
        TelemetryProbe.run(this.context, "supportPendingMetrics", "measurePendingNow");
        assertThat(pendingGauge()).isEqualTo(1.0);
    }

    private double pendingGauge() {
        var gauge = this.meters.find("bokrilog.support.pending.target").gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    /**
     * 로그·응답에서 원문을 찾을 표지. 영문 소문자만 쓴다 — UUID 앞자리는 숫자만으로 이어질 수 있어 민감값의 금융값
     * 규칙(구분자 포함 숫자 6자리 이상)에 걸리고, 그러면 시험이 실행마다 다르게 끝난다.
     */
    private static String letterMarker() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder marker = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            marker.append((char) ('a' + random.nextInt(26)));
        }
        return marker.toString();
    }

    // ------------------------------------------------------------------ 운영 처리

    @Test
    void 운영자가_답변하면_첫_답변_시각과_달성_여부를_쓰고_사용자_목록에_답변이_보인다(CapturedOutput output)
            throws Exception {
        Signed user = signedIn("inquiry-answer-user");
        String title = "결제 화면 질문 " + letterMarker();
        String description = "본문 표지 " + letterMarker() + "\n둘째 줄";
        String email = "reply-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String answer = "답변 표지 " + letterMarker() + " 확인 후 조치했습니다.";
        var created = submit(user, draft("ACCOUNT", title, description, "EMAIL", email), key("inquiry-answer"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String reference = (String) topLevelFieldsOf(created.body()).get("reference");

        Signed operator = operator("inquiry-operator", "command");
        // 최소화한 목록: 본문·답변이 없고 이메일은 마스킹이다.
        var list = get(OperatorController.INQUIRIES_PATH, operator.cookie());
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(itemsIn(list.body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("reference", reference).containsEntry("title", title)
                .containsEntry("category", "ACCOUNT").containsEntry("status", "RECEIVED")
                .containsEntry("contact", "EMAIL").containsEntry("replyEmail", "r***@e***")
                .doesNotContainKeys("description", "answer", "userId"));
        assertThat(list.body()).doesNotContain(email).doesNotContain("본문 표지").doesNotContain(user.user().toString());
        // 상세에서만 본문과 회신 이메일 원문이 보인다(운영자가 자기 메일로 회신하는 용도). 요약은 여전히 마스킹이다.
        var detail = get(OperatorController.INQUIRIES_PATH + "/" + reference, operator.cookie());
        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(detail.body())).containsEntry("description", description)
                .containsEntry("replyEmail", email);
        assertThat(mapOf(topLevelFieldsOf(detail.body()).get("summary"))).containsEntry("replyEmail", "r***@e***");
        assertThat(get(OperatorController.INQUIRIES_PATH + "/Q-AAAAAAAAAA", operator.cookie()).statusCode())
                .isEqualTo(404);

        // 처리 중으로.
        String key = key("inquiry-progress");
        var prepared = prepareUpdate(operator, reference, "IN_PROGRESS", null, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        Map<String, Object> fields = topLevelFieldsOf(prepared.body());
        assertThat(fields).containsEntry("command", UPDATE).containsEntry("scope", "INQUIRY:IN_PROGRESS")
                .containsEntry("target", reference).containsEntry("policyVersion", "OPERATOR-COMMAND-1.4")
                .containsEntry("confirmationPhrase", "문의 처리 중 " + reference);
        assertThat(mapOf(fields.get("impact"))).containsEntry("reference", reference)
                .containsEntry("currentStatus", "RECEIVED").containsEntry("nextStatus", "IN_PROGRESS")
                .containsEntry("answerProvided", false);
        String progressOperation = operationIdIn(prepared.body());
        var progressed = confirmRestriction(operator, UPDATE, progressOperation, phraseIn(prepared.body()), key);
        assertThat(progressed.statusCode()).as(progressed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(progressed.body())).containsEntry("outcome", "COMPLETED")
                .containsEntry("status", "IN_PROGRESS").containsKey("transitionedAt").doesNotContainKey("reference");
        assertThat(sessionCookieOf(progressed)).as("세션이 회전한다").isPresent();
        Signed rotated = after(operator, progressed);
        // 같은 키의 재전송: 저장된 결과, 회전 없음.
        var replayed = confirmRestriction(rotated, UPDATE, progressOperation, phraseIn(prepared.body()), key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replayed.body()).get("transitionedAt"))
                .isEqualTo(topLevelFieldsOf(progressed.body()).get("transitionedAt"));
        assertThat(sessionCookieOf(replayed)).isEmpty();
        Signed again = reauthenticated(rotated);

        // 답변 없는 답변 완료는 422.
        assertRefused(prepareUpdate(again, reference, "ANSWERED", null, key("inquiry-no-answer")), 422,
                "INQUIRY_ANSWER_REQUIRED");
        // 답변 완료.
        String answerKey = key("inquiry-answered");
        var answering = prepareUpdate(again, reference, "ANSWERED", answer, answerKey);
        assertThat(answering.statusCode()).as(answering.body()).isEqualTo(200);
        assertThat(answering.body()).doesNotContain("답변 표지");
        assertThat(phraseIn(answering.body())).isEqualTo("문의 답변 완료 " + reference);
        String answerOperation = operationIdIn(answering.body());
        Map<String, Double> metBefore = Map.of("true", firstAnswers("true"), "false", firstAnswers("false"),
                "pending", firstAnswers("pending"));
        var answered = confirmRestriction(again, UPDATE, answerOperation, phraseIn(answering.body()), answerKey);
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(answered.body())).containsEntry("status", "ANSWERED");
        assertThat(answered.body()).doesNotContain("답변 표지");
        Instant transitionedAt = Instant.parse((String) topLevelFieldsOf(answered.body()).get("transitionedAt"));

        Map<String, Object> row = jdbc().sql("SELECT status, answer, first_answered_at, target_at, target_met, "
                + "last_operation_id FROM support_inquiry WHERE reference = :r").param("r", reference).query()
                .singleRow();
        assertThat(row).containsEntry("status", "ANSWERED").containsEntry("answer", answer)
                .containsEntry("last_operation_id", answerOperation);
        Instant firstAnswered = ((java.sql.Timestamp) row.get("first_answered_at")).toInstant();
        assertThat(firstAnswered).isEqualTo(transitionedAt);
        Object target = row.get("target_at");
        // 첫 답변 목표 계측(Story 3.7): 첫 답변 하나가 목표 달성 여부(미정이면 pending)로 한 번 세진다.
        String met = target == null ? "pending"
                : Boolean.toString(!firstAnswered.isAfter(((java.sql.Timestamp) target).toInstant()));
        for (String each : metBefore.keySet()) {
            assertThat(firstAnswers(each)).as(each).isEqualTo(metBefore.get(each) + (each.equals(met) ? 1 : 0));
        }
        if (target == null) {
            assertThat(row.get("target_met")).isNull();
        } else {
            assertThat(row.get("target_met"))
                    .isEqualTo(!firstAnswered.isAfter(((java.sql.Timestamp) target).toInstant()));
        }

        // 사용자 목록에 답변과 답변 시각.
        assertThat(itemsIn(get(INQUIRIES, user.cookie()).body(), "items")).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("status", "ANSWERED").containsEntry("answer", answer)
                        .containsEntry("answeredAt", transitionedAt.toString()));

        // 역행은 409(재인증 판정 뒤의 상태 거절).
        Signed third = reauthenticated(after(again, answered));
        assertRefused(prepareUpdate(third, reference, "IN_PROGRESS", null, key("inquiry-back")), 409,
                "INQUIRY_TRANSITION_INVALID");
        assertRefused(prepareUpdate(third, reference, "ANSWERED", "다시 답변", key("inquiry-twice")), 409,
                "INQUIRY_TRANSITION_INVALID");

        // 감사: 완료 행의 details는 상태·reference digest·전이 시각(과 키 digest)뿐이고, 원장 어디에도 원문이 없다.
        for (String operation : List.of(progressOperation, answerOperation)) {
            assertThat(outcomesOf(operation)).containsExactly("REQUESTED", "COMPLETED");
            Map<String, Object> details = ledgerDetails(operation, "COMPLETED");
            assertThat(details.keySet()).containsExactlyInAnyOrder("keyDigest", "status", "referenceDigest",
                    "transitionedAt");
            assertThat((String) details.get("referenceDigest")).matches("[0-9a-f]{64}");
        }
        assertThat(jdbc().sql("SELECT DISTINCT target FROM operations_audit_event WHERE command = :c")
                .param("c", UPDATE).query(String.class).list()).allSatisfy(value -> assertThat(value)
                        .matches("[0-9a-f]{64}|-"));
        assertLedgerHasNo(reference);
        assertLedgerHasNo("결제 화면 질문");
        assertLedgerHasNo("본문 표지");
        assertLedgerHasNo("답변 표지");
        assertLedgerHasNo(email);

        // 로그: 제목·본문·회신 이메일·답변·reference가 0건이다.
        String logs = output.getOut() + output.getErr();
        assertThat(logs).doesNotContain(title).doesNotContain("본문 표지").doesNotContain(email)
                .doesNotContain("답변 표지").doesNotContain(reference);
        assertThat(logs).contains("operator command completed: command=update-inquiry status=ANSWERED");
    }

    @Test
    void 답변_완료를_건너뛴_해결은_409이고_답변은_답변_완료에만_싣고_민감값_답변은_거절한다() throws Exception {
        Signed user = signedIn("inquiry-skip");
        var created = submit(user, draft("OTHER", "건너뛰기 문의", "내용입니다", "IN_APP", null), key("inquiry-skip"));
        String reference = (String) topLevelFieldsOf(created.body()).get("reference");
        Signed operator = operator("inquiry-skip-operator", "command");

        assertRefused(prepareUpdate(operator, reference, "RESOLVED", null, key("inquiry-skip-resolve")), 409,
                "INQUIRY_TRANSITION_INVALID");
        assertRefused(prepareUpdate(operator, reference, "IN_PROGRESS", "미리 쓴 답변", key("inquiry-early")), 422,
                "INQUIRY_ANSWER_INVALID");
        assertRefused(prepareUpdate(operator, reference, "ANSWERED", "가".repeat(1001), key("inquiry-long")), 422,
                "INQUIRY_ANSWER_INVALID");
        String wallet = "0x" + "9e".repeat(20);
        assertRefused(prepareUpdate(operator, reference, "ANSWERED", "주소 " + wallet, key("inquiry-wallet")), 422,
                "INQUIRY_SENSITIVE_CONTENT");
        assertRefused(prepareUpdate(operator, reference, "RECEIVED", null, key("inquiry-received")), 422,
                "UNSUPPORTED_SCOPE");
        assertRefused(prepareUpdate(operator, "Q-AAAAAAAAAA", "IN_PROGRESS", null, key("inquiry-missing")), 422,
                "TARGET_INVALID");
        assertRefused(prepareUpdate(operator, "not-a-reference", "IN_PROGRESS", null, key("inquiry-shape")), 422,
                "TARGET_INVALID");
        assertLedgerHasNo(wallet);
        assertLedgerHasNo(reference);
        assertThat(jdbc().sql("SELECT status FROM support_inquiry WHERE reference = :r").param("r", reference)
                .query(String.class).single()).isEqualTo("RECEIVED");

        // 답변 완료 → 해결은 받는다.
        Signed answered = update(operator, reference, "ANSWERED", "확인했습니다.");
        update(answered, reference, "RESOLVED", null);
        assertThat(jdbc().sql("SELECT status || '|' || (resolved_at IS NOT NULL) FROM support_inquiry "
                + "WHERE reference = :r").param("r", reference).query(String.class).single()).isEqualTo("RESOLVED|true");
    }

    @Test
    void 준비_뒤_다른_처리가_상태를_옮기면_확정은_409이고_아무것도_쓰지_않는다() throws Exception {
        Signed user = signedIn("inquiry-race");
        var created = submit(user, draft("OTHER", "경합 문의", "내용입니다", "IN_APP", null), key("inquiry-race"));
        String reference = (String) topLevelFieldsOf(created.body()).get("reference");
        Signed operator = operator("inquiry-race-operator", "command");
        String key = key("inquiry-race-progress");
        var prepared = prepareUpdate(operator, reference, "IN_PROGRESS", null, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        // 준비 뒤 상태가 바뀌었다(다른 처리의 결과를 흉내 낸다).
        jdbc().sql("UPDATE support_inquiry SET status = 'IN_PROGRESS' WHERE reference = :r").param("r", reference)
                .update();
        var confirmed = confirmRestriction(operator, UPDATE, operationIdIn(prepared.body()), phraseIn(prepared.body()),
                key);
        assertRefused(confirmed, 409, "INQUIRY_TRANSITION_INVALID");
        assertThat(outcomesOf(operationIdIn(prepared.body()))).containsExactly("REQUESTED", "DENIED");
        assertThat(jdbc().sql("SELECT count(*) FROM support_inquiry WHERE reference = :r AND last_operation_id IS NULL")
                .param("r", reference).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 집계

    @Test
    void 기간_안_목표가_지난_넷_가운데_셋이_목표_안_답변이면_75퍼센트이고_미정은_따로_센다() throws Exception {
        Signed user = signedIn("inquiry-metrics");
        List<String> references = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            var created = submit(user, draft("OTHER", "집계 " + index, "내용 " + index, "IN_APP", null),
                    key("inquiry-metrics"));
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            references.add((String) topLevelFieldsOf(created.body()).get("reference"));
        }
        // 2026-09-07(월) 10:00 KST 접수, 목표 2026-09-10(목) 18:00 KST.
        Instant received = Instant.parse("2026-09-07T01:00:00Z");
        Instant target = Instant.parse("2026-09-10T09:00:00Z");
        answeredAt(references.get(0), received, target, Instant.parse("2026-09-08T02:00:00Z"));
        answeredAt(references.get(1), received, target, target);
        answeredAt(references.get(2), received, target, Instant.parse("2026-09-07T01:30:00Z"));
        answeredAt(references.get(3), received, target, null);
        // 목표 미정: 달력이 담지 않은 2028을 지나야 하는 2027-12-30 접수(조회가 채울 수 없다).
        jdbc().sql("UPDATE support_inquiry SET received_at = :r, target_at = NULL, target_state = 'CALENDAR_PENDING' "
                        + "WHERE reference = :ref")
                .param("r", OffsetDateTime.ofInstant(Instant.parse("2027-12-30T01:00:00Z"), ZoneOffset.UTC))
                .param("ref", references.get(4)).update();
        // 기간 안이지만 목표가 아직 오지 않은 미답변 문의 — 분모에 들지 않는다.
        var future = submit(user, draft("OTHER", "집계 미래", "내용 미래", "IN_APP", null), key("inquiry-metrics"));
        assertThat(future.statusCode()).as(future.body()).isEqualTo(201);
        jdbc().sql("UPDATE support_inquiry SET target_at = now() + interval '2 days', target_state = 'SET' "
                + "WHERE reference = :ref").param("ref", topLevelFieldsOf(future.body()).get("reference")).update();

        Signed operator = operator("inquiry-metrics-operator", "command");
        var metrics = get(OperatorController.INQUIRY_METRICS_PATH + "?from=2026-09-01&to=2026-12-31",
                operator.cookie());
        assertThat(metrics.statusCode()).as(metrics.body()).isEqualTo(200);
        Map<String, Object> fields = topLevelFieldsOf(metrics.body());
        assertThat(fields).containsEntry("due", 4).containsEntry("metTarget", 3).containsEntry("calendarPending", 0)
                .containsEntry("measured", 3).containsEntry("calendarVersion", SupportInquiryApi.CALENDAR_VERSION)
                .containsEntry("from", "2026-09-01").containsEntry("to", "2026-12-31")
                .containsEntry("purgeHorizon", null);
        // 달성률·중앙값은 JSON 숫자다(문자열이 아니다).
        var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree(metrics.body());
        assertThat(tree.get("ratePercent").isNumber()).isTrue();
        assertThat(tree.get("medianBusinessMinutes").isNumber()).isTrue();
        // 목표 미정은 분모에서 빼고 접수 기간으로 따로 센다.
        var pending = get(OperatorController.INQUIRY_METRICS_PATH + "?from=2027-12-01&to=2027-12-31",
                operator.cookie());
        assertThat(topLevelFieldsOf(pending.body())).containsEntry("due", 0).containsEntry("calendarPending", 1);
        // 기간이 보존 경계(지금 − 90일)보다 앞서면 그 경계를 싣는다 — 그 이전 해결 문의는 지워져 집계에 없다.
        var early = get(OperatorController.INQUIRY_METRICS_PATH + "?from=2026-01-01&to=2026-12-31",
                operator.cookie());
        Object horizon = topLevelFieldsOf(early.body()).get("purgeHorizon");
        assertThat(horizon).isInstanceOf(String.class);
        assertThat(Instant.parse((String) horizon)).isBetween(Instant.now().minus(91, ChronoUnit.DAYS),
                Instant.now().minus(89, ChronoUnit.DAYS));
        assertThat(new java.math.BigDecimal(fields.get("ratePercent").toString()))
                .isEqualByComparingTo(new java.math.BigDecimal("75.0"));
        // 영업 분: 월 10:00 → 화 11:00 = 600, 월 10:00 → 목 18:00 = 2100, 30분 = 30 → 합 2730, 중앙값 600.
        assertThat(((Number) fields.get("totalBusinessMinutes")).longValue()).isEqualTo(2730);
        assertThat(new java.math.BigDecimal(fields.get("medianBusinessMinutes").toString()))
                .isEqualByComparingTo(new java.math.BigDecimal("600.0"));
        // 달성 "선언" 문장이 없다 — 수만 있다.
        assertThat(fields).doesNotContainKeys("message", "verdict");

        // 다른 기간은 0, 읽을 수 없는 기간은 400.
        var empty = get(OperatorController.INQUIRY_METRICS_PATH + "?from=2026-08-01&to=2026-08-31", operator.cookie());
        assertThat(topLevelFieldsOf(empty.body())).containsEntry("due", 0).containsEntry("ratePercent", null);
        for (String query : List.of("", "?from=2026-09-30&to=2026-09-01", "?from=2026-09-01&to=2027-12-31",
                "?from=yesterday&to=2026-09-01")) {
            assertThat(get(OperatorController.INQUIRY_METRICS_PATH + query, operator.cookie()).statusCode())
                    .as(query).isEqualTo(400);
        }
    }

    // ------------------------------------------------------------------ 보존·삭제

    @Test
    void 해결_뒤_91일이_지난_문의는_다음_조회에서_사라지고_89일은_남는다() throws Exception {
        Signed user = signedIn("inquiry-retention");
        String old = reference(submit(user, draft("OTHER", "오래된 해결", "내용", "IN_APP", null), key("inquiry-r1")));
        String recent = reference(submit(user, draft("OTHER", "최근 해결", "내용", "IN_APP", null), key("inquiry-r2")));
        String open = reference(submit(user, draft("OTHER", "열린 문의", "내용", "IN_APP", null), key("inquiry-r3")));
        resolvedDaysAgo(old, 91);
        resolvedDaysAgo(recent, 89);

        var mine = get(INQUIRIES, user.cookie());
        assertThat(itemsIn(mine.body(), "items")).extracting(item -> item.get("reference"))
                .containsExactlyInAnyOrder(recent, open);
        assertThat(jdbc().sql("SELECT reference FROM support_inquiry").query(String.class).list())
                .containsExactlyInAnyOrder(recent, open);

        // 운영자 조회도 같은 규칙으로 먼저 지운다.
        resolvedDaysAgo(recent, 91);
        Signed operator = operator("inquiry-retention-operator", "command");
        var list = get(OperatorController.INQUIRIES_PATH, operator.cookie());
        assertThat(itemsIn(list.body(), "items")).extracting(item -> item.get("reference")).containsExactly(open);
        assertThat(inquiryCount()).isEqualTo(1);
    }

    @Test
    void eraseInquiries는_그_사용자의_문의와_멱등_기록만_지우고_멱등이다() throws Exception {
        Signed erased = signedIn("inquiry-erased");
        Signed kept = signedIn("inquiry-kept");
        String key = key("inquiry-erase");
        String body = draft("OTHER", "삭제 문의", "내용", "IN_APP", null);
        String first = reference(submit(erased, body, key));
        reference(submit(erased, draft("OTHER", "삭제 문의 둘", "내용", "IN_APP", null), key("inquiry-erase")));
        String theirs = reference(submit(kept, body, key("inquiry-erase")));

        assertThat(this.deletion.eraseInquiries(erased.user())).isEqualTo(2);
        assertThat(jdbc().sql("SELECT count(*) FROM support_inquiry WHERE user_id = :u").param("u", erased.user())
                .query(Long.class).single()).isZero();
        assertThat(jdbc().sql("SELECT reference FROM support_inquiry").query(String.class).list())
                .containsExactly(theirs);
        assertThat(this.deletion.eraseInquiries(erased.user())).isZero();
        assertThat(this.deletion.eraseInquiries(null)).isZero();
        // 멱등 기록도 사라졌다 — 같은 키가 새 접수가 된다.
        var again = submit(erased, body, key);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(reference(again)).isNotEqualTo(first);
    }

    // ------------------------------------------------------------------ 권한

    @Test
    void 남의_문의는_보이지_않고_비운영자와_감사_권한만_가진_운영자의_문의_경로는_404다() throws Exception {
        Signed owner = signedIn("inquiry-owner");
        Signed other = signedIn("inquiry-other");
        String reference = reference(submit(owner, draft("OTHER", "소유자 문의", "내용", "IN_APP", null),
                key("inquiry-owner")));
        assertThat(itemsIn(get(INQUIRIES, other.cookie()).body(), "items")).isEmpty();

        String missing = topLevelFieldsOf(get("/api/operator/nope", other.cookie()).body()).get("code").toString();
        for (String path : List.of(OperatorController.INQUIRIES_PATH,
                OperatorController.INQUIRIES_PATH + "/" + reference,
                OperatorController.INQUIRY_METRICS_PATH + "?from=2026-09-01&to=2026-09-30")) {
            var refused = get(path, other.cookie());
            assertThat(refused.statusCode()).as(path).isEqualTo(404);
            assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId")
                    .containsEntry("code", missing);
        }
        assertThat(prepareUpdate(other, reference, "IN_PROGRESS", null, key("inquiry-nobody")).statusCode())
                .isEqualTo(404);
        Signed auditOnly = operator("inquiry-audit-only", "audit");
        assertThat(get(OperatorController.INQUIRIES_PATH, auditOnly.cookie()).statusCode()).isEqualTo(404);
        assertThat(prepareUpdate(auditOnly, reference, "IN_PROGRESS", null, key("inquiry-audit")).statusCode())
                .isEqualTo(404);
        assertThat(jdbc().sql("SELECT status FROM support_inquiry WHERE reference = :r").param("r", reference)
                .query(String.class).single()).isEqualTo("RECEIVED");

        // 미인증은 401, 삭제가 시작된 계정은 공통 404다.
        assertThat(get(INQUIRIES, null).statusCode()).isEqualTo(401);
        accountDeletion().beginDeletion(owner.user());
        var deleted = get(INQUIRIES, owner.cookie());
        assertThat(deleted.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(deleted.body())).containsOnlyKeys("code", "message", "correlationId");
        var refused = submit(owner, draft("OTHER", "삭제 중", "내용", "IN_APP", null), key("inquiry-deleted"));
        assertThat(refused.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId");
    }

    // ------------------------------------------------------------------ 미정 목표 채우기·운영자 목록

    @Test
    void 달력이_그_연도를_담게_되면_다음_조회가_미정_목표를_채우고_정해진_목표는_다시_계산하지_않는다() throws Exception {
        Signed user = signedIn("inquiry-fill");
        String pending = reference(submit(user, draft("OTHER", "연말 문의", "내용", "IN_APP", null),
                key("inquiry-fill")));
        String fixed = reference(submit(user, draft("OTHER", "정해진 문의", "내용", "IN_APP", null),
                key("inquiry-fill")));
        // 2027-12-30(목) 10:00 KST 접수 — 2028을 지나야 해 목표가 미정이다.
        jdbc().sql("UPDATE support_inquiry SET received_at = :r, target_at = NULL, target_state = 'CALENDAR_PENDING' "
                        + "WHERE reference = :ref")
                .param("r", OffsetDateTime.ofInstant(Instant.parse("2027-12-30T01:00:00Z"), ZoneOffset.UTC))
                .param("ref", pending).update();
        // 이미 정해진 목표(일부러 계산과 다른 값)는 소급 재계산하지 않는다.
        jdbc().sql("UPDATE support_inquiry SET target_at = received_at + interval '30 days' WHERE reference = :ref")
                .param("ref", fixed).update();
        Instant fixedTarget = targetOf(fixed);

        assertThat(itemOf(user, pending)).containsEntry("targetAt", null).containsEntry("targetState", "CALENDAR_PENDING");
        String version = SupportInquiryApi.CALENDAR_VERSION;
        try {
            jdbc().sql("INSERT INTO support_calendar_year (calendar_version, year) VALUES (:v, 2028)")
                    .param("v", version).update();
            jdbc().sql("INSERT INTO support_business_calendar (calendar_version, year, holiday_date, name) "
                    + "VALUES (:v, 2028, DATE '2028-01-01', '신정')").param("v", version).update();
            // 12/31(금) → 2028-01-01 신정·주말 → 1/3(월)·1/4(화) → 2028-01-04 18:00 KST.
            assertThat(itemOf(user, pending)).containsEntry("targetAt", "2028-01-04T09:00:00Z")
                    .containsEntry("targetState", "SET");
            assertThat(jdbc().sql("SELECT calendar_version FROM support_inquiry WHERE reference = :r")
                    .param("r", pending).query(String.class).single()).isEqualTo(version);
            assertThat(targetOf(fixed)).isEqualTo(fixedTarget);
        } finally {
            jdbc().sql("DELETE FROM support_business_calendar WHERE calendar_version = :v AND year = 2028")
                    .param("v", version).update();
            jdbc().sql("DELETE FROM support_calendar_year WHERE calendar_version = :v AND year = 2028")
                    .param("v", version).update();
        }
    }

    @Test
    void 운영자_목록은_미해결이_먼저이고_상태로_거른다() throws Exception {
        Signed user = signedIn("inquiry-order");
        String resolved = reference(submit(user, draft("OTHER", "해결된 문의", "내용", "IN_APP", null),
                key("inquiry-order")));
        String older = reference(submit(user, draft("OTHER", "먼저 온 문의", "내용", "IN_APP", null),
                key("inquiry-order")));
        String newer = reference(submit(user, draft("OTHER", "나중 문의", "내용", "IN_APP", null),
                key("inquiry-order")));
        resolvedDaysAgo(resolved, 1);
        // 해결된 문의를 가장 최근 접수로 옮겨도 미해결 뒤다.
        jdbc().sql("UPDATE support_inquiry SET received_at = now() - interval '1 minute' WHERE reference = :r")
                .param("r", older).update();
        jdbc().sql("UPDATE support_inquiry SET received_at = now() - interval '2 hours' WHERE reference = :r")
                .param("r", newer).update();
        Signed operator = operator("inquiry-order-operator", "command");
        var all = get(OperatorController.INQUIRIES_PATH, operator.cookie());
        assertThat(itemsIn(all.body(), "items")).extracting(item -> item.get("reference"))
                .containsExactly(older, newer, resolved);
        var onlyResolved = get(OperatorController.INQUIRIES_PATH + "?status=RESOLVED", operator.cookie());
        assertThat(itemsIn(onlyResolved.body(), "items")).extracting(item -> item.get("reference"))
                .containsExactly(resolved);
        assertThat(get(OperatorController.INQUIRIES_PATH + "?status=PAUSED", operator.cookie()).statusCode())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ 확정 순간의 대상·동시성

    @Test
    void 준비_뒤_문의가_지워지거나_주인_계정_삭제가_시작되면_확정은_TARGET_INVALID이고_허가를_소비하지_않는다()
            throws Exception {
        Signed user = signedIn("inquiry-vanish");
        String reference = reference(submit(user, draft("OTHER", "사라질 문의", "내용", "IN_APP", null),
                key("inquiry-vanish")));
        Signed operator = operator("inquiry-vanish-operator", "command");
        String key = key("inquiry-vanish-progress");
        var prepared = prepareUpdate(operator, reference, "IN_PROGRESS", null, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(this.deletion.eraseInquiries(user.user())).isEqualTo(1);
        var confirmed = confirmRestriction(operator, UPDATE, operationIdIn(prepared.body()), phraseIn(prepared.body()),
                key);
        assertRefused(confirmed, 422, "TARGET_INVALID");
        assertThat(outcomesOf(operationIdIn(prepared.body()))).containsExactly("REQUESTED", "DENIED");
        assertThat(consumedGrants(operator)).isZero();

        // 주인 계정의 삭제가 시작되면 그 문의에 답변을 쓰지 않는다.
        Signed owner = signedIn("inquiry-deleting");
        String theirs = reference(submit(owner, draft("OTHER", "삭제 중 계정 문의", "내용", "IN_APP", null),
                key("inquiry-deleting")));
        String second = key("inquiry-deleting-progress");
        var again = prepareUpdate(operator, theirs, "IN_PROGRESS", null, second);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        accountDeletion().beginDeletion(owner.user());
        assertRefused(confirmRestriction(operator, UPDATE, operationIdIn(again.body()), phraseIn(again.body()), second),
                422, "TARGET_INVALID");
        assertThat(consumedGrants(operator)).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM support_inquiry WHERE reference = :r AND status = 'RECEIVED'")
                .param("r", theirs).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void 같은_키의_동시_접수는_행_하나이고_다른_키의_동시_접수는_상한을_넘지_않는다() throws Exception {
        Signed user = signedIn("inquiry-race-submit");
        String key = key("inquiry-race-same");
        String body = draft("OTHER", "동시 문의", "내용", "IN_APP", null);
        var same = concurrently(() -> submit(user, body, key), () -> submit(user, body, key));
        assertThat(same).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 200);
        assertThat(same).extracting(response -> topLevelFieldsOf(response.body()).get("reference"))
                .containsOnly(topLevelFieldsOf(same.get(0).body()).get("reference"));
        assertThat(inquiryCount()).isEqualTo(1);

        for (int index = 0; index < SupportInquiryApi.DAILY_LIMIT - 2; index++) {
            assertThat(submit(user, draft("OTHER", "문의 " + index, "내용", "IN_APP", null), key("inquiry-race"))
                    .statusCode()).isEqualTo(201);
        }
        var different = concurrently(
                () -> submit(user, draft("OTHER", "동시 하나", "내용", "IN_APP", null), key("inquiry-race-a")),
                () -> submit(user, draft("OTHER", "동시 둘", "내용", "IN_APP", null), key("inquiry-race-b")));
        assertThat(different).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 429);
        assertThat(inquiryCount()).isEqualTo(SupportInquiryApi.DAILY_LIMIT);
    }

    @Test
    void 같은_문의의_두_운영자_동시_확정은_하나만_전이한다() throws Exception {
        Signed user = signedIn("inquiry-race-op");
        String reference = reference(submit(user, draft("OTHER", "동시 처리 문의", "내용", "IN_APP", null),
                key("inquiry-race-op")));
        Signed first = signedIn("inquiry-race-op-a");
        Signed second = signedIn("inquiry-race-op-b");
        operatorAccess().reinterpret(entry(first.subject(), "command") + ";" + entry(second.subject(), "command"));
        Signed a = reauthenticated(first);
        Signed b = reauthenticated(second);
        String keyA = key("inquiry-race-op-a");
        String keyB = key("inquiry-race-op-b");
        var preparedA = prepareUpdate(a, reference, "IN_PROGRESS", null, keyA);
        var preparedB = prepareUpdate(b, reference, "IN_PROGRESS", null, keyB);
        assertThat(preparedA.statusCode()).as(preparedA.body()).isEqualTo(200);
        assertThat(preparedB.statusCode()).as(preparedB.body()).isEqualTo(200);
        List<HttpResponse<String>> results;
        // 원장 잠금을 쥔 채 두 확정을 보내 둘 다 잠금 앞에 세운 뒤 놓는다 — 그다음은 행 잠금과 전이 재검사가 가른다.
        AutoCloseable held = holdAuditLock();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futureA = executor.submit(() -> confirmRestriction(a, UPDATE, operationIdIn(preparedA.body()),
                    phraseIn(preparedA.body()), keyA));
            var futureB = executor.submit(() -> confirmRestriction(b, UPDATE, operationIdIn(preparedB.body()),
                    phraseIn(preparedB.body()), keyB));
            try {
                awaitAdvisoryWaiters(2);
            } finally {
                held.close();
            }
            results = List.of(futureA.get(), futureB.get());
        }
        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(results).filteredOn(response -> response.statusCode() == 409).singleElement()
                .satisfies(response -> assertThat(topLevelFieldsOf(response.body()))
                        .containsEntry("code", "INQUIRY_TRANSITION_INVALID"));
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE command = :c AND outcome = 'COMPLETED'")
                .param("c", UPDATE).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 소스 스캔

    /** 원장 repository의 UPDATE는 비식별 {@code eraseActor} 한 문장뿐이다(SPEC AC). 문의 처리는 원장을 고치지 않는다. */
    @Test
    void 원장_repository의_UPDATE는_eraseActor_한_문장뿐이다() throws Exception {
        String ledger = Files.readString(Path.of(
                "src/main/java/com/bokrilog/operations/internal/OperatorAuditRepository.java"), StandardCharsets.UTF_8);
        Matcher updates = Pattern.compile("\"UPDATE ").matcher(ledger);
        int count = 0;
        while (updates.find()) {
            count++;
        }
        assertThat(count).isEqualTo(1);
        assertThat(ledger).contains("UPDATE operations_audit_event SET actor_user_id = NULL, ");
        // 문의 표를 문자열로 아는 운영 소스는 문의 repository 하나다.
        List<String> naming = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("FROM support_inquiry") || source.contains("INTO support_inquiry")
                        || source.contains("UPDATE support_inquiry")) {
                    naming.add(file.getFileName().toString());
                }
            }
        }
        assertThat(naming).containsExactly("SupportInquiryRepository.java");
    }

    // ------------------------------------------------------------------ 도구

    private HttpResponse<String> submit(Signed signed, String body, @Nullable String key) throws Exception {
        return sendJson("POST", INQUIRIES, body, signed.cookie(), headers(signed.csrf(), key));
    }

    private static String draft(String category, String title, String description, String contact,
            @Nullable String replyEmail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", category);
        body.put("title", title);
        body.put("description", description);
        body.put("contact", contact);
        if (replyEmail != null) {
            body.put("replyEmail", replyEmail);
        }
        return JSON.writeValueAsString(body);
    }

    private HttpResponse<String> prepareUpdate(Signed signed, String reference, String status,
            @Nullable String answer, String key) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", reference);
        body.put("status", status);
        body.put("reason", "문의 처리 기록");
        if (answer != null) {
            body.put("answer", answer);
        }
        return sendJson("POST", commandPath(UPDATE), JSON.writeValueAsString(body), signed.cookie(),
                headers(signed.csrf(), key));
    }

    /** 준비 → 확정. 둘 다 200이어야 한다. 회전한 세션으로 다시 재인증해 돌려준다. */
    private Signed update(Signed signed, String reference, String status, @Nullable String answer) throws Exception {
        String key = key("inquiry-update");
        var prepared = prepareUpdate(signed, reference, status, answer, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        var confirmed = confirmRestriction(signed, UPDATE, operationIdIn(prepared.body()), phraseIn(prepared.body()),
                key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        return reauthenticated(after(signed, confirmed));
    }

    private static String reference(HttpResponse<String> created) {
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        return (String) topLevelFieldsOf(created.body()).get("reference");
    }

    /** 집계 시험의 행 시각. 답변이 있으면 답변 완료 상태와 목표 달성 여부를 함께 쓴다(CHECK가 같은 식을 강제한다). */
    private void answeredAt(String reference, Instant received, Instant target, @Nullable Instant answered) {
        jdbc().sql("UPDATE support_inquiry SET received_at = :r, target_at = :t, target_state = 'SET', "
                        + "status = CASE WHEN CAST(:a AS timestamptz) IS NULL THEN 'RECEIVED' ELSE 'ANSWERED' END, "
                        + "answer = CASE WHEN CAST(:a AS timestamptz) IS NULL THEN NULL ELSE '답변' END, "
                        + "first_answered_at = CAST(:a AS timestamptz), "
                        + "target_met = CASE WHEN CAST(:a AS timestamptz) IS NULL THEN NULL "
                        + "ELSE CAST(:a AS timestamptz) <= :t END WHERE reference = :ref")
                .param("r", OffsetDateTime.ofInstant(received, ZoneOffset.UTC))
                .param("t", OffsetDateTime.ofInstant(target, ZoneOffset.UTC))
                .param("a", answered == null ? null : OffsetDateTime.ofInstant(answered, ZoneOffset.UTC))
                .param("ref", reference)
                .update();
    }

    /** 해결 시각을 오늘에서 그 날 수만큼 옮긴다(접수·목표·첫 답변도 그 앞으로). */
    private void resolvedDaysAgo(String reference, int days) {
        jdbc().sql("UPDATE support_inquiry SET received_at = now() - make_interval(days => :d + 10), "
                        + "target_at = now() - make_interval(days => :d + 7), target_state = 'SET', "
                        + "first_answered_at = now() - make_interval(days => :d + 9), target_met = true, "
                        + "answer = '답변', status = 'RESOLVED', resolved_at = now() - make_interval(days => :d) "
                        + "WHERE reference = :ref")
                .param("d", days).param("ref", reference).update();
    }

    /** DB seed 달력으로 본 기대 목표 시각. 그 연도가 달력에 없으면 {@code null}이다. */
    private @Nullable Instant expectedTarget(Instant received) {
        Set<Integer> years = new HashSet<>(jdbc().sql("SELECT year FROM support_calendar_year "
                + "WHERE calendar_version = :v").param("v", SupportInquiryApi.CALENDAR_VERSION).query(Integer.class)
                .list());
        Set<LocalDate> holidays = new HashSet<>(jdbc().sql("SELECT holiday_date FROM support_business_calendar "
                + "WHERE calendar_version = :v").param("v", SupportInquiryApi.CALENDAR_VERSION)
                .query((rs, row) -> rs.getObject(1, LocalDate.class)).list());
        LocalDate day = received.atZone(SEOUL).toLocalDate();
        int counted = 0;
        while (true) {
            day = day.plusDays(1);
            if (!years.contains(day.getYear())) {
                return null;
            }
            boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!weekend && !holidays.contains(day) && ++counted == 3) {
                return day.atTime(LocalTime.of(18, 0)).atZone(SEOUL).toInstant().truncatedTo(ChronoUnit.SECONDS);
            }
        }
    }

    /** 그 운영자의 소비된 문의 처리 허가 수. */
    private long consumedGrants(Signed operator) {
        return jdbc().sql("SELECT count(*) FROM action_grant WHERE user_id = :u AND command = 'operator-update-inquiry' "
                + "AND consumed_at IS NOT NULL").param("u", operator.user()).query(Long.class).single();
    }

    private Map<String, Object> itemOf(Signed user, String reference) throws Exception {
        return itemsIn(get(INQUIRIES, user.cookie()).body(), "items").stream()
                .filter(item -> reference.equals(item.get("reference"))).findFirst().orElseThrow();
    }

    private Instant targetOf(String reference) {
        return jdbc().sql("SELECT target_at FROM support_inquiry WHERE reference = :r").param("r", reference)
                .query(OffsetDateTime.class).single().toInstant();
    }

    private long inquiryCount() {
        return jdbc().sql("SELECT count(*) FROM support_inquiry").query(Long.class).single();
    }

    private Map<String, Object> ledgerDetails(String operationId, String outcome) {
        String json = jdbc().sql("SELECT CAST(details AS text) FROM operations_audit_event WHERE operation_id = :o "
                + "AND outcome = :c").param("o", operationId).param("c", outcome).query(String.class).single();
        return topLevelFieldsOf(json);
    }

    private void assertLedgerHasNo(String raw) {
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE CAST(row_to_json(operations_audit_event) "
                        + "AS text) LIKE :raw").param("raw", "%" + raw + "%").query(Long.class).single())
                .as(raw).isZero();
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(topLevelFieldsOf(response.body())).containsEntry("code", code);
    }

    private static void assertField(HttpResponse<String> response, String field) {
        assertRefused(response, 422, "INQUIRY_INVALID");
        assertThat(topLevelFieldsOf(response.body())).containsEntry("field", field).doesNotContainKey("rule");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(@Nullable Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** 첫 답변 카운터({@code bokrilog.support.first.answer{met}})의 지금 값. */
    private double firstAnswers(String met) {
        var counter = this.meters.find("bokrilog.support.first.answer").tag("met", met).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
