package com.bokrilog.operations;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 계정 삭제의 복원 방지 tombstone(Story 3.10, {@code DELETION-TOMBSTONE-1.0}). 표는 {@code V25} outbox
 * {@code operations_deletion_tombstone}이다.
 *
 * <p><b>기록은 삭제와 한 트랜잭션이다.</b> 루트 {@code AccountErasure}가 삭제 전환 뒤 {@link #record}를 부른다 — 호출자 트랜잭션
 * 안에서만 쓴다. 그래서 기록 없는 탈퇴가 없다.
 *
 * <p><b>권위는 외부 사본이다.</b> 일회성 {@code tombstone-export} 역할이 {@link #export}로 실을 행 전체를 파일 하나({@link #render})로
 * 쓰고, 운영 호스트가 그 파일을 tombstone 전용 age key로 암호화해 OCI 밖 저장소에 올린다. 복원은 그 사본을 {@link #read}로 읽어 서비스를
 * 열기 전에 적용한다 — 백업 안의 outbox는 백업 뒤의 삭제를 모르므로 권위가 아니다(대조에만 쓴다).
 *
 * <p><b>외부 수명.</b> 사본 object는 {@link #EXPORT_MARGIN}(24시간)만 남기고(내보내기가 그보다 오래된 사본을 지운다), 내보내기는 파기
 * 기한이 지금 + 24시간보다 뒤인 행만 싣는다. 그래서 마지막으로 실린 사본도 {@code requested_at + 35일} 전에 사라진다 — 외부 사본의
 * tombstone이 35일을 넘겨 남지 않는다. 한편 tombstone은 요청 뒤 34일까지 사본에 실리고, 복원은 30일을 넘긴 generation을 받지 않으며 그
 * 계정이 든 generation은 요청보다 앞서므로, 그 계정을 되살릴 수 있는 복원은 언제나 tombstone이 실린 사본을 본다.
 *
 * <p>행은 사용자 ID의 keyed digest(전용 키, 감사 digest 키와 다르다)·요청 ID·요청 시각·파기 기한(+35일)뿐이다. 원식별자·금융값·
 * 자격정보·소셜 식별자는 어떤 칸에도 없다. 파일 머리에는 digest 키의 ID({@link #keyId})가 실린다 — 키 회전은 아직 지원하지 않으며, 다른
 * 키의 사본은 복원이 거부한다.
 */
public interface DeletionTombstoneApi {

    /** 정책 버전. 파일 머리에 실린다. */
    String POLICY_VERSION = "DELETION-TOMBSTONE-1.0";

    /** 파기 기한까지의 길이. 백업 최대 수명(30일)보다 길다. */
    Duration RETENTION = Duration.ofHours(840);

    /** 외부 사본의 보존 길이이자 내보내기의 기한 여유. 위 수명 계산의 근거다. */
    Duration EXPORT_MARGIN = Duration.ofHours(24);

    /** 삭제 요청 ID의 모양. */
    Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** keyed digest의 모양(소문자 16진 64자). */
    Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    /** digest 키 ID의 모양(소문자 16진 16자). */
    Pattern KEY_ID = Pattern.compile("[0-9a-f]{16}");

    /** 내보내기 파일 첫 줄의 머리. 뒤에 {@code " key=<키 ID> exported=<ISO-8601>"}이 붙는다. 복원은 이 머리가 없는 파일을 tombstone으로 읽지 않는다. */
    String FILE_HEADER = "# bokrilog deletion tombstones " + POLICY_VERSION;

    /**
     * tombstone 하나.
     *
     * @param userDigest 사용자 ID의 keyed digest
     * @param requestId 삭제 요청 ID
     * @param requestedAt 요청 시각(ms)
     * @param destroyAfter 파기 기한(요청 + {@link #RETENTION})
     */
    record Tombstone(String userDigest, String requestId, Instant requestedAt, Instant destroyAfter) {

        public Tombstone {
            if (userDigest == null || !DIGEST.matcher(userDigest).matches()) {
                throw new IllegalArgumentException("tombstone digest의 모양이 맞지 않는다.");
            }
            if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
                throw new IllegalArgumentException("삭제 요청 ID의 모양이 맞지 않는다.");
            }
            if (requestedAt == null || destroyAfter == null || !destroyAfter.equals(requestedAt.plus(RETENTION))) {
                throw new IllegalArgumentException("파기 기한은 요청 시각 + 35일이어야 한다.");
            }
        }

        /** 파일의 한 줄: {@code digest request_id requested_at destroy_after}(ISO-8601 UTC). */
        public String line() {
            return this.userDigest + " " + this.requestId + " " + this.requestedAt + " " + this.destroyAfter;
        }

        /**
         * 파일의 한 줄을 푼다.
         *
         * @throws IllegalArgumentException 모양 밖이다. 메시지에 값을 담지 않는다
         */
        public static Tombstone parse(String line) {
            String[] fields = line.strip().split(" ", -1);
            if (fields.length != 4) {
                throw new IllegalArgumentException("tombstone 줄의 칸 수가 4가 아니다.");
            }
            try {
                return new Tombstone(fields[0], fields[1], Instant.parse(fields[2]), Instant.parse(fields[3]));
            } catch (DateTimeParseException unreadable) {
                throw new IllegalArgumentException("tombstone 줄의 시각을 읽을 수 없다.");
            }
        }
    }

    /**
     * 내보내기 파일 하나.
     *
     * @param keyId 그 digest를 만든 키의 ID
     * @param exportedAt 사본을 만든 시각(초)
     * @param tombstones 실린 tombstone
     */
    record TombstoneFile(String keyId, Instant exportedAt, List<Tombstone> tombstones) {

        public TombstoneFile {
            if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
                throw new IllegalArgumentException("tombstone 키 ID의 모양이 맞지 않는다.");
            }
            if (exportedAt == null) {
                throw new IllegalArgumentException("tombstone 사본의 시각이 없다.");
            }
            tombstones = List.copyOf(tombstones);
        }
    }

    /**
     * 한 번의 내보내기.
     *
     * @param keyId 이 역할의 digest 키 ID(파일 머리)
     * @param live 실을 tombstone(파기 기한이 지금 + {@link #EXPORT_MARGIN}보다 뒤, 요청 시각 순)
     * @param purged 이번에 지운 기한 지난 행 수
     */
    record Export(String keyId, List<Tombstone> live, int purged) {}

    /**
     * 그 계정의 tombstone을 기록한다. <b>호출자 트랜잭션 안에서만</b> 쓴다 — 삭제와 한 커밋이어야 한다. 요청 시각은 이 문장의 DB
     * 시각이다. 같은 계정의 행이 이미 있으면 그대로 둔다(멱등).
     *
     * @return 이번에 행을 더했는가
     * @throws IllegalStateException 트랜잭션 밖이거나, 이 역할에 tombstone digest 키가 없거나, 요청 ID가 다른 계정에 쓰였다
     */
    boolean record(UUID userId, String requestId);

    /**
     * 외부 사본의 tombstone 하나를 이 DB의 outbox로 옮긴다(복원 적용). <b>호출자 트랜잭션 안에서만</b> 쓴다. 시각은 사본 그대로다.
     * 같은 계정의 행이 이미 있으면 그대로 둔다 — 복원된 DB가 다음 내보내기에서 그 tombstone을 잃지 않게 한다.
     *
     * @return 이번에 행을 더했는가
     * @throws IllegalStateException 같은 요청 ID의 행이 다른 digest로 이미 있다(사본과 DB가 어긋난다)
     */
    boolean carry(Tombstone tombstone);

    /** 기한이 지난 행을 지우고 실을 행을 읽는다(트랜잭션 하나). 시각의 권위는 DB다. */
    Export export();

    /** 지금 outbox에 있는 행 가운데 파기 기한이 지나지 않은 것(지우지 않는다). 복원 적용이 외부 사본과 대조한다. */
    List<Tombstone> outbox();

    /**
     * {@link #outbox} 가운데 이 DB 계보의 삭제가 {@link #record}로 쓴 행(복원 적용이 {@link #carry}로 옮긴 행 제외). 그 계정은 이 DB에
     * 있어야 한다 — 복원 적용이 지금 키로 계산한 계정 digest와 맞춰 키 불일치를 잡는다.
     */
    List<Tombstone> recordedOutbox();

    /**
     * 이 역할의 digest 키 ID(키에서 나온 16진 16자 — 키를 되짚을 수 없다).
     *
     * @throws IllegalStateException 이 역할에 tombstone digest 키가 없다
     */
    String keyId();

    /**
     * 그 사용자 ID들 가운데 tombstone과 맞는 것. digest는 이 역할의 tombstone 키로 계산한다(사본은 원문 ID를 담지 않는다).
     *
     * @throws IllegalStateException 이 역할에 tombstone digest 키가 없다
     */
    Map<UUID, Tombstone> matching(Collection<UUID> userIds, Collection<Tombstone> tombstones);

    /** 내보내기 파일의 본문. 머리 한 줄({@code FILE_HEADER key=<키 ID> exported=<시각>}) 뒤에 tombstone이 한 줄씩이다. */
    static String render(String keyId, Instant exportedAt, List<Tombstone> tombstones) {
        TombstoneFile file = new TombstoneFile(keyId, exportedAt, tombstones);
        StringBuilder text = new StringBuilder(FILE_HEADER).append(" key=").append(file.keyId()).append(" exported=")
                .append(file.exportedAt()).append('\n');
        for (Tombstone tombstone : file.tombstones()) {
            text.append(tombstone.line()).append('\n');
        }
        return text.toString();
    }

    /**
     * 내보내기 파일을 푼다. 첫 줄이 {@code FILE_HEADER key=<키 ID> exported=<시각>}이 아니면 tombstone 파일이 아니다. 빈 줄은 건너뛴다.
     *
     * @throws IllegalArgumentException 머리가 없거나 한 줄이라도 모양 밖이다
     */
    static TombstoneFile read(List<String> lines) {
        String prefix = FILE_HEADER + " key=";
        if (lines.isEmpty() || !lines.getFirst().strip().startsWith(prefix)) {
            throw new IllegalArgumentException("tombstone 파일의 머리가 없다.");
        }
        String[] header = lines.getFirst().strip().substring(prefix.length()).split(" exported=", -1);
        if (header.length != 2) {
            throw new IllegalArgumentException("tombstone 파일 머리에 시각이 없다.");
        }
        String keyId = header[0];
        Instant exportedAt;
        try {
            exportedAt = Instant.parse(header[1]);
        } catch (DateTimeParseException unreadable) {
            throw new IllegalArgumentException("tombstone 파일 머리의 시각을 읽을 수 없다.");
        }
        List<Tombstone> tombstones = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isBlank()) {
                tombstones.add(Tombstone.parse(line));
            }
        }
        return new TombstoneFile(keyId, exportedAt, tombstones);
    }
}
