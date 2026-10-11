package com.bokrilog;

import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.Signal;
import com.bokrilog.operations.SignalType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * API 프로세스가 아는 실제 비밀값 집합과 그 노출의 신호 경로(Story 3.7 AC-5).
 *
 * <p>집합은 OIDC client secret들(설정값과 실제 등록 — 개발 스텁의 실행 시점 secret도 등록에 있다), 감사 digest 키
 * ({@code BOKRILOG_AUDIT_DIGEST_KEY}), 자격정보 암호화 key ring의 키들, 그리고 있으면 DB 비밀번호다. 기동 끝에 {@link
 * LogSecretGuard}에 등록하고 컨텍스트가 닫히면 뗀다. 값 자체는 이 객체와 등록부의 메모리에만 있다.
 *
 * <p>노출이 감지되면 {@link OperationalSignalApi#raise}로 {@code SECRET_EXPOSED}({@code ruleId=CONFIGURED_SECRET_IN_LOG})를
 * 올린다. 로그를 쓰는 스레드를 막지 않도록 전용 단일 스레드에서 비동기로 한 번 부르고, 그 스레드의 로그는 다시 신호로 올리지
 * 않는다(재귀 가드). 판정·scope(신규 연결·동기화·계산 표시 전역)는 판정표 {@code OPS-SIGNAL-1.0}이 정한다. 일반 로그
 * 이벤트·일반 경보는 이 경로로 오지 않는다.
 *
 * <p>API(servlet) 프로세스에만 있다 — migrate 역할은 감사 키를 받지 않고 신호를 쓰지 않는다. 그 역할의 로그는 패턴 가림만
 * 받는다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ConfiguredSecrets implements SmartInitializingSingleton, DisposableBean {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ConfiguredSecrets.class);

    /** 설정에서 읽는 비밀값 이름. key ring은 아래에서 따로 푼다. */
    private static final List<String> SECRET_PROPERTIES = List.of(
            "bokrilog.auth.google.client-secret",
            "bokrilog.auth.naver.client-secret",
            "bokrilog.auth.kakao.client-secret",
            "bokrilog.operations.audit-digest-key",
            "spring.datasource.password");

    private static final String KEY_RING_PROPERTY = "bokrilog.source.credential.keys";

    private final Environment environment;
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final OperationalSignalApi signals;
    private final ExecutorService alarms = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "secret-exposure-signal");
        thread.setDaemon(true);
        return thread;
    });
    private volatile LogSecretGuard.@Nullable Registration registration;

    ConfiguredSecrets(Environment environment, ObjectProvider<ClientRegistrationRepository> registrations,
            OperationalSignalApi signals) {
        this.environment = environment;
        this.registrations = registrations;
        this.signals = signals;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, String> named = namedSecrets();
        List<String> tooShort = new ArrayList<>();
        named.forEach((name, value) -> {
            if (value.length() < LogSecretGuard.MIN_SECRET_CHARS && !tooShort.contains(name)) {
                tooShort.add(name);
            }
        });
        if (!tooShort.isEmpty()) {
            // 값은 남기지 않는다. 어느 설정이 감지에서 빠졌는지만 알린다 — 평범한 낱말과 구별되지 않을 만큼 짧다.
            LOG.warn("configured secrets too short to detect in logs: settings={}", String.join(",", tooShort));
        }
        this.registration = LogSecretGuard.register(named.values(), this::raiseAsync);
    }

    @Override
    public void destroy() throws InterruptedException {
        LogSecretGuard.unregister(this.registration);
        this.registration = null;
        this.alarms.shutdown();
        this.alarms.awaitTermination(5, TimeUnit.SECONDS);
    }

    /** 이 프로세스의 비밀값들. 짧은 값은 등록부가 버린다({@link LogSecretGuard#MIN_SECRET_CHARS}). */
    Set<String> secrets() {
        return new LinkedHashSet<>(namedSecrets().values());
    }

    /**
     * 설정 이름 → 비밀값. 같은 이름의 값이 여럿이면(key ring의 키들, 등록마다의 secret) 이름 뒤에 순번을 붙인다. 이름은 기동
     * 경고에만 쓰고 값은 어디에도 쓰지 않는다.
     */
    Map<String, String> namedSecrets() {
        Map<String, String> secrets = new LinkedHashMap<>();
        for (String name : SECRET_PROPERTIES) {
            add(secrets, name, this.environment.getProperty(name));
        }
        String ring = this.environment.getProperty(KEY_RING_PROPERTY);
        if (ring != null) {
            // `<버전>:<base64 키>`를 쉼표로 이은 목록이다. 키 부분만 비밀이다. 콜론이 없는 항목은 항목 전체를 키로 본다.
            int index = 0;
            for (String entry : ring.split(",")) {
                String trimmed = entry.strip();
                int colon = trimmed.indexOf(':');
                add(secrets, KEY_RING_PROPERTY + "[" + index++ + "]", colon < 0 ? trimmed : trimmed.substring(colon + 1));
            }
        }
        ClientRegistrationRepository repository = this.registrations.getIfAvailable();
        if (repository instanceof Iterable<?> iterable) {
            for (Object each : iterable) {
                if (each instanceof ClientRegistration client) {
                    add(secrets, "registration[" + client.getRegistrationId() + "].client-secret",
                            client.getClientSecret());
                }
            }
        }
        return secrets;
    }

    private static void add(Map<String, String> secrets, String name, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            secrets.put(name, value.strip());
        }
    }

    /** 감지 하나를 신호로(비동기 1회). 실패하면 억제를 풀어 다음 등장이 다시 시도한다. */
    private void raiseAsync(String ruleId) {
        try {
            this.alarms.execute(() -> LogSecretGuard.whileRaising(() -> raise(ruleId)));
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // 컨텍스트가 닫히는 중이다. 다음 프로세스의 등장이 다시 올린다.
            LogSecretGuard.raiseFailed(ruleId);
        }
    }

    private void raise(String ruleId) {
        try {
            this.signals.raise(new Signal(SignalType.SECRET_EXPOSED, RestrictionSubject.none(), null,
                    Map.of("ruleId", ruleId), null));
        } catch (RuntimeException failure) {
            LogSecretGuard.raiseFailed(ruleId);
            // 값·원인 문장은 남기지 않는다. 이 줄은 재귀 가드 안이라 다시 신호로 오지 않는다.
            LOG.atWarn().addKeyValue("exception", failure.getClass().getSimpleName()).log("secret exposure signal not recorded");
        }
    }

    /** 시험 전용: 지금 등록된 비밀값 수(짧아서 버린 값은 세지 않는다). */
    int registeredCount() {
        LogSecretGuard.Registration current = this.registration;
        return current == null ? 0 : current.secrets().size();
    }
}
