package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.AdjustmentQuery;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The opaque cursor of the adjustment history (Story 2.10): a scope digest and the last sequence of the page
 * before, nothing else — no amount, no time, no identifier in plain text.
 *
 * <p>The digest binds the cursor to one scope: the requesting user's internal id, the three values of the
 * shown pointer, the direction filter and the ordering policy. A cursor made for another user, another
 * version, another filter or another ordering fails the comparison and names nothing, exactly like a
 * malformed one. It is not signed: the digest already carries the requester's id, so another user's cursor
 * never matches, and a user who edits the sequence of their own cursor still reads only their own rows. No
 * secret key, and no new configuration, is needed.
 *
 * <p>The encoding is fixed-length and canonical — one version byte, the 32-byte digest and the four-byte
 * sequence, base64url without padding — and a cursor is accepted only if it re-encodes to itself, so there is
 * one spelling per cursor.
 */
final class AdjustmentCursor {
    /**
     * The ordering the sequence realizes: the version's {@code sequence}, which is the calculation's own
     * {@code (event time, provider account, source id)} order. One conversion version per event within a
     * calculation version, so the third key of the approved order never ties. That premise holds while a plan
     * has one official version (before Story 2.11); Stories 2.11, 2.12 and 2.15 must revisit it.
     */
    static final String ORDER_POLICY = "ADJUSTMENT-ORDER-1.0";

    private static final byte VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int BYTES = 1 + DIGEST_BYTES + Integer.BYTES;
    /** {@code ceil(37 * 8 / 6)} characters of base64url without padding. */
    private static final int LENGTH = (BYTES * 8 + 5) / 6;
    private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9_-]{" + LENGTH + "}");

    private AdjustmentCursor() {}

    /** The cursor that continues after {@code lastSequence} in this user's scope. */
    static String encode(UUID user, AdjustmentQuery scope, int lastSequence) {
        if (lastSequence <= 0) throw new IllegalArgumentException("sequence");
        ByteBuffer bytes = ByteBuffer.allocate(BYTES).put(VERSION).put(digest(user, scope)).putInt(lastSequence);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /**
     * The last sequence a cursor names in this user's scope, or empty when it names nothing here: malformed,
     * not canonical, of another version of the format, or made for another scope.
     */
    static OptionalInt decode(UUID user, AdjustmentQuery scope, String cursor) {
        if (!TEXT.matcher(cursor).matches()) return OptionalInt.empty();
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException malformed) {
            return OptionalInt.empty();
        }
        if (raw.length != BYTES || raw[0] != VERSION) return OptionalInt.empty();
        ByteBuffer bytes = ByteBuffer.wrap(raw, 1, BYTES - 1);
        byte[] digest = new byte[DIGEST_BYTES];
        bytes.get(digest);
        int sequence = bytes.getInt();
        if (sequence <= 0 || !MessageDigest.isEqual(digest, digest(user, scope))) return OptionalInt.empty();
        // One spelling per cursor: trailing bits that decode to the same bytes are another spelling.
        if (!encode(user, scope, sequence).equals(cursor)) return OptionalInt.empty();
        return OptionalInt.of(sequence);
    }

    /**
     * The scope digest: named fields, each prefixed with its length (never bare separators, so no value can
     * pass for two). The cursor itself never travels back into the digest.
     */
    private static byte[] digest(UUID user, AdjustmentQuery scope) {
        StringBuilder payload = new StringBuilder();
        field(payload, "kind", "adjustment-cursor-1");
        field(payload, "user", user.toString());
        field(payload, "plan", scope.planId().toString());
        field(payload, "pointerVersion", Long.toString(scope.pointerVersion()));
        field(payload, "calculation", scope.calculationId().toString());
        field(payload, "direction", scope.direction().name());
        field(payload, "order", ORDER_POLICY);
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void field(StringBuilder payload, String name, String value) {
        payload.append(name).append('=').append(value.length()).append(':').append(value).append(';');
    }
}
