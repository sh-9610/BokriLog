package com.bokrilog.sync.internal;

/**
 * 작업자 claim의 문(Story 3.8 drain).
 *
 * <p>dispatch는 claim 앞에서 {@link #enter()}를 부르고, 잡은 lease를 진행 중 표에 넣은 뒤 {@link #exit()}한다. {@link #close()}
 * 뒤에는 {@link #enter()}가 거짓이라 새 lease를 잡지 않는다. 이미 문 안에 있던 claim은 끝까지 가고, 그동안 {@link #active()}가
 * 그 수를 센다 — drain이 "진행 중 0"을 읽는 순간 문 안에 claim이 없고 잡힌 lease는 모두 진행 중 표에 있다(그 사이의 틈이
 * 없다).
 */
final class ClaimGate {

    private boolean closed;

    private int active;

    /** 문을 지나도 되면 참이고 그때 {@link #exit()}를 꼭 불러야 한다. 닫혔으면 거짓이다. */
    synchronized boolean enter() {
        if (this.closed) {
            return false;
        }
        this.active++;
        return true;
    }

    synchronized void exit() {
        this.active--;
    }

    /** 문을 닫는다. 다시 열지 않는다. */
    synchronized void close() {
        this.closed = true;
    }

    /** 지금 문 안에 있는 claim 수. */
    synchronized int active() {
        return this.active;
    }
}
