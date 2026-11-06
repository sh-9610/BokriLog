package com.bokrilog.operations;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 계정 삭제의 복원 방지 tombstone(Story 3.10, Story 5.3 — {@code DELETION-TOMBSTONE-1.0}). 표는 {@code V25} outbox
 * {@code operations_deletion_tombstone}과 {@code V32}의 영향 generation·내보내기 기록이다.
 *
 * <p><b>기록은 삭제 실행의 첫 단계다(Story 5.3).</b> 사용자 요청의 단계 모드는 Worker가 실행을 처음 집는 단계({@code CREDENTIALS} 앞, 같은
 * fencing)에서 요청 시각(V30)으로 {@link #record(UUID, String, Instant)}하고, 마지막 단계가 {@link #close}로 영향 generation 집합을 닫고 보존을
 * 늘린다. 단일 모드(검증용 삭제 표본·복원 적용)는 마지막 단계에서 기록과 닫기를 한 트랜잭션에 한다. 모두 호출자 트랜잭션 안에서만 쓴다.
 *
 * <p><b>권위는 외부 사본이다.</b> 일회성 {@code tombstone-export} 역할이 {@link #export}로 실을 행 전체를 파일 하나({@link #render})로
 * 쓰고, 운영 호스트가 그 파일을 tombstone 전용 age key로 암호화해 OCI 밖 저장소에 올린 뒤 같은 역할의 기록 모드가 {@link #confirmExport}로
 * 성공을 남긴다. 복원은 그 사본을 {@link #read}로 읽어 서비스를 열기 전에 적용한다 — 백업 안의 outbox는 백업 뒤의 삭제를 모르므로 권위가
 * 아니다(대조에만 쓴다).
 *
 * <p><b>외부 수명.</b> 사본 object는 {@link #EXPORT_MARGIN}(24시간)만 남는다. 보존 기한은 요청 + 35일이고 집합이 닫히면
 * {@code max(요청, 운영 완료) + 35일}로 늘어난다(줄지 않는다). <b>백업 파기가 완료({@code DESTROYED})된 행만</b> 보존 기한으로 지워지고
 * 내보내기에서 빠진다(기한이 지금 + 24시간보다 앞서면) — 완료가 아닌 행(열린 집합·파기 미완료·초과)은 기한이 지나도 DB에 남고 외부 사본에도
 * 실린다(그 계정이 든 백업이 남아 있을 수 있다). 계정이 든 마지막 generation은 운영 완료 + 1시간 전에 dump를 시작했고, 복원은 30일을 넘긴
 * generation을 받지 않으므로, 그 계정을 되살릴 수 있는 복원은 언제나 tombstone이 실린 사본을 본다.
 *
 * <p>행은 사용자 ID의 keyed digest(전용 키, 감사 digest 키와 다르다)·요청 ID·요청 시각·보존 기한·백업 파기 기한(요청 + 30일)·파기 상태·영향
 * generation 집합(열림·닫힘, generation마다 저장소별 파기 여부)뿐이다. 정책 버전은 파일 머리({@link #FILE_HEADER})에 한 번 실리고 DB 행에는
 * 열로 남는다. 원식별자·금융값·자격정보·소셜 식별자는 어떤 칸에도 없다. 파일 머리에는 digest 키의 ID({@link #keyId})도 실린다 — 키 회전은
 * 아직 지원하지 않으며, 다른 키의 사본은 복원이 거부한다.
 *
 * <p><b>감사.</b> 기록·보존 연장·내보내기·만료 삭제·복원 적용은 감사 원장에 SYSTEM 행({@link #AUDIT_COMMAND})을 남긴다 — 수와 code뿐이고
 * digest·요청 ID·사용자 ID는 없다. 백업 파기 완료·초과는 {@link BackupDestructionApi}가 같은 command로 남긴다.
 */
public interface DeletionTombstoneApi {

    /** 정책 버전. 파일 머리와 행에 실린다. */
    String POLICY_VERSION = "DELETION-TOMBSTONE-1.0";

    /** 보존 기한까지의 최소 길이. 백업 최대 수명(30일)보다 길다. */
    Duration RETENTION = Duration.ofHours(840);

    /** 백업 파기 기한: 요청 + 30일. 이때까지 영향 generation이 두 저장소에서 모두 파기돼야 한다. */
    Duration BACKUP_DEADLINE = Duration.ofHours(720);

    /** 외부 사본의 보존 길이이자 내보내기의 기한 여유. 위 수명 계산의 근거다. */
    Duration EXPORT_MARGIN = Duration.ofHours(24);

    /** 감사 행의 command. */
    String AUDIT_COMMAND = "tombstone";

    /** 감사 행의 scope. */
    String AUDIT_SCOPE = "DELETION";

    /** 삭제 요청 ID의 모양. */
    Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** keyed digest의 모양(소문자 16진 64자). */
    Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    /** digest 키 ID의 모양(소문자 16진 16자). */
    Pattern KEY_ID = Pattern.compile("[0-9a-f]{16}");

    /** 백업 generation ID의 모양({@code BackupRecordApi.GENERATION_ID}와 같다). */
    Pattern GENERATION_ID = Pattern.compile("gen_[0-9]{8}");

    /** 내보내기 파일 첫 줄의 머리. 뒤에 {@code " key=<키 ID> exported=<ISO-8601>"}이 붙는다. 복원은 이 머리가 없는 파일을 tombstone으로 읽지 않는다. */
    String FILE_HEADER = "# bokrilog deletion tombstones " + POLICY_VERSION;

    /** 백업 파기 상태. */
    enum DestructionStatus {
        /** 영향 generation의 파기가 아직 모두 확인되지 않았다. */
        PENDING,
        /** 백업 파기 기한(요청 + 30일)이 지났는데 완료가 아니다. 완료로 표시하지 않는다. */
        OVERDUE,
        /** 집합이 닫혔고 그 모든 generation이 두 저장소에서 파기됐다 — 백업 파기 완료. */
        DESTROYED
    }

    /** 영향 generation 집합의 상태: 운영 데이터 삭제 완료 전에는 열려 있다. */
    enum GenerationSet {
        OPEN, CLOSED
    }

    /** 사본 줄의 generation 한 칸: {@code gen_YYYYMMDD:<oci><offsite>}, 저장소마다 {@code P}(파기 확인) 또는 {@code -}. */
    Pattern AFFECTED_GENERATION = Pattern.compile("(gen_[0-9]{8}):([P-])([P-])");

    /**
     * 영향 generation 하나와 저장소별 파기 확인.
     *
     * @param ociPurged OCI에서 파기가 기록됐다
     * @param offsitePurged OCI 밖 저장소에서 파기가 기록됐다
     */
    record AffectedGeneration(String id, boolean ociPurged, boolean offsitePurged) {

        public AffectedGeneration {
            if (id == null || !GENERATION_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("generation ID의 모양이 맞지 않는다.");
            }
        }

        /** 사본 줄의 칸. */
        public String token() {
            return this.id + ":" + (this.ociPurged ? "P" : "-") + (this.offsitePurged ? "P" : "-");
        }

        /**
         * 사본 줄의 칸을 푼다.
         *
         * @throws IllegalArgumentException 모양 밖이다
         */
        public static AffectedGeneration parse(String token) {
            var matcher = AFFECTED_GENERATION.matcher(token == null ? "" : token);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("영향 generation 칸의 모양이 맞지 않는다.");
            }
            return new AffectedGeneration(matcher.group(1), "P".equals(matcher.group(2)), "P".equals(matcher.group(3)));
        }
    }

    /**
     * tombstone 하나.
     *
     * @param userDigest 사용자 ID의 keyed digest
     * @param requestId 삭제 요청 ID
     * @param requestedAt 요청 시각(ms)
     * @param destroyAfter 보존 기한(요청 + {@link #RETENTION} 이상)
     * @param status 백업 파기 상태
     * @param set 영향 generation 집합이 닫혔는가
     * @param generations 영향 generation(ID 순, ID마다 하나)
     */
    record Tombstone(String userDigest, String requestId, Instant requestedAt, Instant destroyAfter,
            DestructionStatus status, GenerationSet set, List<AffectedGeneration> generations) {

        public Tombstone {
            if (userDigest == null || !DIGEST.matcher(userDigest).matches()) {
                throw new IllegalArgumentException("tombstone digest의 모양이 맞지 않는다.");
            }
            if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
                throw new IllegalArgumentException("삭제 요청 ID의 모양이 맞지 않는다.");
            }
            if (requestedAt == null || destroyAfter == null || destroyAfter.isBefore(requestedAt.plus(RETENTION))) {
                throw new IllegalArgumentException("보존 기한은 요청 시각 + 35일보다 짧을 수 없다.");
            }
            if (status == null || set == null) {
                throw new IllegalArgumentException("tombstone의 상태가 없다.");
            }
            if (status == DestructionStatus.DESTROYED && set != GenerationSet.CLOSED) {
                throw new IllegalArgumentException("백업 파기 완료는 닫힌 집합에만 있다.");
            }
            if (generations == null) {
                throw new IllegalArgumentException("영향 generation 목록이 없다.");
            }
            TreeMap<String, AffectedGeneration> sorted = new TreeMap<>();
            for (AffectedGeneration generation : generations) {
                if (generation == null || sorted.putIfAbsent(generation.id(), generation) != null) {
                    throw new IllegalArgumentException("영향 generation이 비었거나 겹친다.");
                }
            }
            generations = List.copyOf(sorted.values());
            if (status == DestructionStatus.DESTROYED
                    && !generations.stream().allMatch(g -> g.ociPurged() && g.offsitePurged())) {
                // 백업 파기 완료는 모든 영향 generation이 두 저장소에서 파기됐다는 뜻이다 — 모순된 줄을 받지 않는다.
                throw new IllegalArgumentException("백업 파기 완료인데 두 저장소 파기가 확인되지 않은 generation이 있다.");
            }
        }

        /**
         * Story 3.10 모양의 tombstone(집합이 요청 시각에 닫혔고 파기 상태를 모른다). 이 스토리 전의 외부 사본 줄이 이 모양이다.
         */
        public Tombstone(String userDigest, String requestId, Instant requestedAt, Instant destroyAfter) {
            this(userDigest, requestId, requestedAt, destroyAfter, DestructionStatus.PENDING, GenerationSet.CLOSED,
                    List.of());
        }

        /** 백업 파기 기한(요청 + {@link #BACKUP_DEADLINE}). */
        public Instant backupDeadline() {
            return this.requestedAt.plus(BACKUP_DEADLINE);
        }

        /**
         * 파일의 한 줄(ISO-8601 UTC): {@code digest request_id requested_at destroy_after backup_deadline status set generations}.
         * generation이 없으면 {@code -}, 있으면 {@code gen_YYYYMMDD:<oci><offsite>}(파기 확인 {@code P}, 아니면 {@code -})를 쉼표로 잇는다. 앞 네 칸은 Story 3.10 모양 그대로다(내보내기의 줄어듦 검사가 1·4칸을 읽는다).
         */
        public String line() {
            return this.userDigest + " " + this.requestId + " " + this.requestedAt + " " + this.destroyAfter + " "
                    + backupDeadline() + " " + this.status.name() + " " + this.set.name() + " "
                    + (this.generations.isEmpty() ? "-"
                            : String.join(",", this.generations.stream().map(AffectedGeneration::token).toList()));
        }

        /**
         * 파일의 한 줄을 푼다. 여덟 칸(이 스토리)과 네 칸(Story 3.10 — 요청 시각에 닫힌 집합, 상태 모름)을 읽는다.
         *
         * @throws IllegalArgumentException 모양 밖이다. 메시지에 값을 담지 않는다
         */
        public static Tombstone parse(String line) {
            String[] fields = line.strip().split(" ", -1);
            if (fields.length != 4 && fields.length != 8) {
                throw new IllegalArgumentException("tombstone 줄의 칸 수가 4도 8도 아니다.");
            }
            Instant requestedAt;
            Instant destroyAfter;
            try {
                requestedAt = Instant.parse(fields[2]);
                destroyAfter = Instant.parse(fields[3]);
            } catch (DateTimeParseException unreadable) {
                throw new IllegalArgumentException("tombstone 줄의 시각을 읽을 수 없다.");
            }
            if (fields.length == 4) {
                return new Tombstone(fields[0], fields[1], requestedAt, destroyAfter);
            }
            Instant deadline;
            try {
                deadline = Instant.parse(fields[4]);
            } catch (DateTimeParseException unreadable) {
                throw new IllegalArgumentException("tombstone 줄의 파기 기한을 읽을 수 없다.");
            }
            if (!deadline.equals(requestedAt.plus(BACKUP_DEADLINE))) {
                throw new IllegalArgumentException("백업 파기 기한은 요청 시각 + 30일이어야 한다.");
            }
            DestructionStatus status;
            GenerationSet set;
            try {
                status = DestructionStatus.valueOf(fields[5]);
                set = GenerationSet.valueOf(fields[6]);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("tombstone 줄의 상태가 닫힌 값이 아니다.");
            }
            List<AffectedGeneration> generations = new ArrayList<>();
            if (!"-".equals(fields[7])) {
                for (String generation : fields[7].split(",", -1)) {
                    generations.add(AffectedGeneration.parse(generation));
                }
            }
            return new Tombstone(fields[0], fields[1], requestedAt, destroyAfter, status, set, generations);
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
     * @param live 실을 tombstone(백업 파기 완료가 아니거나, 아직 그 상태로 성공한 내보내기에 실리지 않았거나, 보존 기한이 지금 +
     *     {@link #EXPORT_MARGIN}보다 뒤, 요청 시각 순)
     * @param purged 이번에 지운 보존 기한 지난 행 수
     * @param exportId 이 내보내기의 번호. 업로드·대조 뒤 {@link #confirmExport}에 그대로 넘긴다
     */
    record Export(String keyId, List<Tombstone> live, int purged, long exportId) {}

    /**
     * 그 계정의 tombstone을 기록한다(단일 모드). <b>호출자 트랜잭션 안에서만</b> 쓴다 — 삭제와 한 커밋이어야 한다. 요청 시각은 이 문장의 DB
     * 시각이다. 같은 계정의 행이 이미 있으면 그대로 둔다(멱등). 새로 더했으면 감사 SYSTEM 행 하나를 남긴다.
     *
     * @return 이번에 행을 더했는가
     * @throws IllegalStateException 트랜잭션 밖이거나, 이 역할에 tombstone digest 키·감사 digest 키가 없거나, 요청 ID가 다른 계정에 쓰였다
     */
    boolean record(UUID userId, String requestId);

    /**
     * 그 계정의 tombstone을 사용자 삭제 요청의 시각으로 기록한다(Story 5.3 — 단계 모드의 첫 단계). <b>호출자 트랜잭션 안에서만</b> 쓴다.
     * 요청 시각은 ms로 자른다. 같은 계정의 행이 이미 있으면 그대로 둔다(멱등 — 재시도·lease 만료 복구가 다시 불러도 한 행이다). 새로 더했으면
     * 감사 SYSTEM 행 하나를 남긴다.
     *
     * @return 이번에 행을 더했는가
     * @throws IllegalStateException {@link #record(UUID, String)}와 같다
     */
    boolean record(UUID userId, String requestId, Instant requestedAt);

    /**
     * 그 요청의 영향 generation 집합을 지금(이 문장의 DB 시각, ms) 닫고 보존 기한을 {@code max(요청, 지금) + 35일}로 늘린다(줄이지
     * 않는다). <b>호출자 트랜잭션 안에서만</b> 쓴다 — 운영 데이터 삭제 완료와 한 커밋이다. 이미 닫혔거나 행이 없으면 아무것도 하지 않는다.
     * 닫았으면 감사 SYSTEM 행 하나를 남긴다.
     *
     * @return 이번에 닫았는가
     */
    boolean close(String requestId);

    /**
     * 외부 사본의 tombstone 하나를 이 DB의 outbox로 옮긴다(복원 적용). <b>호출자 트랜잭션 안에서만</b> 쓴다. 시각·상태·영향 generation은
     * 사본 그대로다 — 닫힌 집합의 닫힌 시각은 보존 기한에서 되짚는다({@code destroy_after − 35일}, 요청 시각보다 앞서지 않게). 사본이 파기를
     * 확인한 저장소는 옮긴 시각으로 확인 표시를 남긴다(복원 DB의 백업 기록에는 그 뒤의 파기가 없다). 같은 요청·같은 계정의 행이 이미 있으면
     * 사본의 더 진전된 상태를 병합한다(상태 단조 PENDING &lt; OVERDUE, DESTROYED는 끝; 닫힌 시각·초과·완료 시각은 없을 때만; 보존 기한은 큰
     * 쪽; 영향 generation은 합집합과 파기 확인). 같은 계정의 다른 요청이면 앞선 tombstone이 그 계정을 지키므로 그대로 둔다.
     *
     * @return 이번에 행을 더했는가
     * @throws IllegalStateException 같은 요청 ID의 행이 다른 digest로 이미 있다(사본과 DB가 어긋난다)
     */
    boolean carry(Tombstone tombstone);

    /**
     * 복원 적용(Story 5.3): 외부 사본에서 열린 집합으로 옮긴 tombstone 가운데 이 DB에서 닫힐 길이 없는 것(그 계정이 이 DB에 없거나 이미
     * {@code DELETED}라 다시 지우지 않았다)을 지금(이 문장의 DB 시각)으로 닫는다 — 영향 generation은 지금까지의 상위 집합이 된다(보수적).
     * 트랜잭션 하나이고 닫은 것이 있으면 감사 SYSTEM 행 하나를 남긴다.
     *
     * @return 닫은 수
     */
    int closeRestored(Collection<String> requestIds);

    /**
     * 내보내기 하나를 시작한다(트랜잭션 하나): 백업 파기가 완료됐고 그 상태로 성공한 내보내기에 실렸고 보존 기한이 지난 행을 지우고, 이
     * 내보내기의 번호를 만들어 지금 DESTROYED인 행에 처음 실린 번호로 붙이고, 실을 행을 읽는다. 완료가 아닌 행은 기한과 무관하게 남고
     * 실린다. 지운 행이 있으면 감사 SYSTEM 행 하나(수)를 남긴다. 시각의 권위는 DB다.
     */
    Export export();

    /**
     * 외부 사본의 성공을 기록한다(트랜잭션 하나) — 운영 호스트가 사본을 올리고 원격 대조·{@code latest} 갱신을 마친 뒤에만 부른다. 그
     * 내보내기 행을 성공(실린 수)으로 채우고 감사 SYSTEM 행 하나를 남긴다. 이 성공부터 그 사본에 DESTROYED로 실린 tombstone이 보존 기한
     * 규칙을 따른다. 같은 내보내기의 재전송은 아무것도 쓰지 않는다.
     *
     * @param tombstones 그 사본에 실린 tombstone 수
     * @param exportId {@link Export#exportId}
     * @throws IllegalArgumentException 그런 내보내기가 없다
     */
    void confirmExport(int tombstones, long exportId);

    /**
     * 복원 적용의 결과를 감사 원장에 남긴다(수만, 트랜잭션 하나).
     *
     * @param counts 닫힌 이름 → 수(사본의 tombstone·맞은 계정·다시 지운 계정·옮긴 tombstone·지운 세션)
     * @param passed 서비스를 열어도 되는가
     */
    void recordRestoreApplied(Map<String, Integer> counts, boolean passed);

    /** 지금 outbox에 있는 행 가운데 보존 기한이 지나지 않은 것(지우지 않는다). 복원 적용이 외부 사본과 대조한다. */
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
     * 이 역할에 tombstone digest 키가 있는가(Story 5.2). 계정 삭제 Worker는 키가 없으면 삭제를 집지 않는다 — 첫 단계의 {@link #record}가
     * 실패할 실행을 시작하지 않는다.
     */
    boolean keyAvailable();

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
