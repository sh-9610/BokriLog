package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 3.5: 제한·장애·복구 상태를 서비스 내부 공지로 안내한다 — SPEC I/O 매트릭스 전 행.
 *
 * <p>공지는 운영 command {@code publish-notice} 하나가 쓴다(3.1 envelope·재인증·허가·멱등 키·세션 회전·감사). 사용자는
 * {@code GET /api/notices}로 자기에게 적용되는 효력 제한과 그것을 덮는 최신 공지를 받고, 공지가 없는 제한은 기본 항목으로
 * 받는다. 시험의 제한은 대부분 시험이 직접 넣는다({@link #restrict}) — 운영자 제한 왕복은 {@code OperatorRestrictionTest}가
 * 본다. 복구 공지의 72시간 경과는 행 시각을 옮겨 만든다.
 */
class ServiceNoticeTest extends OperatorTestBase {

    private static final String PUBLISH = OperatorCommandApi.PUBLISH_NOTICE;

    private static final String NOTICES = AuthSecurityConfig.NOTICES_PATH;

    private static final List<String> FOUR = List.of("NEW_CONNECTION", "SYNC", "CALCULATION_DISPLAY", "ADVERTISING");

    @Autowired
    private SupportContact supportContact;

    @AfterEach
    void restoreSupportContact() {
        this.supportContact.reinterpret(OperatorTestProperties.SUPPORT_CONTACT);
    }

    /** 계획 하나를 가진 로그인 사용자. */
    private record Holder(Signed signed, UUID planId, UUID portfolioId, UUID connectionId) {}

    private Holder holder(String prefix) throws Exception {
        return holder(prefix, null);
    }

    private Holder holder(String prefix, @Nullable Instant start) throws Exception {
        Signed signed = signedIn(prefix);
        store(signed.user(), DevBybitStub.Scenario.UTA2);
        publishedPlan(signed.user(), prefix + "-" + UUID.randomUUID(), start);
        UUID planId = ready(signed.user()).planId();
        UUID portfolio = jdbc().sql("SELECT portfolio_id FROM plan_definition WHERE plan_id = :p")
                .param("p", planId).query(UUID.class).single();
        UUID connection = jdbc().sql("SELECT connection_id FROM source_connection WHERE user_id = :u "
                + "AND status = 'ACTIVE'").param("u", signed.user()).query(UUID.class).single();
        return new Holder(signed, planId, portfolio, connection);
    }

    // ------------------------------------------------------------------ 새 사건 → 대상·비대상 사용자

    @Test
    void 새_사건은_v1과_감사_두_행이고_대상_사용자만_공지를_받는다() throws Exception {
        Holder holder = holder("notice-holder");
        Signed bystander = signedIn("notice-bystander");
        UUID r1 = restrict(RestrictionFeature.SYNC, RestrictionScope.PLAN, holder.planId().toString());
        Signed operator = operator("notice-new", "command,audit");
        Instant next = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        String key = key("notice-new");

        var prepared = prepareNotice(operator, "NEW", "INVESTIGATING", List.of(r1), "동기화 지연 원인 조사 중", next, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        Map<String, Object> fields = topLevelFieldsOf(prepared.body());
        assertThat(fields).containsEntry("command", PUBLISH).containsEntry("scope", "NOTICE:INVESTIGATING")
                .containsEntry("target", "NEW").containsEntry("reason", "동기화 지연 원인 조사 중")
                .containsEntry("policyVersion", "OPERATOR-COMMAND-1.4")
                .containsEntry("confirmationPhrase", "공지 발행 새 사건 제한 1건");
        Map<String, Object> impact = mapOf(fields.get("impact"));
        assertThat(impact).containsEntry("incident", "NEW").containsEntry("version", 1)
                .containsEntry("status", "INVESTIGATING").containsEntry("affectedFeatures", List.of("SYNC"))
                .containsEntry("continuingFeatures", List.of("NEW_CONNECTION", "CALCULATION_DISPLAY", "ADVERTISING"))
                .containsEntry("covers", List.of(r1.toString()));
        String operationId = operationIdIn(prepared.body());
        assertThat(noticeVersionCount()).isZero();

        var confirmed = confirmNotice(operator, operationId, "공지 발행 새 사건 제한 1건", key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        Map<String, Object> done = topLevelFieldsOf(confirmed.body());
        assertThat(done).containsEntry("operationId", operationId).containsEntry("outcome", "COMPLETED")
                .containsEntry("command", PUBLISH).containsEntry("version", 1).containsEntry("status", "INVESTIGATING")
                .containsKeys("incidentId", "publishedAt").doesNotContainKey("message");
        UUID incident = UUID.fromString((String) done.get("incidentId"));
        assertThat(sessionCookieOf(confirmed)).as("세션이 회전한다").isPresent();
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");
        Map<String, Object> completed = ledgerDetails(operationId, "COMPLETED");
        assertThat(completed).containsEntry("incidentId", incident.toString()).containsEntry("version", 1)
                .containsEntry("status", "INVESTIGATING").containsEntry("covers", 1);
        assertThat(completed.toString()).doesNotContain("동기화 지연");
        assertThat(jdbc().sql("SELECT version || '|' || COALESCE(CAST(previous_version AS text), '-') || '|' || status "
                        + "|| '|' || array_to_string(affected_features, ',') FROM operations_notice_version "
                        + "WHERE incident_id = :i").param("i", incident).query(String.class).list())
                .containsExactly("1|-|INVESTIGATING|SYNC");

        // 같은 키의 재전송: 저장된 결과, 회전 없음, 원장 행 추가 없음.
        Signed rotated = after(operator, confirmed);
        var replayed = confirmNotice(rotated, operationId, "공지 발행 새 사건 제한 1건", key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replayed.body())).containsEntry("incidentId", incident.toString())
                .containsEntry("version", 1).containsEntry("publishedAt", done.get("publishedAt"));
        assertThat(sessionCookieOf(replayed)).isEmpty();
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");

        // 대상 사용자: 공지 항목 하나, 영향 SYNC, 계속 가능 셋, 연락처.
        var mine = noticesGet(holder.signed());
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        assertThat(mine.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("no-store"));
        assertThat(itemsIn(mine.body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("kind", "NOTICE").containsEntry("incidentId", incident.toString())
                .containsEntry("version", 1).containsEntry("status", "INVESTIGATING")
                .containsEntry("affectedFeatures", List.of("SYNC"))
                .containsEntry("continuingFeatures", List.of("NEW_CONNECTION", "CALCULATION_DISPLAY", "ADVERTISING"))
                .containsEntry("message", "동기화 지연 원인 조사 중").containsEntry("nextUpdateAt", next.toString())
                .containsEntry("supportContact", OperatorTestProperties.SUPPORT_CONTACT)
                .containsKey("startedAt").doesNotContainKeys("recoveredAt", "covers"));
        assertResponseCarriesNoUserFacts(mine.body(), holder, r1);

        // 비대상 사용자: 빈 항목.
        var other = noticesGet(bystander);
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(itemsIn(other.body(), "items")).isEmpty();
        assertThat(topLevelFieldsOf(other.body())).containsOnlyKeys("asOf", "expiresAt", "items");
    }

    // ------------------------------------------------------------------ 입력 검증

    @Test
    void 연락처_메시지_대상_다음_안내가_틀리면_준비를_거절한다() throws Exception {
        UUID open = restrictGlobally(RestrictionFeature.ADVERTISING);
        UUID closed = restrict(RestrictionFeature.SYNC, RestrictionScope.GLOBAL, RestrictionScope.GLOBAL_TARGET);
        closeRestriction(closed);
        Signed operator = operator("notice-input", "command");
        Instant next = Instant.now().plus(1, ChronoUnit.HOURS);

        this.supportContact.reinterpret(null);
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "광고 오류 조사", next,
                key("notice-contact")), 422, "SUPPORT_CONTACT_UNCONFIGURED");
        this.supportContact.reinterpret(OperatorTestProperties.SUPPORT_CONTACT);

        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "주문 1234567 확인 중", next,
                key("notice-digits")), 422, "REASON_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "상세는 https://status.io 참고",
                next, key("notice-url")), 422, "REASON_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "상세는 example.com 참고", next,
                key("notice-domain")), 422, "REASON_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "경로 /api/operator 장애", next,
                key("notice-path")), 422, "REASON_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "담당 ops@example.com", next,
                key("notice-email")), 422, "REASON_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "가".repeat(201), next,
                key("notice-long")), 422, "REASON_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), " ", next, key("notice-blank")),
                422, "REASON_REQUIRED");
        assertRefused(prepareNotice(operator, "NEW", "SCOPE_CHANGED", List.of(open), "광고 오류", next,
                key("notice-new-scope")), 422, "UNSUPPORTED_SCOPE");
        assertRefused(prepareNotice(operator, "NEW", "PAUSED", List.of(open), "광고 오류", next,
                key("notice-status")), 422, "UNSUPPORTED_SCOPE");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(), "광고 오류", next,
                key("notice-empty")), 422, "TARGET_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "광고 오류", null,
                key("notice-no-next")), 422, "NEXT_UPDATE_INVALID");
        // 상태 거절은 재인증 판정 뒤다 — 재인증이 신선한 운영자에게 답한다.
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(closed), "광고 오류", next,
                key("notice-closed")), 422, "TARGET_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(UUID.randomUUID()), "광고 오류", next,
                key("notice-unknown")), 422, "TARGET_INVALID");
        assertRefused(prepareNotice(operator, UUID.randomUUID().toString(), "SCOPE_CHANGED", List.of(open), "광고 오류",
                next, key("notice-no-incident")), 422, "TARGET_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "광고 오류",
                Instant.now().minus(1, ChronoUnit.MINUTES), key("notice-past")), 422, "NEXT_UPDATE_INVALID");
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "광고 오류",
                Instant.now().plus(8, ChronoUnit.DAYS), key("notice-far")), 422, "NEXT_UPDATE_INVALID");
        // 끊어 쓴 금융값·식별자, IP·포트, 통화 금액, 화면 금지 표현도 거절한다(리뷰 patch).
        for (String bad : List.of("손실 12,345,678 확인", "계정 1 234 567 확인", "잔액 $12,345.67 확인",
                "서버 10.0.12.5:8080 장애", "주소 192.168.0.1 장애", "host:8080 장애", "100 USDT 차이", "5000원 차이",
                "USD 30 차이", "실시간 복구 중", "정확한 시각 보장", "수익을 내는 중")) {
            assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), bad, next,
                    key("notice-rule")), 422, "REASON_INVALID");
        }
        // 짧은 시각·버전은 받는다(형식만 본다 — 대상은 열린 제한이라 준비가 된다).
        var fine = prepareNotice(operator, "NEW", "INVESTIGATING", List.of(open), "15:30부터 1.4.2 배포 확인 중",
                next, key("notice-fine"));
        assertThat(fine.statusCode()).as(fine.body()).isEqualTo(200);
        // 데이터베이스 범위 밖 시각은 500이 아니라 422다.
        var outOfRange = sendJson("POST", commandPath(PUBLISH), "{\"incident\":\"NEW\",\"status\":\"INVESTIGATING\","
                + "\"covers\":[\"" + open + "\"],\"message\":\"광고 오류\",\"nextUpdateAt\":\"+300000-01-01T00:00:00Z\"}",
                operator.cookie(), headers(operator.csrf(), key("notice-year")));
        assertRefused(outOfRange, 422, "NEXT_UPDATE_INVALID");
        // 본문 모양 오류는 400이다.
        var malformed = sendJson("POST", commandPath(PUBLISH), "{\"incident\":\"NEW\",\"covers\":\"x\"}",
                operator.cookie(), headers(operator.csrf(), key("notice-shape")));
        assertThat(malformed.statusCode()).isEqualTo(400);

        assertThat(noticeVersionCount()).isZero();
        // 거절 행에 메시지가 원문으로 남지 않는다 — 숫자열·URL·이메일 때문에 거절된 메시지가 원장에 들어가지 않는다.
        assertLedgerHasNo("1234567");
        assertLedgerHasNo("status.io");
        assertLedgerHasNo("@");
    }

    // ------------------------------------------------------------------ 범위 변경·경합·복구

    @Test
    void 범위_변경은_v2를_더하고_v1을_남기며_경합은_409이고_복구_뒤에는_닫힌다() throws Exception {
        Holder holder = holder("notice-scope");
        UUID r1 = restrict(RestrictionFeature.SYNC, RestrictionScope.PLAN, holder.planId().toString());
        UUID r2 = restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.PLAN, holder.planId().toString());
        Signed operator = operator("notice-scope", "command,audit");
        Instant next = Instant.now().plus(1, ChronoUnit.HOURS);
        var first = publish(operator, "NEW", "INVESTIGATING", List.of(r1), "동기화 지연 조사", next);
        UUID incident = first.incidentId();

        // 두 세션이 같은 v2를 준비한다. 먼저 확정한 쪽만 쓰고 뒤의 것은 409다.
        Signed a = reauthenticated(first.signed());
        Signed b = operatorSession("notice-scope-second", a);
        String keyA = key("notice-scope-a");
        String keyB = key("notice-scope-b");
        var preparedA = prepareNotice(a, incident.toString(), "SCOPE_CHANGED", List.of(r1, r2), "표시도 영향 확인",
                next, keyA);
        var preparedB = prepareNotice(b, incident.toString(), "SCOPE_CHANGED", List.of(r1, r2), "표시도 영향 확인",
                next, keyB);
        assertThat(preparedA.statusCode()).as(preparedA.body()).isEqualTo(200);
        assertThat(preparedB.statusCode()).as(preparedB.body()).isEqualTo(200);
        assertThat(phraseIn(preparedA.body())).isEqualTo(scopeChanged(incident, 2));
        assertThat(mapOf(topLevelFieldsOf(preparedA.body()).get("impact")))
                .containsEntry("affectedFeatures", List.of("SYNC", "CALCULATION_DISPLAY"));
        var confirmedA = confirmNotice(a, operationIdIn(preparedA.body()), scopeChanged(incident, 2), keyA);
        assertThat(confirmedA.statusCode()).as(confirmedA.body()).isEqualTo(200);
        var confirmedB = confirmNotice(b, operationIdIn(preparedB.body()), scopeChanged(incident, 2), keyB);
        assertRefused(confirmedB, 409, "NOTICE_VERSION_CONFLICT");
        assertThat(outcomesOf(operationIdIn(preparedB.body()))).containsExactly("REQUESTED", "DENIED");
        assertThat(versionsOf(incident)).containsExactly("1|-|INVESTIGATING|SYNC",
                "2|1|SCOPE_CHANGED|SYNC,CALCULATION_DISPLAY");

        // 대상 사용자는 최신 버전 하나를 받는다(두 제한을 덮는다).
        assertThat(itemsIn(noticesGet(holder.signed()).body(), "items")).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("version", 2)
                        .containsEntry("status", "SCOPE_CHANGED"));

        // 제한을 닫고 복구 공지를 쓴다. 덮는 제한을 비워 보내면 직전 버전의 것을 잇는다.
        closeRestriction(r1);
        closeRestriction(r2);
        Signed c = reauthenticated(after(a, confirmedA));
        var recovered = publish(c, incident.toString(), "RECOVERED", List.of(), "원인 수정 배포 완료", null);
        assertThat(recovered.version()).isEqualTo(3);
        assertThat(versionsOf(incident)).containsExactly("1|-|INVESTIGATING|SYNC",
                "2|1|SCOPE_CHANGED|SYNC,CALCULATION_DISPLAY", "3|2|RECOVERED|SYNC,CALCULATION_DISPLAY");
        assertThat(jdbc().sql("SELECT (recovered_at IS NOT NULL) || '|' || (next_update_at IS NULL) "
                        + "FROM operations_notice_version WHERE incident_id = :i AND version = 3").param("i", incident)
                .query(String.class).single()).isEqualTo("true|true");

        // 복구 공지는 다음 안내 시각을 받지 않고, 닫힌 사건은 새 버전을 받지 않는다.
        Signed d = reauthenticated(recovered.signed());
        assertRefused(prepareNotice(d, incident.toString(), "SCOPE_CHANGED", List.of(restrictGlobally(
                RestrictionFeature.ADVERTISING)), "재발", next, key("notice-closed-again")), 409, "INCIDENT_CLOSED");
        assertRefused(prepareNotice(d, UUID.randomUUID().toString(), "RECOVERED", List.of(), "복구", next,
                key("notice-recovered-next")), 422, "NEXT_UPDATE_INVALID");

        // 복구 뒤 72시간: 영향을 받았던 사용자에게만 복구 항목이 있다.
        Signed bystander = signedIn("notice-scope-bystander");
        var mine = noticesGet(holder.signed());
        List<Map<String, Object>> items = itemsIn(mine.body(), "items");
        assertThat(items).filteredOn(item -> "NOTICE".equals(item.get("kind"))).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("status", "RECOVERED").containsEntry("version", 3)
                        .containsKey("recoveredAt").doesNotContainKey("nextUpdateAt"));
        assertThat(itemsIn(noticesGet(bystander).body(), "items"))
                .noneMatch(item -> incident.toString().equals(item.get("incidentId")));
        // 73시간이 지나면(행 시각을 옮긴다) 그 항목은 없다.
        jdbc().sql("UPDATE operations_notice_version SET recovered_at = recovered_at - interval '73 hours' "
                + "WHERE incident_id = :i AND status = 'RECOVERED'").param("i", incident).update();
        assertThat(itemsIn(noticesGet(holder.signed()).body(), "items"))
                .noneMatch(item -> incident.toString().equals(item.get("incidentId")));

        // 운영자 목록: 사건과 버전 이력(읽기 전용), 닫힘, 연락처 설정 여부.
        var list = get(OperatorController.NOTICES_PATH, d.cookie());
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        Map<String, Object> listed = topLevelFieldsOf(list.body());
        assertThat(listed).containsEntry("supportContactConfigured", true);
        assertThat(itemsIn(list.body(), "items")).filteredOn(item -> incident.toString().equals(item.get("incidentId")))
                .singleElement().satisfies(item -> {
                    assertThat(item).containsEntry("closed", true);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> versions = (List<Map<String, Object>>) item.get("versions");
                    assertThat(versions).extracting(version -> version.get("version")).containsExactly(1, 2, 3);
                    assertThat(versions.get(1)).containsEntry("previousVersion", 1)
                            .containsEntry("covers", List.of(r1.toString(), r2.toString()).stream().sorted().toList());
                });
    }

    @Test
    @SuppressWarnings("try") // 잠금을 쥐고 있는 동안이 시험의 사이다. 본문이 그 값을 부르지 않는다.
    void 같은_버전의_동시_확정은_원장_잠금에서_줄을_서고_하나만_쓴다() throws Exception {
        UUID ad = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("notice-race", "command,audit");
        Instant next = Instant.now().plus(1, ChronoUnit.HOURS);
        Published first = publish(operator, "NEW", "INVESTIGATING", List.of(ad), "광고 오류 조사", next);
        UUID incident = first.incidentId();
        Signed a = reauthenticated(first.signed());
        Signed b = operatorSession("notice-race-second", a);
        String keyA = key("notice-race-a");
        String keyB = key("notice-race-b");
        var preparedA = prepareNotice(a, incident.toString(), "SCOPE_CHANGED", List.of(ad), "범위 재확인", next, keyA);
        var preparedB = prepareNotice(b, incident.toString(), "SCOPE_CHANGED", List.of(ad), "범위 재확인", next, keyB);
        assertThat(preparedA.statusCode()).as(preparedA.body()).isEqualTo(200);
        assertThat(preparedB.statusCode()).as(preparedB.body()).isEqualTo(200);

        List<HttpResponse<String>> answers = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        try (AutoCloseable held = holdAuditLock()) {
            threads.add(Thread.ofVirtual().start(() -> answers.add(quietly(() -> confirmNotice(a,
                    operationIdIn(preparedA.body()), scopeChanged(incident, 2), keyA)))));
            threads.add(Thread.ofVirtual().start(() -> answers.add(quietly(() -> confirmNotice(b,
                    operationIdIn(preparedB.body()), scopeChanged(incident, 2), keyB)))));
            awaitAdvisoryWaiters(2);
        }
        for (Thread thread : threads) {
            thread.join(20_000);
        }
        assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(answers).filteredOn(answer -> answer.statusCode() == 409).singleElement()
                .satisfies(answer -> assertThat(topLevelFieldsOf(answer.body()))
                        .containsEntry("code", "NOTICE_VERSION_CONFLICT"));
        assertThat(versionsOf(incident)).containsExactly("1|-|INVESTIGATING|ADVERTISING",
                "2|1|SCOPE_CHANGED|ADVERTISING");
    }

    // ------------------------------------------------------------------ 확정 순간 재검사·멱등·연락처 고정

    @Test
    void 준비_뒤_덮는_제한이_닫히거나_연락처가_비면_확정은_거절되고_아무것도_쓰지_않는다() throws Exception {
        UUID ad = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("notice-recheck", "command,audit");
        Instant next = Instant.now().plus(1, ChronoUnit.HOURS);

        String key = key("notice-recheck");
        var prepared = prepareNotice(operator, "NEW", "INVESTIGATING", List.of(ad), "광고 오류 조사", next, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        closeRestriction(ad);
        var confirmed = confirmNotice(operator, operationIdIn(prepared.body()), phraseIn(prepared.body()), key);
        assertRefused(confirmed, 422, "TARGET_INVALID");
        assertThat(noticeVersionCount()).isZero();
        assertThat(outcomesOf(operationIdIn(prepared.body()))).containsExactly("REQUESTED", "DENIED");

        // 같은 키에 덮는 제한·다음 안내만 바꾼 준비는 다른 요청이다(키는 끝난 operation의 것이기도 하다).
        UUID sync = restrictGlobally(RestrictionFeature.SYNC);
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(sync), "광고 오류 조사", next, key),
                409, "IDEMPOTENCY_CONFLICT");
        String open = key("notice-recheck-open");
        var first = prepareNotice(operator, "NEW", "INVESTIGATING", List.of(sync), "동기화 오류 조사", next, open);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(sync), "동기화 오류 조사",
                next.plus(1, ChronoUnit.HOURS), open), 409, "IDEMPOTENCY_CONFLICT");

        // 준비 뒤 연락처가 비면 확정이 거절되고 아무것도 쓰지 않는다.
        String contactKey = key("notice-recheck-contact");
        var armed = prepareNotice(operator, "NEW", "INVESTIGATING", List.of(sync), "동기화 지연 조사", next, contactKey);
        assertThat(armed.statusCode()).as(armed.body()).isEqualTo(200);
        this.supportContact.reinterpret(null);
        assertRefused(confirmNotice(operator, operationIdIn(armed.body()), phraseIn(armed.body()), contactKey), 422,
                "SUPPORT_CONTACT_UNCONFIGURED");
        assertThat(noticeVersionCount()).isZero();
        assertThat(outcomesOf(operationIdIn(armed.body()))).containsExactly("REQUESTED", "DENIED");
    }

    @Test
    void 연락처_거절은_재인증_판정_뒤이고_복구_공지는_열린_제한을_덮지_못한다() throws Exception {
        UUID ad = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed plain = signedIn("notice-order");
        operatorAccess().reinterpret(entry(plain.subject(), "command"));
        this.supportContact.reinterpret(null);
        // 재인증이 없으면 연락처보다 재인증 요구가 먼저다(키 → 재인증 → 대상 순서).
        assertRefused(prepareNotice(plain, "NEW", "INVESTIGATING", List.of(ad), "광고 오류", Instant.now()
                .plus(1, ChronoUnit.HOURS), key("notice-order-reauth")), 403, "REAUTHENTICATION_REQUIRED");
        Signed operator = reauthenticated(plain);
        assertRefused(prepareNotice(operator, "NEW", "INVESTIGATING", List.of(ad), "광고 오류", Instant.now()
                .plus(1, ChronoUnit.HOURS), key("notice-order-contact")), 422, "SUPPORT_CONTACT_UNCONFIGURED");
        this.supportContact.reinterpret(OperatorTestProperties.SUPPORT_CONTACT);

        var first = publish(operator, "NEW", "INVESTIGATING", List.of(ad), "광고 오류 조사",
                Instant.now().plus(1, ChronoUnit.HOURS));
        Signed again = reauthenticated(first.signed());
        // 덮는 제한이 아직 효력 중이면 복구 공지를 낼 수 없다. 문장이 그 사실을 말한다.
        var refused = prepareNotice(again, first.incidentId().toString(), "RECOVERED", List.of(), "복구", null,
                key("notice-recover-open"));
        assertRefused(refused, 422, "TARGET_INVALID");
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("message",
                OperatorController.NOTICE_RECOVERY_TARGET_INVALID);
        assertThat(versionsOf(first.incidentId())).containsExactly("1|-|INVESTIGATING|ADVERTISING");
    }

    @Test
    void 지원_연락처는_발행한_버전에_고정되고_재배포로_바뀌지_않는다() throws Exception {
        Signed user = signedIn("notice-contact");
        UUID ad = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("notice-contact-operator", "command");
        publish(operator, "NEW", "INVESTIGATING", List.of(ad), "광고 오류 조사", Instant.now().plus(1, ChronoUnit.HOURS));
        this.supportContact.reinterpret("help@bokrilog.test");
        UUID sync = restrictGlobally(RestrictionFeature.SYNC);
        List<Map<String, Object>> items = itemsIn(noticesGet(user).body(), "items");
        // 공지는 발행 시점의 연락처, 공지 없는 제한의 기본 항목은 지금 설정이다.
        assertThat(items).extracting(item -> item.get("kind") + "|" + item.get("supportContact"))
                .containsExactly("NOTICE|" + OperatorTestProperties.SUPPORT_CONTACT,
                        "RESTRICTED_WITHOUT_NOTICE|help@bokrilog.test");
        assertThat(jdbc().sql("SELECT support_contact FROM operations_notice_version").query(String.class).list())
                .containsExactly(OperatorTestProperties.SUPPORT_CONTACT);
        closeRestriction(sync);
    }

    @Test
    void 다음_안내가_지난_공지는_늦어짐으로_싣고_만료_계산에서_뺀다() throws Exception {
        Signed user = signedIn("notice-overdue");
        UUID ad = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("notice-overdue-operator", "command");
        UUID incident = publish(operator, "NEW", "INVESTIGATING", List.of(ad), "광고 오류 조사",
                Instant.now().plus(1, ChronoUnit.MINUTES)).incidentId();
        jdbc().sql("UPDATE operations_notice_version SET next_update_at = statement_timestamp() - interval '10 minutes' "
                + "WHERE incident_id = :i").param("i", incident).update();
        Map<String, Object> body = topLevelFieldsOf(noticesGet(user).body());
        Instant asOf = Instant.parse((String) body.get("asOf"));
        assertThat(Instant.parse((String) body.get("expiresAt"))).isEqualTo(asOf.plus(5, ChronoUnit.MINUTES));
        assertThat(itemsIn(noticesGet(user).body(), "items")).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("overdue", true).containsKey("nextUpdateAt"));
    }

    @Test
    void 해제된_연결은_계획_계보에서도_빠지고_종료된_계획의_PLAN_차원은_남는다() throws Exception {
        // 계획 종료는 시작이 충분히 지난 계획만 받는다(Story 2.13) — 시작을 10일 앞으로 당긴다.
        Holder holder = holder("notice-lineage", Instant.now().minus(10, ChronoUnit.DAYS));
        // 종료된 계획: 최신 계획(`latestPlan`)의 PLAN 차원은 남는다.
        Signed ended = endPlan(holder);
        restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.PLAN, holder.planId().toString());
        assertThat(itemsIn(noticesGet(ended).body(), "items")).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("affectedFeatures", List.of("CALCULATION_DISPLAY")));

        // 해제된 연결의 제한은 그 사용자에게 공지로 남지 않는다(계획 계보 차원에서도).
        jdbc().sql("TRUNCATE TABLE operations_restriction CASCADE").update();
        restrict(RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION, holder.connectionId().toString());
        assertThat(itemsIn(noticesGet(ended).body(), "items")).hasSize(1);
        revokeConnectionOf(ended.user());
        var revoked = noticesGet(ended);
        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(itemsIn(revoked.body(), "items")).isEmpty();
    }

    // ------------------------------------------------------------------ 공지 없는 제한·기능별 매핑

    @Test
    void 공지가_없는_효력_제한은_모든_대상_사용자에게_기본_항목이다() throws Exception {
        Signed user = signedIn("notice-default");
        restrictGlobally(RestrictionFeature.NEW_CONNECTION);
        var response = noticesGet(user);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(itemsIn(response.body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsOnlyKeys("kind", "affectedFeatures", "continuingFeatures", "supportContact")
                .containsEntry("kind", "RESTRICTED_WITHOUT_NOTICE")
                .containsEntry("affectedFeatures", List.of("NEW_CONNECTION"))
                .containsEntry("continuingFeatures", List.of("SYNC", "CALCULATION_DISPLAY", "ADVERTISING")));

        // 연락처가 없으면 기본 항목은 연락처 없이 남는다 — 제한을 숨기지 않는다.
        this.supportContact.reinterpret(null);
        assertThat(itemsIn(noticesGet(user).body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("kind", "RESTRICTED_WITHOUT_NOTICE").doesNotContainKey("supportContact"));
    }

    @Test
    void 사용자_차원은_계획_연결_portfolio_정책_전역으로_맞추고_다른_대상은_싣지_않는다() throws Exception {
        Holder holder = holder("notice-dimension");
        Signed fresh = signedIn("notice-dimension-fresh");
        // 남의 계획·portfolio·연결 제한은 그 사용자에게만 맞는다.
        restrict(RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION, holder.connectionId().toString());
        var connection = itemsIn(noticesGet(holder.signed()).body(), "items");
        assertThat(connection).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("affectedFeatures", List.of("SYNC")));
        assertThat(itemsIn(noticesGet(fresh).body(), "items")).isEmpty();

        restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.PORTFOLIO, holder.portfolioId().toString());
        assertThat(itemsIn(noticesGet(holder.signed()).body(), "items")).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("affectedFeatures",
                        List.of("SYNC", "CALCULATION_DISPLAY")));
        assertThat(itemsIn(noticesGet(fresh).body(), "items")).isEmpty();

        // 계산 정책 버전은 계획이 없는 사용자에게도 맞는다(지금 정책).
        jdbc().sql("TRUNCATE TABLE operations_restriction CASCADE").update();
        restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.CALCULATION_POLICY_VERSION, "CALC-1.0");
        assertThat(itemsIn(noticesGet(fresh).body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("affectedFeatures", List.of("CALCULATION_DISPLAY")));
        assertThat(itemsIn(noticesGet(holder.signed()).body(), "items")).hasSize(1);

        // 계획 하나의 제한은 그 계획의 주인에게만.
        jdbc().sql("TRUNCATE TABLE operations_restriction CASCADE").update();
        restrict(RestrictionFeature.SYNC, RestrictionScope.PLAN, holder.planId().toString());
        assertThat(itemsIn(noticesGet(holder.signed()).body(), "items")).hasSize(1);
        assertThat(itemsIn(noticesGet(fresh).body(), "items")).isEmpty();

        // 전역은 모두에게. 닫힌 제한은 아무에게도.
        UUID global = restrictGlobally(RestrictionFeature.ADVERTISING);
        assertThat(itemsIn(noticesGet(fresh).body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("affectedFeatures", List.of("ADVERTISING")));
        closeRestriction(global);
        assertThat(itemsIn(noticesGet(fresh).body(), "items")).isEmpty();
    }

    @Test
    void 만료는_다음_안내_시각과_5분_가운데_이른_쪽이고_공지는_제한을_풀지_않는다() throws Exception {
        Signed user = signedIn("notice-expiry");
        UUID ad = restrictGlobally(RestrictionFeature.ADVERTISING);
        UUID sync = restrictGlobally(RestrictionFeature.SYNC);
        Signed operator = operator("notice-expiry-operator", "command");
        Instant soon = Instant.now().plus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        publish(operator, "NEW", "INVESTIGATING", List.of(ad), "광고 오류 조사", soon);

        Map<String, Object> body = topLevelFieldsOf(noticesGet(user).body());
        Instant asOf = Instant.parse((String) body.get("asOf"));
        assertThat(Instant.parse((String) body.get("expiresAt"))).isEqualTo(soon)
                .isBefore(asOf.plus(5, ChronoUnit.MINUTES));
        // 공지가 광고 제한만 덮는다 — 동기화 제한은 기본 항목으로 남는다.
        assertThat(itemsIn(noticesGet(user).body(), "items")).extracting(item -> item.get("kind"))
                .containsExactly("NOTICE", "RESTRICTED_WITHOUT_NOTICE");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction WHERE effective_until IS NULL "
                + "AND restriction_id IN (:a, :s)").param("a", ad).param("s", sync).query(Long.class).single())
                .isEqualTo(2L);

        // 다음 안내가 먼 공지만 있으면 asOf + 5분이다.
        jdbc().sql("TRUNCATE TABLE operations_notice_version, operations_notice_incident").update();
        closeRestriction(sync);
        Signed again = operator("notice-expiry-far", "command");
        publish(again, "NEW", "INVESTIGATING", List.of(ad), "광고 오류 조사", Instant.now().plus(1, ChronoUnit.DAYS));
        Map<String, Object> far = topLevelFieldsOf(noticesGet(user).body());
        assertThat(Instant.parse((String) far.get("expiresAt")))
                .isEqualTo(Instant.parse((String) far.get("asOf")).plus(5, ChronoUnit.MINUTES));
    }

    // ------------------------------------------------------------------ 삭제·권한

    @Test
    void 삭제가_시작된_계정과_미인증은_공통_오류이고_사용자별_항목이_없다() throws Exception {
        Signed user = signedIn("notice-deleted");
        restrictGlobally(RestrictionFeature.ADVERTISING);
        assertThat(noticesGet(user).statusCode()).isEqualTo(200);
        accountDeletion().beginDeletion(user.user());
        var deleted = noticesGet(user);
        assertThat(deleted.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(deleted.body())).containsOnlyKeys("code", "message", "correlationId");

        var anonymous = get(NOTICES, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(topLevelFieldsOf(anonymous.body())).containsOnlyKeys("code", "message", "correlationId");
    }

    @Test
    void 비운영자와_감사_권한만_가진_운영자의_발행과_목록은_404다() throws Exception {
        UUID ad = restrictGlobally(RestrictionFeature.ADVERTISING);
        Instant next = Instant.now().plus(1, ChronoUnit.HOURS);
        Signed auditOnly = operator("notice-audit-only", "audit");
        assertThat(prepareNotice(auditOnly, "NEW", "INVESTIGATING", List.of(ad), "광고 오류", next,
                key("notice-audit-only")).statusCode()).isEqualTo(404);
        assertThat(get(OperatorController.NOTICES_PATH, auditOnly.cookie()).statusCode()).isEqualTo(404);
        operatorAccess().reinterpret("");
        Signed nobody = signedIn("notice-nobody");
        var refused = prepareNotice(nobody, "NEW", "INVESTIGATING", List.of(ad), "광고 오류", next, key("notice-nobody"));
        assertThat(refused.statusCode()).isEqualTo(404);
        var list = get(OperatorController.NOTICES_PATH, nobody.cookie());
        assertThat(list.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(list.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", topLevelFieldsOf(get("/api/operator/nope", nobody.cookie()).body()).get("code"));
        assertThat(noticeVersionCount()).isZero();
        assertThat(auditCount()).isZero();
    }

    // ------------------------------------------------------------------ 추가 전용 소스 스캔

    /**
     * 확정의 판정 순서(운영 command 공통화, 2026-10-13 사용자 결정): 원장 잠금 → 최종 권한이 템플릿 prologue이고 지원 연락처는
     * 그 뒤의 공지 본문이다. 그래서 확정 요청 전에 연락처가 비고, 확정이 원장 잠금을 기다리는 사이 권한이 회수되면 연락처
     * 거절(422)이 아니라 권한 회수(없는 경로와 같은 404)다. 아무것도 쓰지 않고 거절 행 하나로 맺는다.
     */
    @Test
    @SuppressWarnings("try")
    void 확정_순간_권한_회수가_빈_연락처보다_먼저인_404다() throws Exception {
        UUID ad = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("notice-order-revoked", "command");
        String key = key("notice-order-revoked");
        var prepared = prepareNotice(operator, "NEW", "INVESTIGATING", List.of(ad), "광고 오류 조사",
                Instant.now().plus(1, ChronoUnit.HOURS), key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        String operationId = operationIdIn(prepared.body());
        // 게이트는 연락처를 보지 않는다. 확정 요청 전에 비운다.
        this.supportContact.reinterpret(null);

        java.util.concurrent.CompletableFuture<HttpResponse<String>> confirming;
        HttpResponse<String> revoked;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try (AutoCloseable held = holdAuditLock()) {
                confirming = java.util.concurrent.CompletableFuture.supplyAsync(() -> quietly(() -> confirmNotice(
                        operator, operationId, phraseIn(prepared.body()), key)), executor);
                awaitAdvisoryWaiters(1);
                operatorAccess().reinterpret("");
            }
            revoked = confirming.get();
        }
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(404);
        assertThat(noticeVersionCount()).isZero();
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
        assertThat(decisionsOf(operator.user())).last().isEqualTo("DENIED:ACCESS_REVOKED");
    }

    /**
     * 공지 두 표의 운영 SQL은 INSERT·SELECT뿐이다 — 고치거나 지우는 문장이 운영 소스 어디에도 없고, 두 표를 문자열로 아는
     * 운영 소스는 공지 repository 하나다. 원장 repository의 UPDATE는 비식별 {@code eraseActor} 하나다.
     */
    @Test
    void 공지_표의_운영_SQL에는_UPDATE와_DELETE가_없다() throws Exception {
        Path main = Path.of("src/main/java");
        Pattern mutation = Pattern.compile("\\b(?:UPDATE\\s+(?:ONLY\\s+)?operations_notice_\\w+|DELETE\\s+FROM\\s+"
                + "(?:ONLY\\s+)?operations_notice_\\w+|TRUNCATE\\b[^;\"]*\\boperations_notice_\\w+)",
                Pattern.CASE_INSENSITIVE);
        List<String> mutations = new ArrayList<>();
        List<String> naming = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8)
                        .replaceAll("\"\\s*\\+\\s*\"", "").replaceAll("\\s+", " ");
                Matcher matcher = mutation.matcher(source);
                while (matcher.find()) {
                    mutations.add(file.getFileName() + ":" + matcher.group());
                }
                if (source.contains("FROM operations_notice_") || source.contains("INTO operations_notice_")) {
                    naming.add(file.getFileName().toString());
                }
            }
        }
        assertThat(mutations).isEmpty();
        assertThat(naming).containsExactly("ServiceNoticeRepository.java");
        String repository = Files.readString(main.resolve("com/bokrilog/operations/internal/ServiceNoticeRepository.java"),
                StandardCharsets.UTF_8);
        assertThat(repository).doesNotContain("UPDATE").doesNotContain("DELETE").doesNotContain("TRUNCATE");

        String ledger = Files.readString(main.resolve("com/bokrilog/operations/internal/OperatorAuditRepository.java"),
                StandardCharsets.UTF_8);
        Matcher updates = Pattern.compile("\"UPDATE ").matcher(ledger);
        int count = 0;
        while (updates.find()) {
            count++;
        }
        assertThat(count).isEqualTo(1);
        assertThat(ledger).contains("UPDATE operations_audit_event SET actor_user_id = NULL, ");
    }

    // ------------------------------------------------------------------ 도구

    /** 같은 allowlist의 두 번째 운영자 세션(재인증까지). 첫 세션의 allowlist 줄을 지우지 않는다. */
    private Signed operatorSession(String prefix, Signed first) throws Exception {
        Signed second = signedIn(prefix);
        operatorAccess().reinterpret(entry(first.subject(), "command,audit") + ";"
                + entry(second.subject(), "command,audit"));
        return reauthenticated(second);
    }

    private record Published(Signed signed, UUID incidentId, int version, String operationId) {}

    /** 기존 사건 범위 변경의 확인 문구. 사건 ID 앞 8자와 버전을 담는다. */
    private static String scopeChanged(UUID incident, int version) {
        return "공지 범위 변경 " + incident.toString().substring(0, 8) + " v" + version;
    }

    /** 그 계획을 사용자 요청으로 중도 종료한다(Story 2.13의 준비 → 확정). 시작을 10일 앞으로 당긴 계획이어야 한다. */
    private Signed endPlan(Holder holder) throws Exception {
        Signed signed = reauthenticated(holder.signed());
        var result = ready(signed.user());
        String path = AuthSecurityConfig.DASHBOARD_PATH + "/plan-end";
        String reference = "\"planId\":\"" + result.planId() + "\",\"pointerVersion\":" + result.pointerVersion()
                + ",\"calculationId\":\"" + result.calculationId() + "\"";
        var prepared = sendJson("POST", path, "{\"reason\":\"USER_REQUEST\"," + reference + "}", signed.cookie(),
                headers(signed.csrf(), null));
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        var confirmed = sendJson("POST", path + "/confirm", "{\"reason\":\"USER_REQUEST\"," + reference
                        + ",\"confirmation\":\"" + confirmationIn(prepared.body()) + "\"}", signed.cookie(),
                headers(signed.csrf(), key("notice-plan-end")));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        // 재인증과 종료 확정이 세션을 회전시켰다. 그 뒤의 요청은 새 쿠키로 한다.
        return after(signed, confirmed);
    }

    /** 준비 → 확정. 둘 다 200이어야 한다. 확정 뒤 회전한 세션을 돌려준다(재인증 시각은 지워졌다). */
    private Published publish(Signed signed, String incident, String status, List<UUID> covers, String message,
            @Nullable Instant next) throws Exception {
        String key = key("notice-publish");
        var prepared = prepareNotice(signed, incident, status, covers, message, next, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        var confirmed = confirmNotice(signed, operationIdIn(prepared.body()), phraseIn(prepared.body()), key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        Map<String, Object> done = topLevelFieldsOf(confirmed.body());
        return new Published(after(signed, confirmed), UUID.fromString((String) done.get("incidentId")),
                ((Number) done.get("version")).intValue(), operationIdIn(prepared.body()));
    }

    private HttpResponse<String> prepareNotice(Signed signed, String incident, String status, List<UUID> covers,
            String message, @Nullable Instant next, String key) throws Exception {
        List<String> fields = new ArrayList<>();
        fields.add("\"incident\":\"" + incident + "\"");
        fields.add("\"status\":\"" + status + "\"");
        fields.add("\"covers\":[" + String.join(",", covers.stream().map(id -> "\"" + id + "\"").toList()) + "]");
        fields.add("\"message\":\"" + message + "\"");
        if (next != null) {
            fields.add("\"nextUpdateAt\":\"" + next + "\"");
        }
        return sendJson("POST", commandPath(PUBLISH), "{" + String.join(",", fields) + "}", signed.cookie(),
                headers(signed.csrf(), key));
    }

    private HttpResponse<String> confirmNotice(Signed signed, String operationId, String phrase, String key)
            throws Exception {
        return confirmRestriction(signed, PUBLISH, operationId, phrase, key);
    }

    private HttpResponse<String> noticesGet(Signed signed) throws Exception {
        return get(NOTICES, signed.cookie());
    }

    /** 제한 하나의 효력 구간을 닫는다. 시험 정리다 — 운영 해제 경로는 {@code lift-restriction} 하나다. */
    private void closeRestriction(UUID restrictionId) {
        jdbc().sql("UPDATE operations_restriction SET effective_until = statement_timestamp() WHERE restriction_id = :r")
                .param("r", restrictionId).update();
    }

    private long noticeVersionCount() {
        return jdbc().sql("SELECT count(*) FROM operations_notice_version").query(Long.class).single();
    }

    private List<String> versionsOf(UUID incident) {
        return jdbc().sql("SELECT version || '|' || COALESCE(CAST(previous_version AS text), '-') || '|' || status "
                        + "|| '|' || array_to_string(affected_features, ',') FROM operations_notice_version "
                        + "WHERE incident_id = :i ORDER BY version").param("i", incident).query(String.class).list();
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

    /**
     * 응답 allowlist: 시각·사건 ID를 걷어 낸 본문에 UUID·숫자 6자리 이상 연속이 없고, 이메일은 지원 연락처 하나다. 사용자·
     * 계획·portfolio·연결·제한 ID가 어디에도 없다.
     */
    private static void assertResponseCarriesNoUserFacts(String body, Holder holder, UUID restrictionId) {
        assertThat(body).doesNotContain(holder.signed().user().toString()).doesNotContain(holder.planId().toString())
                .doesNotContain(holder.portfolioId().toString()).doesNotContain(holder.connectionId().toString())
                .doesNotContain(restrictionId.toString());
        String stripped = body
                .replaceAll("\"(asOf|expiresAt|startedAt|nextUpdateAt|recoveredAt)\":\"[^\"]*\"", "")
                .replaceAll("\"incidentId\":\"[^\"]*\"", "")
                .replace(OperatorTestProperties.SUPPORT_CONTACT, "");
        assertThat(stripped).doesNotContainPattern("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-")
                .doesNotContainPattern("\\d{6,}").doesNotContain("@");
        assertThat(topLevelFieldsOf(body)).containsOnlyKeys("asOf", "expiresAt", "items");
        for (Map<String, Object> item : itemsIn(body, "items")) {
            assertThat(item.keySet()).isSubsetOf(List.of("kind", "incidentId", "version", "status", "startedAt",
                    "affectedFeatures", "continuingFeatures", "message", "nextUpdateAt", "overdue", "recoveredAt",
                    "supportContact"));
            @SuppressWarnings("unchecked")
            List<String> affected = (List<String>) item.get("affectedFeatures");
            assertThat(FOUR).containsAll(affected);
        }
    }

    private interface Call {
        HttpResponse<String> run() throws Exception;
    }

    private static HttpResponse<String> quietly(Call call) {
        try {
            return call.run();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(topLevelFieldsOf(response.body())).containsEntry("code", code);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(@Nullable Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
