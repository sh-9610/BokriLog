package com.bokrilog;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 웹 프로세스의 역할 판정(Story 3.8 AC-3). 정의는 {@link BokrilogRole}에 있다.
 *
 * <p>servlet 프로세스에만 있다 — 일회성 역할({@code migrate}·{@code deploy-audit})은 웹 서버도 작업자도 없어 역할이 없다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class BokrilogRoleConfig {

    /** 운영 환경 값({@code BOKRILOG_ENV}). */
    static final String PRODUCTION = "production";

    /**
     * 이 프로세스의 역할. 셋 밖의 값이거나, 운영({@code BOKRILOG_ENV=production})에서 {@link BokrilogRole#ALL}이면 기동을
     * 거부한다 — 운영은 API와 Worker를 반드시 나눠 띄운다. 메시지에 값을 담지 않는다.
     */
    @Bean
    BokrilogRole bokrilogRole(@Value("${" + BokrilogRole.PROPERTY + ":" + BokrilogRole.DEFAULT + "}") String role,
            @Value("${bokrilog.telemetry.env:development}") String env) {
        BokrilogRole parsed = BokrilogRole.parse(role);
        if (parsed == BokrilogRole.ALL && PRODUCTION.equals(env)) {
            throw new IllegalStateException("운영 환경은 bokrilog.role을 api 또는 worker로 정해야 한다.");
        }
        return parsed;
    }

    /**
     * Worker의 공개 포트는 루프백에만 묶는다. Worker는 같은 servlet 앱이지만(작업 경로가 쓰는 digest·비밀 감지·관리 포트가
     * servlet 배선에 있다) 공개 HTTP 표면이 아니다 — Nginx upstream에 없고 포트를 게시하지 않으며, 이 주소가 같은 네트워크의
     * 다른 컨테이너도 닿지 못하게 한다. 컨테이너 안의 healthcheck는 그대로 닿는다.
     */
    @Bean
    @ConditionalOnProperty(name = BokrilogRole.PROPERTY, havingValue = "worker")
    WebServerFactoryCustomizer<ConfigurableWebServerFactory> workerLoopbackAddress() {
        InetAddress loopback = loopback();
        return factory -> factory.setAddress(loopback);
    }

    /** {@code 127.0.0.1}. 이름 해석을 거치지 않는다. */
    static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException impossible) {
            // 길이 4의 주소는 언제나 받아들여진다.
            throw new IllegalStateException("루프백 주소를 만들 수 없다.", impossible);
        }
    }
}
