package com.bokrilog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 작업자 스케줄링의 유일한 스위치. 일회성 역할({@code migrate}·{@code deploy-audit})은 웹 서버가 없어 여기 오지 않고, API 역할
 * ({@code bokrilog.role=api}, Story 3.8)도 켜지 않는다 — dispatch·heartbeat·주기 측정은 {@code worker}와 {@code all}만 한다
 * ({@link BokrilogRole}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(BokrilogRole.Schedules.class)
@EnableScheduling
class FirstSnapshotSchedulingConfig {}
