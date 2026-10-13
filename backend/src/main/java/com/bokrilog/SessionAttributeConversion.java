package com.bokrilog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * 세션 속성 직렬화의 변환기 — Spring Session JDBC가 {@code ATTRIBUTE_BYTES}를 쓰고 읽는 자리(운영 command 공통화 SPEC의
 * "배포 전 무장").
 *
 * <p>쓰기는 기본과 같은 Java 직렬화다. <b>읽기만 다르다</b>: 이 빌드가 풀 수 없는 바이트(배포 전 인스턴스가 쓴 무장 — 지금
 * 빌드에 없는 클래스, record 생성자의 불변식을 어기는 옛 모양 — 나 깨진 값)는 예외 대신 표지 {@link UnreadableSessionValue}로
 * 읽는다. 기본 변환기는 그 자리에서 변환 예외를 던지는데, Spring Session의 {@code HttpSession} 어댑터는 속성을 쓰거나 지우기
 * 전에도 옛 값을 먼저 읽으므로(바인딩 리스너 통지) 그 속성 하나가 그 세션의 요청을 500으로 만들고 덮어쓸 수도 지울 수도 없게
 * 된다. {@code null}(없는 속성)로 읽지 않는 이유는 재인증 표지처럼 있다는 사실이 판정인 속성이 조용히 풀리기 때문이다
 * (fail-open) — 표지를 받은 각 판독이 자기 규칙으로 접는다({@link UnreadableSessionValue}). 경고는 그 판독이 세션·속성마다 한
 * 번 남긴다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다(세션은 API 프로세스에만 있다).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
class SessionAttributeConversion {

    /** Spring Session JDBC가 이 이름의 변환기를 찾는다({@code JdbcHttpSessionConfiguration}). */
    @Bean("springSessionConversionService")
    ConversionService springSessionConversionService() {
        GenericConversionService conversions = new GenericConversionService();
        DeserializingConverter deserializer = new DeserializingConverter(SessionAttributeConversion.class.getClassLoader());
        conversions.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversions.addConverter(byte[].class, Object.class, source -> {
            try {
                return deserializer.convert(source);
            } catch (SerializationFailedException unreadable) {
                Throwable cause = unreadable.getCause() == null ? unreadable : unreadable.getCause();
                return new UnreadableSessionValue(cause.getClass().getSimpleName());
            }
        });
        return conversions;
    }

    /**
     * 세션의 주체 이름 색인({@code PRINCIPAL_NAME})을 저장할 때마다 계산하는 resolver. 기본 resolver는 로그인 문맥에 SpEL
     * {@code authentication?.name}을 적용하므로, 로그인 문맥이 읽을 수 없는 값이면 저장마다 SpEL 예외(500)가 된다. 여기서는
     * 같은 규칙을 타입으로 읽는다 — {@code SecurityContext}의 인증 이름이고, 읽을 수 없는 값은 주체가 없는 것(미인증과 같다)이다.
     */
    @Bean
    org.springframework.session.config.SessionRepositoryCustomizer<
            org.springframework.session.jdbc.JdbcIndexedSessionRepository> principalIndexCustomizer() {
        return repository -> repository.setIndexResolver(
                new org.springframework.session.DelegatingIndexResolver<>(new ReadablePrincipalNameIndexResolver()));
    }

    /** 읽을 수 없는 로그인 문맥·주체 이름 속성을 주체 없음으로 접는 주체 이름 색인. */
    static final class ReadablePrincipalNameIndexResolver
            extends org.springframework.session.PrincipalNameIndexResolver<org.springframework.session.Session> {

        @Override
        public @org.jspecify.annotations.Nullable String resolveIndexValueFor(
                org.springframework.session.Session session) {
            Object principal = session.getAttribute(getIndexName());
            if (principal instanceof String name) {
                return name;
            }
            Object context = session.getAttribute(
                    org.springframework.security.web.context.HttpSessionSecurityContextRepository
                            .SPRING_SECURITY_CONTEXT_KEY);
            if (context instanceof org.springframework.security.core.context.SecurityContext security
                    && security.getAuthentication() != null) {
                return security.getAuthentication().getName();
            }
            return null;
        }
    }
}
