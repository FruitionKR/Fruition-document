-- lint가 기여 원장에 남긴 행을 표시한다(ai ADR 0042 하위 항목 분리·다시 합치기).
-- lint 자기 기록은 ingest_operation_id와 같은 값, lint가 원래 작업 id로 다른 페이지에 이은 편입 기록은 그 lint id다.
-- ingest·사람 수정 기여는 NULL이다. lint 복원은 이 값이 대상 lint인 행을 끈다.
ALTER TABLE wiki_page_contributions
    ADD COLUMN lint_operation_id varchar(255),
    ADD CONSTRAINT fk_wiki_page_contributions_lint_operation
        FOREIGN KEY (lint_operation_id) REFERENCES ai_operation_logs(operation_id);
