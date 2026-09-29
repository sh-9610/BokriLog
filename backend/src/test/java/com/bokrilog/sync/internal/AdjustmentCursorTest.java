package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.sync.SyncJobApi.AdjustmentDirection;
import com.bokrilog.sync.SyncJobApi.AdjustmentQuery;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The adjustment history cursor (Story 2.10) names a place only in the scope it was made for: this user, this
 * pointer, this filter. Everything else — another user, version, calculation, plan or direction, an edited
 * byte, another spelling of the same bytes, garbage — names nothing.
 */
class AdjustmentCursorTest {
    private static final UUID USER = UUID.fromString("0d3f6a0e-6a1b-4c52-9a7e-3e2b1c0d9f8a");
    private static final UUID PLAN = UUID.fromString("5d1e7c2a-8b3f-4e6a-9c0d-1f2e3a4b5c6d");
    private static final UUID CALCULATION = UUID.fromString("9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b");

    private static AdjustmentQuery scope(AdjustmentDirection direction) {
        return new AdjustmentQuery(PLAN, 1, CALCULATION, direction, null);
    }

    @Test
    void aCursorNamesItsSequenceInItsOwnScopeOnly() {
        String cursor = AdjustmentCursor.encode(USER, scope(AdjustmentDirection.ALL), 20);
        assertThat(cursor).matches("[A-Za-z0-9_-]{50}");
        assertThat(AdjustmentCursor.decode(USER, scope(AdjustmentDirection.ALL), cursor)).isEqualTo(OptionalInt.of(20));
        // The same scope named again (a new query object) is the same scope; the cursor field itself is not part of it.
        assertThat(AdjustmentCursor.decode(USER, new AdjustmentQuery(PLAN, 1, CALCULATION, AdjustmentDirection.ALL,
                cursor), cursor)).isEqualTo(OptionalInt.of(20));

        assertThat(AdjustmentCursor.decode(UUID.randomUUID(), scope(AdjustmentDirection.ALL), cursor))
                .as("another user").isEmpty();
        assertThat(AdjustmentCursor.decode(USER, scope(AdjustmentDirection.IN), cursor)).as("another filter").isEmpty();
        assertThat(AdjustmentCursor.decode(USER, new AdjustmentQuery(PLAN, 2, CALCULATION, AdjustmentDirection.ALL,
                null), cursor)).as("another pointer version").isEmpty();
        assertThat(AdjustmentCursor.decode(USER, new AdjustmentQuery(PLAN, 1, UUID.randomUUID(),
                AdjustmentDirection.ALL, null), cursor)).as("another calculation").isEmpty();
        assertThat(AdjustmentCursor.decode(USER, new AdjustmentQuery(UUID.randomUUID(), 1, CALCULATION,
                AdjustmentDirection.ALL, null), cursor)).as("another plan").isEmpty();
    }

    @Test
    void anEditedMalformedOrOtherwiseSpelledCursorNamesNothing() {
        AdjustmentQuery scope = scope(AdjustmentDirection.OUT);
        String cursor = AdjustmentCursor.encode(USER, scope, 7);
        byte[] raw = Base64.getUrlDecoder().decode(cursor);

        // The sequence edited in place: the digest still matches, so it is the user's own scope and a real place.
        byte[] moved = raw.clone();
        moved[moved.length - 1] = 9;
        String edited = Base64.getUrlEncoder().withoutPadding().encodeToString(moved);
        assertThat(AdjustmentCursor.decode(USER, scope, edited)).isEqualTo(OptionalInt.of(9));

        byte[] digest = raw.clone();
        digest[5] ^= 1;
        assertThat(AdjustmentCursor.decode(USER, scope,
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest))).as("a digest byte").isEmpty();
        byte[] version = raw.clone();
        version[0] = 2;
        assertThat(AdjustmentCursor.decode(USER, scope,
                Base64.getUrlEncoder().withoutPadding().encodeToString(version))).as("another format").isEmpty();
        byte[] zero = raw.clone();
        zero[zero.length - 1] = 0;
        assertThat(AdjustmentCursor.decode(USER, scope,
                Base64.getUrlEncoder().withoutPadding().encodeToString(zero))).as("sequence 0").isEmpty();

        // The last character carries two unused bits: another character with the same six bits' prefix is
        // another spelling of the same bytes, and only one spelling is accepted.
        char last = cursor.charAt(cursor.length() - 1);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int index = alphabet.indexOf(last);
        String respelled = cursor.substring(0, cursor.length() - 1) + alphabet.charAt(index ^ 1);
        assertThat(Base64.getUrlDecoder().decode(respelled)).as("the same bytes").isEqualTo(raw);
        assertThat(AdjustmentCursor.decode(USER, scope, respelled)).as("another spelling").isEmpty();

        for (String garbage : new String[] {"", "x", cursor + "A", cursor.substring(1), "+" + cursor.substring(1),
                "=".repeat(50), Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("adjustment".repeat(4).getBytes(StandardCharsets.UTF_8))}) {
            assertThat(AdjustmentCursor.decode(USER, scope, garbage)).as(garbage).isEmpty();
        }
        assertThatThrownBy(() -> AdjustmentCursor.encode(USER, scope, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCursorCarriesNoIdentifierInPlainText() {
        String cursor = AdjustmentCursor.encode(USER, scope(AdjustmentDirection.ALL), 20);
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.ISO_8859_1);
        for (String id : new String[] {USER.toString(), PLAN.toString(), CALCULATION.toString(), "ALL",
                AdjustmentCursor.ORDER_POLICY}) {
            assertThat(cursor).doesNotContain(id);
            assertThat(decoded).doesNotContain(id);
        }
    }
}
