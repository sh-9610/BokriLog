package com.bokrilog.operations;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 배포 단계의 감사(Story 3.8 AC-5). 배포 절차({@code deploy/production/deploy.sh})가 단계마다 일회성 {@code deploy-audit} 역할을
 * 띄워 이 port로 감사 원장에 SYSTEM 행 하나를 더한다.
 *
 * <p><b>행의 모양.</b> {@code actor_kind=SYSTEM}, {@code command=deploy}, {@code scope=DEPLOY}, {@code target}=배포 대상 image
 * digest 참조, {@code reason}=단계명({@link Step}), {@code outcome}=기존 결과 enum 중 셋({@link Outcome}). 수행자는 {@code
 * BOKRILOG_DEPLOY_ACTOR}의 {@code deployer:} keyed digest이고 원문은 남지 않는다. 한 배포의 모든 행은 같은 operation ID로 묶인다.
 * {@code details}는 닫힌 키뿐이다: 실패 code·슬롯·이전 digest 참조·리허설 표지·그 시점의 활성 제한 수. 비밀·금융값·사용자
 * 식별자는 없다.
 *
 * <p><b>제한을 쓰지 않는다.</b> 배포는 제한 행을 만들거나 닫지 않는다. 활성 제한 수는 읽기만 해서 행에 남긴다 — 배포 전후 수가
 * 같은지를 원장에서 비교할 수 있다.
 */
public interface DeployAuditApi {

    /** 감사 행의 command. */
    String COMMAND = "deploy";

    /** 감사 행의 scope. */
    String SCOPE = "DEPLOY";

    /** 배포 감사의 정책 버전. */
    String POLICY_VERSION = "DEPLOY-1.0";

    /** operation ID의 모양. 배포 스크립트가 만든다. */
    Pattern OPERATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** image digest 참조의 모양: {@code <repo>@sha256:<64hex>} 또는 리허설의 image ID {@code sha256:<64hex>}. */
    Pattern IMAGE_REFERENCE = Pattern.compile("([a-z0-9][a-z0-9._/:-]{0,180}@)?sha256:[0-9a-f]{64}");

    /** 실패 code의 모양. */
    Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** 수행자 이름의 모양. */
    Pattern ACTOR = Pattern.compile("[A-Za-z0-9._@-]{1,128}");

    /**
     * 배포 단계(감사 행의 사유). {@link #GATE}부터 {@link #ROLLED_BACK}까지는 배포 절차의 순서다. {@link #WINDOW_CLOSED}는 배포
     * 밖의 운영 행동 하나 — rollback 창을 닫는 것({@code deploy.sh close-window})이고, target은 그때의 활성 이미지다.
     */
    enum Step {
        GATE, DRAINED, MIGRATED, INACTIVE_VERIFIED, CUTOVER, VERIFIED, ROLLED_BACK, WINDOW_CLOSED
    }

    /** 배포 감사가 쓰는 결과. 원장의 결과 enum({@code V17}) 안의 셋이다. {@link #REQUESTED}는 {@link Step#GATE}에만 쓴다. */
    enum Outcome {
        REQUESTED, COMPLETED, FAILED
    }

    /** blue/green 슬롯. */
    enum Slot {
        BLUE, GREEN
    }

    /**
     * 기록할 단계 하나.
     *
     * @param target 배포 대상 image digest 참조({@link #IMAGE_REFERENCE}). {@code GATE FAILED}(gate가 형식 때문에 거부했다)에서만
     *     {@code null}일 수 있고 그때 행의 target은 {@code INVALID_REFERENCE}다. 다른 단계에서는 비면 거절이다
     * @param actor 배포 수행자 이름({@link #ACTOR}). 원장에는 keyed digest로만 남는다
     * @param code 실패 code({@link #CODE}). 없으면 {@code null}
     * @param previous 이전 image digest 참조. 없으면 {@code null}
     */
    record Event(String operationId, Step step, Outcome outcome, @Nullable String target, String actor,
            @Nullable String code, @Nullable Slot slot, @Nullable String previous, boolean rehearsal) {}

    /**
     * 기록된 행.
     *
     * @param sequence 원장의 추가 순서
     * @param activeRestrictions 그 행을 쓴 시점의 활성 제한 수
     */
    record Recorded(long sequence, int activeRestrictions) {}

    /** gate가 형식 때문에 거부한 대상의 원장 target. */
    String INVALID_TARGET = "INVALID_REFERENCE";

    /**
     * 단계 하나를 원장에 더한다(트랜잭션 하나, 원장 추가 잠금 아래).
     *
     * @throws IllegalArgumentException 입력이 위 모양 밖이거나 {@link Outcome#REQUESTED}가 {@link Step#GATE} 밖에 쓰였다
     */
    Recorded record(Event event);
}
