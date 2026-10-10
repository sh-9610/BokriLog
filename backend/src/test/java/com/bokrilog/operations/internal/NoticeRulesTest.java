package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * Story 3.5 리뷰: 공지 메시지 규칙과 버전 충돌 판정의 단위 시험. 데이터베이스 없이 규칙만 본다 — 발행 왕복은
 * {@code ServiceNoticeTest}가 본다.
 */
class NoticeRulesTest {

    @Test
    void 메시지_규칙은_끊어_쓴_숫자_IP_포트_금액_URL_금지_표현을_거절하고_짧은_시각과_버전은_받는다() {
        for (String bad : List.of("주문 1234567", "12,345,678", "1 234 567", "$12,345.67", "₩5000 차이",
                "10.0.12.5:8080", "192.168.0.1", "host:8080 장애", "100 USDT", "5,000원", "USD 30",
                "https://status.io", "example.com 참고", "경로 /api/x", "담당 a@b.io", "실시간 복구", "정확한 시각",
                "결과 보장", "수익을 내는 중", "가".repeat(201), "줄\n바꿈")) {
            assertThat(OperatorEnvelopeService.isAcceptableMessage(bad)).as(bad).isFalse();
        }
        for (String good : List.of("동기화 지연 원인 조사 중", "15:30부터 1.4.2 배포 확인 중", "10월 10일 오후 3시 재안내",
                "영향 2개 기능")) {
            assertThat(OperatorEnvelopeService.isAcceptableMessage(good)).as(good).isTrue();
        }
    }

    @Test
    void 버전_기본키_위반만_번호_충돌이다() {
        var conflict = new DuplicateKeyException("duplicate", new SQLException(
                "ERROR: duplicate key value violates unique constraint \"operations_notice_version_pkey\""));
        var other = new DuplicateKeyException("duplicate", new SQLException(
                "ERROR: duplicate key value violates unique constraint \"operations_notice_version_recovered_key\""));
        assertThat(OperatorEnvelopeService.versionConflict(conflict)).isTrue();
        assertThat(OperatorEnvelopeService.versionConflict(other)).isFalse();
    }

    /** 서버의 금지 표현 목록은 프런트엔드 카피 규칙({@code content/copyRules.ts})과 같은 목록이다. */
    @Test
    void 금지_표현_목록은_프런트엔드_카피_규칙과_같다() throws Exception {
        String rules = Files.readString(Path.of("../frontend/src/content/copyRules.ts"), StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("FORBIDDEN_COPY_WORDS = \\[(.*?)\\]", Pattern.DOTALL).matcher(rules);
        assertThat(block.find()).isTrue();
        List<String> words = new ArrayList<>();
        Matcher word = Pattern.compile("'([^']*)'").matcher(block.group(1));
        while (word.find()) {
            words.add(word.group(1));
        }
        assertThat(words).isEqualTo(OperatorEnvelopeService.FORBIDDEN_COPY_WORDS);
    }
}
