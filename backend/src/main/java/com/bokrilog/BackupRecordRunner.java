package com.bokrilog;

import com.bokrilog.operations.BackupRecordApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 일회성 백업 기록 역할의 진입(Story 3.9). {@code backup-record} 프로필에서만 있다.
 *
 * <p>운영 호스트의 백업 절차({@code deploy/production/backup.sh})가 실행마다 같은 이미지로 이 역할을 띄운다 — 시작에 한 번({@code
 * STARTED}), 끝에 한 번(결과·파기·측정). 환경이 준 행들({@code bokrilog.backup.record.*}, {@code application.yml})을 {@link
 * BackupRecordApi}로 한 트랜잭션에 더하고, 프로세스는 웹 서버 없이 끝난다 — 종료 코드 0이 기록 성공이고, 입력이 모양 밖이거나 쓰기가
 * 실패하면 기동 실패(0이 아님)다. 이 역할은 Flyway를 검사하지 않는다(배포 감사 역할과 같은 이유 — 표에 INSERT만 한다).
 *
 * <p><b>행의 표기.</b> 행은 {@code ;}로, 칸은 {@code ,}로 나눈다. 칸은 순서대로 {@code event,generation,store,code,bytes,count,
 * quota,sha256,key_id}이고 빈 칸은 값이 없다는 뜻이다. 어느 칸의 모양에도 두 구분자가 들어갈 수 없다.
 *
 * <p>로그에 입력 값을 싣지 않는다. 남는 것은 행 수뿐이다.
 */
@Component
@Profile("backup-record")
class BackupRecordRunner implements ApplicationRunner {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BackupRecordRunner.class);

    /** 한 행의 칸 수. */
    private static final int FIELDS = 9;

    private final BackupRecordApi records;

    private final String operation;

    private final String actor;

    private final String events;

    private final boolean rehearsal;

    BackupRecordRunner(BackupRecordApi records,
            @Value("${bokrilog.backup.record.operation:}") String operation,
            @Value("${bokrilog.backup.record.actor:}") String actor,
            @Value("${bokrilog.backup.record.events:}") String events,
            @Value("${bokrilog.backup.record.rehearsal:false}") boolean rehearsal) {
        this.records = records;
        this.operation = operation;
        this.actor = actor;
        this.events = events;
        this.rehearsal = rehearsal;
    }

    @Override
    public void run(ApplicationArguments args) {
        BackupRecordApi.Recorded recorded = this.records.record(batch());
        LOG.atInfo().addKeyValue("count", recorded.rows()).log("backup.record.recorded");
    }

    /** 환경 값을 기록 하나로 푼다. 모양 밖이면 {@link IllegalArgumentException}이다. */
    BackupRecordApi.Batch batch() {
        List<BackupRecordApi.Row> rows = new ArrayList<>();
        for (String line : this.events.split(";", -1)) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.strip().split(",", -1);
            if (fields.length != FIELDS) {
                throw new IllegalArgumentException("행의 칸 수가 " + FIELDS + "가 아니다.");
            }
            BackupRecordApi.Event event = parse(BackupRecordApi.Event.class, fields[0], "사건");
            BackupRecordApi.Store store = fields[2].isBlank() ? null
                    : parse(BackupRecordApi.Store.class, fields[2].toUpperCase(Locale.ROOT), "저장소");
            rows.add(new BackupRecordApi.Row(event, fields[1], store, blankToNull(fields[3]),
                    number(fields[4], "bytes"), integer(fields[5], "count"), number(fields[6], "quota"),
                    blankToNull(fields[7]), blankToNull(fields[8])));
        }
        return new BackupRecordApi.Batch(this.operation, this.actor, List.copyOf(rows), this.rehearsal);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(name + " 값이 닫힌 목록 밖이다.");
        }
    }

    private static @Nullable Long number(String value, String name) {
        if (value.isBlank()) {
            return null;
        }
        if (!value.matches("[0-9]{1,18}")) {
            throw new IllegalArgumentException(name + "가 음이 아닌 정수가 아니다.");
        }
        return Long.parseLong(value);
    }

    private static @Nullable Integer integer(String value, String name) {
        if (value.isBlank()) {
            return null;
        }
        if (!value.matches("[0-9]{1,9}")) {
            throw new IllegalArgumentException(name + "가 음이 아닌 정수가 아니다.");
        }
        return Integer.parseInt(value);
    }

    private static @Nullable String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
