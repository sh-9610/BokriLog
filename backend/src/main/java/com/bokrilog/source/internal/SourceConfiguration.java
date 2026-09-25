package com.bokrilog.source.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * source 모듈의 배선.
 *
 * <p>{@code internal}에 있다. 이 설정이 만드는 빈은 전부 모듈 내부 타입이고 밖에서 주입받는 것은
 * 공개 API 하나뿐이다({@code SourceConnectionApi}).
 *
 * <p>{@code @ConfigurationProperties} record는 스캔이 아니라 이 자리에서 켠다. 저장소에
 * {@code @ConfigurationPropertiesScan}이 없으므로({@code AuthSecurityConfig}도 같은 이유로
 * {@code @EnableConfigurationProperties}를 쓴다) 켜는 자리가 명시적이어야 그 record가 어느 모듈의
 * 것인지 읽힌다.
 *
 * <p>key ring과 cipher를 빈으로 두는 이유는 fail-closed의 자리 때문이다. 둘 다 생성자에서 예외를
 * 던지지 않고 "쓸 수 없음"을 값으로 들고 있으므로, 구성이 빈 스택에서도 컨텍스트가 뜨고 연결
 * 기능만 공통 오류 계약으로 닫힌다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SourceProperties.class)
class SourceConfiguration {

    @Bean
    CredentialKeyRing credentialKeyRing(SourceProperties properties) {
        return new CredentialKeyRing(
                properties.credential().keys(), properties.credential().activeKeyVersion());
    }

    @Bean
    CredentialCipher credentialCipher(CredentialKeyRing keyRing) {
        return new CredentialCipher(keyRing);
    }
}
