package com.bokrilog;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.ServiceNoticeApi;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.PlanPublicationPort;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 사용자의 서비스 공지(Story 3.5 AC-2·AC-4·AC-5).
 *
 * <p>{@code GET /api/notices}는 그 사용자의 대상 차원 — 활성·최신 계획(그 portfolio·연결·계산 정책 포함), 기본
 * portfolio, 현재 활성 연결, 지금 계산 정책 — 으로 {@code operations}의 공지 판독({@link ServiceNoticeApi#forSubjects})을
 * 부른다. 차원은 각 모듈 공개 API로만 읽는다. 응답은 {@code {asOf, expiresAt, items[]}}이고 항목은 공지
 * ({@code kind: NOTICE})이거나 공지 없는 제한의 기본 항목({@code kind: RESTRICTED_WITHOUT_NOTICE})이다.
 *
 * <p><b>응답에 사용자 식별자·금융값·제한 target 원문이 없다.</b> 공지는 사건 ID(난수)·버전·상태·시각·기능 code·운영자
 * 한 줄·지원 연락처(설정이 있을 때만)뿐이고, 덮는 제한 ID도 싣지 않는다.
 *
 * <p>계정 접근은 {@code AccountAccessApi.decide(SENSITIVE)}로 본다 — 삭제가 시작된 계정은 없는 경로와 같은 404이고,
 * 미인증은 인가 필터의 401이다(공통 오류 계약). 판독이 실패하면 500이다 — "공지 없음"으로 접지 않는다. 모든 응답이
 * {@code no-store}다.
 *
 * <p>한 요청에 운영 로그 한 줄 — 결과·상태·항목 수·지연뿐이다. 사용자·사건·메시지를 남기지 않는다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ServiceNoticeController {

    /** 공지 항목의 종류. */
    static final String KIND_NOTICE = "NOTICE";

    static final String KIND_RESTRICTED_WITHOUT_NOTICE = "RESTRICTED_WITHOUT_NOTICE";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ServiceNoticeController.class);

    /**
     * 공지 항목 하나. 공지면 사건·버전·상태·시각·메시지가 있고, 기본 항목이면 영향·계속 가능 기능과 연락처뿐이다.
     *
     * @param overdue 공지의 다음 안내 시각이 이미 지났다. 기본 항목에는 없다
     * @param supportContact 지원 연락처 이메일. 공지는 그 버전에 고정된 값이고, 기본 항목은 지금 설정이며 없으면 싣지 않는다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ItemView(String kind, @Nullable UUID incidentId, @Nullable Integer version, @Nullable String status,
            @Nullable String startedAt, List<String> affectedFeatures, List<String> continuingFeatures,
            @Nullable String message, @Nullable String nextUpdateAt, @Nullable Boolean overdue,
            @Nullable String recoveredAt, @Nullable String supportContact) {}

    record NoticesView(String asOf, String expiresAt, List<ItemView> items) {}

    private final ServiceNoticeApi notices;

    private final AccountAccessApi accounts;

    private final PlanPublicationPort plans;

    private final PlanConditionApi planTargets;

    private final PortfolioMembershipApi portfolios;

    private final SourceConnectionApi sources;

    private final SupportContact supportContact;

    ServiceNoticeController(ServiceNoticeApi notices, AccountAccessApi accounts, PlanPublicationPort plans,
            PlanConditionApi planTargets, PortfolioMembershipApi portfolios, SourceConnectionApi sources,
            SupportContact supportContact) {
        this.notices = notices;
        this.accounts = accounts;
        this.plans = plans;
        this.planTargets = planTargets;
        this.portfolios = portfolios;
        this.sources = sources;
        this.supportContact = supportContact;
    }

    @GetMapping(path = AuthSecurityConfig.NOTICES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> notices(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        UUID userId = currentUserId();
        if (userId == null
                || this.accounts.decide(userId, userId, AccountAccessKind.SENSITIVE) != AccessDecision.ALLOWED) {
            // 삭제가 시작된 계정·쓸 수 없는 계정: 없는 경로와 같은 404. 사용자별 항목을 만들지 않는다.
            response.sendError(HttpStatus.NOT_FOUND.value());
            log("NOT_FOUND", HttpStatus.NOT_FOUND.value(), 0, started);
            return null;
        }
        ServiceNoticeApi.UserNotices found;
        try {
            found = this.notices.forSubjects(subjectsOf(userId));
        } catch (RuntimeException failure) {
            // 클래스 이름만 debug로 남긴다. 메시지·원인에는 SQL이나 값이 실릴 수 있다.
            if (LOG.isDebugEnabled()) {
                LOG.debug("service notice read failed: failure={}", failure.getClass().getName());
            }
            log("ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value(), 0, started);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiErrorHandler.ApiError(ApiErrorHandler.CODE_INTERNAL_ERROR,
                            ApiErrorHandler.MESSAGE_INTERNAL_ERROR,
                            ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER))));
        }
        String contact = this.supportContact.value();
        List<ItemView> items = found.items().stream().map(item -> view(item, contact)).toList();
        log("OK", HttpStatus.OK.value(), items.size(), started);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new NoticesView(found.asOf().toString(), found.expiresAt().toString(), items));
    }

    /**
     * 그 사용자의 대상 차원. 계획 차원은 그 계획의 portfolio·연결·계산 정책을 함께 담는다 — 계획의 검사 지점이 그 차원을
     * 함께 넘기므로 더 넓은 portfolio·정책 제한도 그 계획을 막는다. 계획이 없어도 기본 portfolio·활성 연결·지금 계산
     * 정책의 차원이 하나 있다(첫 스냅샷·계획 조건 단계의 검사 지점이 넘기는 차원이다). 전역 제한은 어느 차원에나 맞는다.
     */
    List<RestrictionSubject> subjectsOf(UUID userId) {
        Set<UUID> plans = new LinkedHashSet<>();
        this.plans.activePlan(userId).ifPresent(active -> plans.add(active.planId()));
        this.plans.latestPlan(userId).ifPresent(latest -> plans.add(latest.plan().planId()));
        List<RestrictionSubject> subjects = new ArrayList<>();
        for (UUID planId : plans) {
            this.planTargets.planTarget(planId)
                    .filter(target -> userId.equals(target.userId()))
                    .ifPresent(target -> subjects.add(new RestrictionSubject(target.planId(), target.portfolioId(),
                            // 해제된 연결은 계획 계보 차원에서도 뺀다 — 그 연결의 제한이 해제한 사용자에게 공지로 남지 않는다.
                            this.sources.isRevoked(userId, target.connectionId()) ? null : target.connectionId(),
                            target.calculationPolicy())));
        }
        UUID connection = this.sources.currentConnection(userId)
                .filter(current -> !current.revoked())
                .map(SourceConnectionApi.ConnectionStatus::connectionId)
                .orElse(null);
        UUID portfolio = this.portfolios.findDefaultPortfolio(userId).orElse(null);
        subjects.add(new RestrictionSubject(null, portfolio, connection, PlanCalculator.CALCULATION_POLICY));
        return List.copyOf(subjects);
    }

    private static ItemView view(ServiceNoticeApi.Item item, @Nullable String contact) {
        return switch (item) {
            case ServiceNoticeApi.Item.Notice notice -> {
                ServiceNoticeApi.NoticeVersion version = notice.version();
                yield new ItemView(KIND_NOTICE, version.incidentId(), version.version(), version.status().name(),
                        version.startedAt().toString(), names(version.affectedFeatures()),
                        names(version.continuingFeatures()), version.message(),
                        version.nextUpdateAt() == null ? null : version.nextUpdateAt().toString(),
                        version.nextUpdateAt() == null ? null : notice.overdue(),
                        version.recoveredAt() == null ? null : version.recoveredAt().toString(),
                        version.supportContact());
            }
            case ServiceNoticeApi.Item.RestrictedWithoutNotice restricted -> new ItemView(
                    KIND_RESTRICTED_WITHOUT_NOTICE, null, null, null, null, names(restricted.affectedFeatures()),
                    names(restricted.continuingFeatures()), null, null, null, null, contact);
        };
    }

    private static List<String> names(List<RestrictionFeature> features) {
        return features.stream().map(Enum::name).toList();
    }

    private static void log(String result, int status, int items, long started) {
        LOG.info("service notice request: result={} status={} items={} latencyMs={}", result, status, items,
                (System.nanoTime() - started) / 1_000_000);
    }

    private static @Nullable UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
