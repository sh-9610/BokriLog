package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionOrigin;
import com.bokrilog.operations.RestrictionScope;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 서비스 공지가 해제 smoke 되돌림 계보를 따라간다(Epic 3 회고 B-1·AI-1).
 *
 * <p>해제 smoke가 어긋나면 같은 범위가 새 ID(R2, {@code reinstated_from = R1})로 되돌아간다(Story 3.4). 공지는 제한 하나가
 * 아니라 그 계보를 덮는다 — R1을 덮는 진행 중 공지는 R2·R3에도 이어지고, R2가 열려 있는 동안 R1만 덮는 복구 공지는 준비와
 * 확정 모두에서 거절된다. 계보가 모두 닫힌 뒤에만 복구 공지가 통과한다.
 *
 * <p>smoke 실패는 {@code OperatorRecoveryTest}와 같은 훅으로 만든다 — evaluator를 감싸 해제된 제한을 계속 보고하게 한다.
 * spy 구성이 그 시험과 같아 컨텍스트를 함께 쓴다. 훅이 없는 동안 spy는 실제 구현에 위임한다.
 */
class ServiceNoticeLineageTest extends OperatorTestBase {

    private static final String LIFT = OperatorCommandApi.LIFT_RESTRICTION;

    private static final String PUBLISH = OperatorCommandApi.PUBLISH_NOTICE;

    private static final List<String> CONDITIONS = OperatorCommandApi.RECOVERY_CONDITIONS;

    @MockitoSpyBean
    private OperationalRestrictionApi restrictions;

    /** {@code OperatorRecoveryTest}와 같은 spy 구성(컨텍스트 공유). 이 시험은 훅을 걸지 않는다. */
    @MockitoSpyBean
    private OperatorCommandApi commands;

    @Test
    void 되돌림_뒤에도_공지가_이어지고_열린_되돌림이_있으면_복구_공지를_거절하며_다_닫히면_통과한다() throws Exception {
        Signed user = signedIn("lineage-user");
        UUID r1 = restrictGlobally(RestrictionFeature.SYNC);
        Signed operator = operator("lineage-operator", "command,audit");
        Instant next = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        // R1을 덮는 조사 중 공지. 사용자는 그 공지를 받고 기본 항목은 없다.
        var published = publish(operator, "NEW", "INVESTIGATING", List.of(r1), "동기화 지연 조사 중", next);
        UUID incident = published.incidentId();
        assertCoveredBy(user, incident, "INVESTIGATING");

        // R1 해제의 smoke가 어긋나 같은 범위가 R2로 되돌아간다.
        Reverted first = liftReverted(reauthenticated(published.signed()), r1, "lineage-lift-1");
        UUID r2 = first.reinstated();
        assertThat(reinstatedFromOf(r2)).isEqualTo(r1);
        Signed signed = reauthenticated(first.signed());
        // 운영자 목록이 되돌림 행의 원 제한을 말한다.
        assertThat(itemsIn(restrictionsGet(signed).body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("restrictionId", r2.toString()).containsEntry("reinstated", true)
                .containsEntry("reinstatedFrom", r1.toString()));

        // 사용자: 그 공지가 R2에 대해 이어진다 — RESTRICTED_WITHOUT_NOTICE가 없다.
        assertCoveredBy(user, incident, "INVESTIGATING");

        // R2가 열린 동안 R1만 덮는 복구 공지는 준비에서 거절된다(덮는 제한을 비워 보내도, R1을 직접 보내도).
        assertRefused(prepareNotice(signed, incident.toString(), "RECOVERED", List.of(), "동기화 복구", null,
                key("lineage-recovered-inherit")), 422, "TARGET_INVALID",
                OperatorController.NOTICE_RECOVERY_TARGET_INVALID);
        assertRefused(prepareNotice(signed, incident.toString(), "RECOVERED", List.of(r1), "동기화 복구", null,
                key("lineage-recovered-r1")), 422, "TARGET_INVALID",
                OperatorController.NOTICE_RECOVERY_TARGET_INVALID);
        // 진행 중 공지는 R1이 닫혔어도 그 계보(R2)가 열려 있으니 R1을 덮을 수 있다.
        var changed = publish(signed, incident.toString(), "SCOPE_CHANGED", List.of(r1), "동기화 재조사 중", next);
        assertThat(changed.version()).isEqualTo(2);
        assertCoveredBy(user, incident, "SCOPE_CHANGED");

        // R2를 정상 해제(smoke 통과)하면 계보 전체가 닫힌다.
        Signed lifting = reauthenticated(changed.signed());
        String liftKey = key("lineage-lift-2");
        var preparedLift = prepareLift(lifting, r2, liftKey);
        assertThat(preparedLift.statusCode()).as(preparedLift.body()).isEqualTo(200);
        var liftedR2 = confirmRestriction(lifting, LIFT, operationIdIn(preparedLift.body()),
                phraseIn(preparedLift.body()), liftKey);
        assertThat(liftedR2.statusCode()).as(liftedR2.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(liftedR2.body()).get("smoke"))).containsEntry("status", "PASSED");

        // 복구 공지를 준비한 뒤 확정 전에 계보에 다시 열린 제한이 생기면(되돌림) 확정도 거절한다.
        Signed recovering = reauthenticated(after(lifting, liftedR2));
        String recoveryKey = key("lineage-recovered-race");
        var preparedRecovery = prepareNotice(recovering, incident.toString(), "RECOVERED", List.of(), "동기화 복구",
                null, recoveryKey);
        assertThat(preparedRecovery.statusCode()).as(preparedRecovery.body()).isEqualTo(200);
        UUID raced = reinstate(r2);
        assertRefused(confirmRestriction(recovering, PUBLISH, operationIdIn(preparedRecovery.body()),
                phraseIn(preparedRecovery.body()), recoveryKey), 422, "TARGET_INVALID",
                OperatorController.NOTICE_RECOVERY_TARGET_INVALID);
        assertThat(versionsOf(incident)).hasSize(2);
        // 사용자: 확정 순간 되돌아온 R3(원 제한 R1의 계보)도 그 공지가 덮는다.
        assertCoveredBy(user, incident, "SCOPE_CHANGED");

        // 계보가 모두 닫히면 R1만 덮는 복구 공지가 통과한다.
        close(raced);
        var recovered = publish(recovering, incident.toString(), "RECOVERED", List.of(r1), "동기화 복구", null);
        assertThat(recovered.version()).isEqualTo(3);
        var mine = noticesGet(user);
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        assertThat(itemsIn(mine.body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("kind", "NOTICE").containsEntry("incidentId", incident.toString())
                .containsEntry("status", "RECOVERED"));
    }

    /** 여러 번 되돌아가도(R1 → R2 → R3) R1을 덮는 공지가 R3에 이어진다. */
    @Test
    void 다중_되돌림도_원_제한의_공지가_덮는다() throws Exception {
        Signed user = signedIn("lineage-multi-user");
        UUID r1 = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("lineage-multi-operator", "command");
        Instant next = Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        var published = publish(operator, "NEW", "INVESTIGATING", List.of(r1), "광고 표시 오류 조사 중", next);
        UUID incident = published.incidentId();

        Reverted first = liftReverted(reauthenticated(published.signed()), r1, "lineage-multi-1");
        UUID r2 = first.reinstated();
        Reverted second = liftReverted(reauthenticated(first.signed()), r2, "lineage-multi-2");
        UUID r3 = second.reinstated();
        assertThat(reinstatedFromOf(r3)).isEqualTo(r2);
        assertThat(reinstatedFromOf(r2)).isEqualTo(r1);

        assertCoveredBy(user, incident, "INVESTIGATING");
        // R3가 열려 있으니 R1을 덮는 복구 공지는 거절이다.
        assertRefused(prepareNotice(reauthenticated(second.signed()), incident.toString(), "RECOVERED", List.of(r1),
                "광고 표시 복구", null, key("lineage-multi-recovered")), 422, "TARGET_INVALID",
                OperatorController.NOTICE_RECOVERY_TARGET_INVALID);
    }

    /**
     * 운영자 사건 목록의 최신 버전은 덮는 제한의 되돌림 계보 가운데 지금 열린 제한(`liveCovers`)을 싣는다(2026-10-13 사용자
     * 결정). 닫힌 중간 행(R2)을 지나도 따라가고, 중간 행을 덮은 사건도 같고, 계보가 모두 닫히면 빈 목록이다.
     */
    @Test
    void 사건_목록의_liveCovers는_닫힌_중간_행을_지나_지금_열린_되돌림을_싣는다() throws Exception {
        UUID r1 = restrictGlobally(RestrictionFeature.SYNC);
        Signed operator = operator("lineage-live", "command");
        Instant next = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        var onRoot = publish(operator, "NEW", "INVESTIGATING", List.of(r1), "동기화 지연 조사 중", next);
        // 덮은 제한 자신이 열려 있으면 그것이다.
        assertThat(liveCoversOf(onRoot.signed(), onRoot.incidentId())).containsExactly(r1.toString());

        Reverted first = liftReverted(reauthenticated(onRoot.signed()), r1, "lineage-live-1");
        UUID r2 = first.reinstated();
        Reverted second = liftReverted(reauthenticated(first.signed()), r2, "lineage-live-2");
        UUID r3 = second.reinstated();
        // 중간 행 R2를 덮는 사건(R2는 닫혔지만 계보의 R3가 열려 있어 받는다).
        var onMiddle = publish(reauthenticated(second.signed()), "NEW", "INVESTIGATING", List.of(r2),
                "동기화 재조사 중", next);
        Signed viewer = onMiddle.signed();

        assertThat(liveCoversOf(viewer, onRoot.incidentId())).containsExactly(r3.toString());
        assertThat(liveCoversOf(viewer, onMiddle.incidentId())).containsExactly(r3.toString());
        // 덮는 제한 원문은 그대로다.
        assertThat(latestVersionOf(viewer, onRoot.incidentId())).containsEntry("covers", List.of(r1.toString()));

        close(r3);
        assertThat(liveCoversOf(viewer, onRoot.incidentId())).isEmpty();
        assertThat(latestVersionOf(viewer, onMiddle.incidentId())).containsEntry("liveCovers", List.of());
    }

    private Map<String, Object> latestVersionOf(Signed signed, UUID incident) throws Exception {
        var list = get(OperatorController.NOTICES_PATH, signed.cookie());
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        Map<String, Object> found = itemsIn(list.body(), "items").stream()
                .filter(item -> incident.toString().equals(item.get("incidentId"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) found.get("versions");
        // 최신 버전만 liveCovers를 싣는다.
        for (Map<String, Object> earlier : versions.subList(0, versions.size() - 1)) {
            assertThat(earlier).doesNotContainKey("liveCovers");
        }
        return versions.getLast();
    }

    @SuppressWarnings("unchecked")
    private List<String> liveCoversOf(Signed signed, UUID incident) throws Exception {
        Object live = latestVersionOf(signed, incident).get("liveCovers");
        assertThat(live).isInstanceOf(List.class);
        return (List<String>) live;
    }

    // ------------------------------------------------------------------ 도구

    /** 그 사용자의 공지가 그 사건의 그 상태 하나이고 덮이지 않은 제한(기본 항목)이 없다. */
    private void assertCoveredBy(Signed user, UUID incident, String status) throws Exception {
        var mine = noticesGet(user);
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        List<Map<String, Object>> items = itemsIn(mine.body(), "items");
        assertThat(items).as(mine.body()).noneSatisfy(item -> assertThat(item)
                .containsEntry("kind", "RESTRICTED_WITHOUT_NOTICE"));
        assertThat(items).singleElement().satisfies(item -> assertThat(item).containsEntry("kind", "NOTICE")
                .containsEntry("incidentId", incident.toString()).containsEntry("status", status));
    }

    /** 되돌린 해제: 회전한 운영자 세션과 되돌림이 만든 새 제한. */
    private record Reverted(Signed signed, UUID reinstated) {}

    /** 그 제한을 해제하되 smoke가 해제된 제한을 계속 보고하게 해 같은 범위의 새 제한으로 되돌린다. */
    private Reverted liftReverted(Signed signed, UUID restrictionId, String prefix) throws Exception {
        String key = key(prefix);
        var prepared = prepareLift(signed, restrictionId, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        var facts = jdbc().sql("SELECT feature || '|' || scope || '|' || target FROM operations_restriction "
                + "WHERE restriction_id = :r").param("r", restrictionId).query(String.class).single().split("\\|");
        AtomicReference<@Nullable UUID> stale = new AtomicReference<>(restrictionId);
        hookSmoke(real -> {
            UUID reported = stale.get();
            if (reported == null) {
                return real;
            }
            List<ActiveRestriction> with = new ArrayList<>(real);
            with.add(new ActiveRestriction(reported, RestrictionFeature.valueOf(facts[0]),
                    RestrictionScope.valueOf(facts[1]), facts[2], false, Instant.now(), null, null,
                    RestrictionOrigin.OPERATOR, 0, null));
            return with;
        });
        var confirmed = confirmRestriction(signed, LIFT, operationIdIn(prepared.body()), phraseIn(prepared.body()),
                key);
        stale.set(null);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(confirmed.body()).get("smoke"))).containsEntry("status", "REVERTED");
        return new Reverted(after(signed, confirmed), jdbc().sql("SELECT restriction_id FROM operations_restriction "
                + "WHERE reinstated_from = :r").param("r", restrictionId).query(UUID.class).single());
    }

    /** smoke 훅의 판독 하나. 해제 smoke만 제외 집합 없이 후보 전체를 읽는다(미리보기·기대는 해제 행을 뺀다). */
    private void hookSmoke(java.util.function.Function<List<ActiveRestriction>, List<ActiveRestriction>> answer) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ActiveRestriction> real = (List<ActiveRestriction>) invocation.callRealMethod();
            Set<?> excluding = invocation.getArgument(2);
            return excluding.isEmpty() ? answer.apply(real) : real;
        }).when(this.restrictions).matching(any(), any(), any());
    }

    private HttpResponse<String> prepareLift(Signed signed, UUID restrictionId, String key) throws Exception {
        String conditions = String.join(",", CONDITIONS.stream().map(c -> "\"" + c + "\"").toList());
        return sendJson("POST", commandPath(LIFT), "{\"target\":\"" + restrictionId + "\",\"reason\":\"원인 수정\","
                        + "\"verification\":\"재현 시험 통과\",\"evidenceRef\":\"INC-7 기록\",\"deployVersion\":\"1.4.3\","
                        + "\"conditions\":[" + conditions + "]}", signed.cookie(), headers(signed.csrf(), key));
    }

    private record Published(Signed signed, UUID incidentId, int version) {}

    /** 준비 → 확정. 둘 다 200이어야 한다. 확정 뒤 회전한 세션을 돌려준다(재인증 시각은 지워졌다). */
    private Published publish(Signed signed, String incident, String status, List<UUID> covers, String message,
            @Nullable Instant next) throws Exception {
        String key = key("lineage-publish");
        var prepared = prepareNotice(signed, incident, status, covers, message, next, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        var confirmed = confirmRestriction(signed, PUBLISH, operationIdIn(prepared.body()), phraseIn(prepared.body()),
                key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        Map<String, Object> done = topLevelFieldsOf(confirmed.body());
        return new Published(after(signed, confirmed), UUID.fromString((String) done.get("incidentId")),
                ((Number) done.get("version")).intValue());
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

    private HttpResponse<String> noticesGet(Signed signed) throws Exception {
        return get(AuthSecurityConfig.NOTICES_PATH, signed.cookie());
    }

    private @Nullable UUID reinstatedFromOf(UUID restrictionId) {
        return jdbc().sql("SELECT reinstated_from FROM operations_restriction WHERE restriction_id = :r")
                .param("r", restrictionId).query(UUID.class).single();
    }

    /**
     * 확정 전 되돌림을 흉내 낸다 — 그 제한과 같은 범위의 새 열린 행이 그 제한을 가리킨다({@code reinstate}와 같은 모양).
     * 시험 준비다 — 운영 되돌림 경로는 해제 smoke 하나다.
     */
    private UUID reinstate(UUID liftedRestrictionId) {
        UUID id = UUID.randomUUID();
        jdbc().sql("INSERT INTO operations_restriction (restriction_id, feature, scope, target, origin, reinstated_from, "
                        + "effective_from) SELECT :id, feature, scope, target, origin, restriction_id, statement_timestamp() "
                        + "FROM operations_restriction WHERE restriction_id = :lifted")
                .param("id", id).param("lifted", liftedRestrictionId).update();
        return id;
    }

    /** 제한 하나의 효력 구간을 닫는다. 시험 정리다 — 운영 해제 경로는 {@code lift-restriction} 하나다. */
    private void close(UUID restrictionId) {
        jdbc().sql("UPDATE operations_restriction SET effective_until = statement_timestamp() WHERE restriction_id = :r")
                .param("r", restrictionId).update();
    }

    private List<String> versionsOf(UUID incident) {
        return jdbc().sql("SELECT version || '|' || status FROM operations_notice_version WHERE incident_id = :i "
                + "ORDER BY version").param("i", incident).query(String.class).list();
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(topLevelFieldsOf(response.body())).containsEntry("code", code).containsEntry("message", message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(@Nullable Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
