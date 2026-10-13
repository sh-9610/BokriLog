package com.bokrilog.operations;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 일일 암호화 백업의 실행 기록(Story 3.9 AC-2~AC-4). 운영 호스트의 백업 절차({@code deploy/production/backup.sh})가 실행마다 일회성
 * {@code backup-record} 역할을 띄워 이 port로 {@code operations_backup_event}에 행을 더한다.
 *
 * <p><b>표는 기록이고 파기 판단의 권위는 저장소 목록이다.</b> 이 port는 절차가 판정한 사건을 모양만 검사해 추가한다. 성공은 절차가
 * 다섯 완료 조건을 모두 확인한 뒤에만 보낸다 — 이 port가 그 판정을 대신하지 않는다. 기록하지 못하면 성공이 아니다(절차가 실패로
 * 끝난다).
 *
 * <p><b>감사.</b> 파기 실패({@link Event#PURGE_FAILED})와 기한 초과({@link Event#PURGE_OVERDUE})는 같은 트랜잭션에서 감사 원장에도
 * SYSTEM 행 하나씩을 남긴다 — {@code command=backup}, {@code scope=BACKUP}, target=generation ID, reason=사건 이름, outcome
 * {@code FAILED}. 수행자는 {@code BOKRILOG_BACKUP_ACTOR}의 {@code backup:} keyed digest다. 한 실행의 행은 같은 operation ID로
 * 묶인다. details는 닫힌 키(저장소·code·리허설 표지)뿐이다.
 *
 * <p>사용자 식별자·이메일·금융값·key 값은 어떤 열에도 없다. key는 ID({@link #KEY_ID})로만 남는다.
 */
public interface BackupRecordApi {

    /** 감사 행의 command. */
    String COMMAND = "backup";

    /** 감사 행의 scope. */
    String SCOPE = "BACKUP";

    /** 백업 기록의 정책 버전. */
    String POLICY_VERSION = "BACKUP-1.0";

    /** operation ID의 모양. 백업 절차가 실행마다 만든다. */
    Pattern OPERATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** generation ID: {@code gen_<UTC YYYYMMDD>}. 같은 날 재실행은 같은 ID다. */
    Pattern GENERATION_ID = Pattern.compile("gen_[0-9]{8}");

    /** 단계·사유 code. */
    Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** 암호문 SHA-256(소문자 16진). */
    Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** backup key ID. object 이름의 한 마디라 점을 쓰지 않는다. */
    Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    /** 수행자 이름의 모양. */
    Pattern ACTOR = Pattern.compile("[A-Za-z0-9._@-]{1,128}");

    /** 한 번에 기록할 수 있는 행 수의 상한. */
    int MAX_ROWS = 500;

    /** 측정 행이 기한 초과 generation이 남은 저장소에 다는 code. */
    String MEASURED_OVERDUE = "PURGE_OVERDUE";

    /** 사건 종류. */
    enum Event {
        STARTED, SUCCEEDED, FAILED, PURGED, PURGE_FAILED, PURGE_OVERDUE, MEASURED
    }

    /** 저장소. OCI와 OCI 밖 하나씩이다. */
    enum Store {
        OCI, OFFSITE;

        /** 표·tag의 값(소문자). */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 사건 하나. 사건마다 채울 수 있는 칸이 정해져 있다({@code V24} CHECK와 같다).
     *
     * @param store 저장소. {@link Event#PURGED}에서 비면 로컬 임시본이다
     * @param code 단계·사유 code({@link #CODE})
     * @param bytes 크기(성공 = 암호문, 측정 = 저장소 합계, 파기 = 지운 크기)
     * @param count 개수(측정 = generation 수, 파기 = 지운 object 수)
     * @param quotaBytes 측정의 저장소 quota
     * @param sha256 성공의 암호문 SHA-256
     * @param keyId 성공·파기의 backup key ID
     */
    record Row(Event event, String generationId, @Nullable Store store, @Nullable String code, @Nullable Long bytes,
            @Nullable Integer count, @Nullable Long quotaBytes, @Nullable String sha256, @Nullable String keyId) {}

    /**
     * 한 번의 기록. 한 트랜잭션이다.
     *
     * @param operationId 그 실행의 operation ID
     * @param actor 백업 수행자 이름({@link #ACTOR}). 감사 원장에는 keyed digest로만 남는다
     * @param rehearsal 리허설이면 감사 details에 {@code rehearsal:true}
     */
    record Batch(String operationId, String actor, List<Row> rows, boolean rehearsal) {}

    /**
     * 기록된 것.
     *
     * @param rows 표에 더한 행 수
     * @param auditRows 감사 원장에 더한 행 수
     */
    record Recorded(int rows, int auditRows) {}

    /**
     * 행들을 순서대로 추가한다(트랜잭션 하나, 기록 잠금 아래). 하나라도 모양 밖이면 아무것도 쓰지 않는다.
     *
     * @throws IllegalArgumentException 입력이 위 모양 밖이다
     */
    Recorded record(Batch batch);
}
