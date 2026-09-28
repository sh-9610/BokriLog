package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public kline surface of the dev stub is unauthenticated on the dev and CI stack, so its
 * request size is closed: {@code 1 <= limit <= 1000}, {@code start <= end}, and the whole window
 * must fit in {@code limit} — duplicate-row fixtures included.
 */
class DevBybitStubMarketTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long MINUTE = 60_000L;
    private final DevBybitStub stub = new DevBybitStub("203.0.113.10");

    @Test
    void oversizedOrInvertedWindowsGetAnErrorEnvelope() {
        long end = Math.floorDiv(System.currentTimeMillis(), MINUTE) * MINUTE - 10 * MINUTE;
        assertThat(retCode(kline("BTCUSDT", end - 999 * MINUTE, end, "1001"))).isEqualTo(10001);
        assertThat(retCode(kline("BTCUSDT", end - 999 * MINUTE, end, "0"))).isEqualTo(10001);
        assertThat(retCode(kline("BTCUSDT", end, end - MINUTE, "1000"))).isEqualTo(10001);
        assertThat(retCode(kline("BTCUSDT", end - 1000 * MINUTE, end, "1000")))
                .as("1001 minutes do not fit a 1000 limit").isEqualTo(10001);
        assertThat(retCode(kline("BTCUSDT", end - 5 * MINUTE, end, "5"))).isEqualTo(10001);
        JsonNode full = body(kline("BTCUSDT", end - 999 * MINUTE, end, "1000"));
        assertThat(full.get("retCode").asInt()).isZero();
        assertThat(full.get("result").get("list").size()).isEqualTo(1000);
    }

    @Test
    void duplicateRowFixturesStayWithinTheLimit() {
        long end = Math.floorDiv(System.currentTimeMillis(), MINUTE) * MINUTE - 10 * MINUTE;
        for (String symbol : new String[] {"DUPUSDT", "VOLUSDT"}) {
            JsonNode page = body(kline(symbol, end - 999 * MINUTE, end, "1000"));
            assertThat(page.get("retCode").asInt()).isZero();
            assertThat(page.get("result").get("list").size()).as(symbol).isEqualTo(1000);
            JsonNode small = body(kline(symbol, end - 2 * MINUTE, end, "3"));
            assertThat(small.get("result").get("list").size()).as(symbol).isEqualTo(3);
        }
    }

    private DevBybitStub.Response kline(String symbol, long start, long end, String limit) {
        return stub.handle(DevBybitStub.KLINE_PATH, Map.of(), Map.of("category", "spot", "symbol", symbol,
                "interval", "1", "start", Long.toString(start), "end", Long.toString(end), "limit", limit));
    }

    private static int retCode(DevBybitStub.Response response) {
        return body(response).get("retCode").asInt();
    }

    private static JsonNode body(DevBybitStub.Response response) {
        return JSON.readTree(response.body());
    }
}
