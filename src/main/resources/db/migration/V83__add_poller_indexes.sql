-- 짧은 주기로 도는 스케줄 작업의 조회가 테이블 전체를 훑지 않게 한다. 대상 행만 담는 부분 인덱스라 크기가 작다.

-- 취소 재개(AiTaskCancellationService.resume, 1초 주기). 상수 IN 조건이라 부분 인덱스 조건과 그대로 맞는다.
CREATE INDEX idx_ai_task_runs_resume ON ai_task_runs (updated_at)
    WHERE status IN ('cancel_requested', 'rolling_back');

-- pipeline run 대사(DocumentPipelineRunReconciler, 3초 주기). JPA가 status를 바인드 변수로 보내므로
-- status를 인덱스 조건이 아닌 키로 둬야 generic plan에서도 인덱스를 쓴다.
CREATE INDEX idx_documents_pipeline_run_status ON documents (status)
    WHERE pipeline_run_id IS NOT NULL;
