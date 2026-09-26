/**
 * 사용자가 확인한 최초 복리 조건 intent의 소유 모듈.
 *
 * <p>Intent는 활성 계획·공식 계산이 아니며, 최초 발행 스토리가 소비하기 전까지 불변으로 둔다.
 * Portfolio와 sync provenance는 각 공개 API를 통해서만 읽고 이 모듈의 내부 저장소로 수정하지
 * 않는다. 다른 모듈은 공개 application API만 사용한다.
 */
package com.bokrilog.plan;
