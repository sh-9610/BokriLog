package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.SignalType;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * 감지 집합의 구성(Story 3.7 AC-5): key ring의 키 부분, 감사 digest 키, DB 비밀번호가 로그에 나오면 그 줄은 가려지고 신호가
 * 올라간다. 너무 짧은 값은 감지에서 빠지고 기동 때 설정 이름만 WARN으로 알린다. Spring 컨텍스트 없이 도는 시험이라 전역 Logback
 * 구성이 아직 {@code logback-spring.xml}이 아닐 수 있다 — 그래서 두 로거에 운영과 같은 encoder({@link RedactingJsonEncoder})를 쓰는
 * 시험 appender를 직접 붙여 그 출력을 본다.
 *
 * <p>비밀 모양 값은 실행 시점에 만든다 — 리터럴로 적으면 gitleaks가 잡는다.
 */
class ConfiguredSecretsTest {

    private static final Logger PROBE = LoggerFactory.getLogger("com.bokrilog.ConfiguredSecretsProbe");

    private static final SecureRandom RANDOM = new SecureRandom();

    @BeforeEach
    @AfterEach
    void resetGuard() {
        LogSecretGuard.resetSuppression();
        LogSecretGuard.useClock(null);
    }

    @Test
    void key_ring_키_감사_digest_키_DB_비밀번호는_가려지고_신호로_올라가며_짧은_값은_이름만_경고한다() throws Exception {
        RenderingAppender lines = RenderingAppender.attach(PROBE.getName(), ConfiguredSecrets.class.getName());
        String ringKey = randomKey();
        String bareKey = randomKey();
        String digestKey = randomKey();
        String password = "pw" + randomKey().substring(0, 12);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bokrilog.source.credential.keys", " probe:" + ringKey + " ,  " + bareKey + " ")
                .withProperty("bokrilog.operations.audit-digest-key", digestKey)
                .withProperty("spring.datasource.password", password)
                .withProperty("bokrilog.auth.naver.client-secret", "short");
        OperationalSignalApi signals = mock(OperationalSignalApi.class);
        ConfiguredSecrets secrets = new ConfiguredSecrets(environment,
                new StaticListableBeanFactory().getBeanProvider(ClientRegistrationRepository.class), signals);
        try {
            secrets.afterSingletonsInstantiated();
            assertThat(secrets.registeredCount()).as("짧은 값은 빠진다").isEqualTo(4);
            assertThat(LogLines.events(lines.text(), "configured secrets too short")).singleElement()
                    .satisfies(event -> assertThat(event).contains("bokrilog.auth.naver.client-secret")
                            .doesNotContain("short,").doesNotContain(ringKey));
            int raised = 0;
            for (String value : List.of(ringKey, bareKey, digestKey, password)) {
                LogSecretGuard.resetSuppression();
                PROBE.warn("probe configured value {}", value);
                raised++;
                verify(signals, timeout(5_000).times(raised)).raise(argThat(signal ->
                        signal.type() == SignalType.SECRET_EXPOSED
                                && LogSecretGuard.RULE_ID.equals(signal.evidence().get("ruleId"))));
                assertThat(lines.text()).doesNotContain(value);
            }
            assertThat(LogLines.events(lines.text(), RedactingJsonEncoder.CONFIGURED_SECRET_MARK)).hasSize(4);
        } finally {
            secrets.destroy();
            lines.detach();
        }
    }

    /** 운영 encoder로 줄을 만들어 모으는 시험 appender. */
    static final class RenderingAppender extends ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> {

        private final java.util.List<String> rendered = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<ch.qos.logback.classic.Logger> loggers = new java.util.ArrayList<>();

        static RenderingAppender attach(String... names) {
            var context = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
            RenderingAppender appender = new RenderingAppender();
            appender.setContext(context);
            appender.start();
            for (String name : names) {
                ch.qos.logback.classic.Logger logger = context.getLogger(name);
                logger.addAppender(appender);
                appender.loggers.add(logger);
            }
            return appender;
        }

        @Override
        protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
            this.rendered.add(RedactingJsonEncoder.render(event));
        }

        String text() {
            return String.join("\n", this.rendered);
        }

        void detach() {
            for (ch.qos.logback.classic.Logger logger : this.loggers) {
                logger.detachAppender(this);
            }
            stop();
        }
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
