-- Story 3.7: 동기화 대기 나이(`bokrilog_sync_queue_age_seconds`) 측정의 받침 색인.
--
-- 관측은 15초마다 종류별로 `min(created_at) WHERE status = 'QUEUED'`를 읽는다(`SyncMetrics`, statement_timeout 2초).
-- 첫 스냅샷(`sync_job`)과 초기 수집(`sync_initial_collection_job`)에는 대기 행을 받치는 색인이 없어 표 전체를 훑는다 — 작업
-- 행은 끝난 뒤에도 남으므로 시간이 갈수록 측정이 느려지고 timeout으로 미측정(NaN)이 된다. 나머지 넷(판정·환산·발행·계획
-- 동기화)은 V8~V11의 `WHERE status IN ('QUEUED','RUNNING')` 부분 색인이 이미 받친다.
--
-- 부분 색인이라 대기 행만 담는다. `CONCURRENTLY` 없이 만든다 — 지금은 행이 적고, migration은 별도 단일 실행이다(AD-12). 운영
-- 데이터가 쌓인 뒤 같은 모양을 더할 때는 Story 3.8의 배포 절차를 따른다. expand만 한다. V1~V22는 고치지 않는다.

CREATE INDEX sync_job_queued_idx ON sync_job (created_at) WHERE status = 'QUEUED';

CREATE INDEX sync_initial_collection_job_queued_idx ON sync_initial_collection_job (created_at)
    WHERE status = 'QUEUED';
