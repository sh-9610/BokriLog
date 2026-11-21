package com.bokrilog.engagement;

import org.jspecify.annotations.Nullable;

/** 대시보드가 전면 광고를 준비할 때 쓸 광고 단위 경로. 공급 중단 상태에서는 값이 없다. */
public interface DashboardAdApi {

    @Nullable String adUnitPath();
}
