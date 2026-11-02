package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;

/**
 * 실패로 끝난 작업의 요약문. 목록·상세 API가 그대로 사용자에게 보여주는 값이다.
 *
 * <p>AI worker가 보낸 오류 원문({@code "document-svc contribution lookup failed"},
 * {@code "500: ingest source revision is stale"} 같은 문자열)을 그대로 실으면 사용자에게는
 * 아무 의미가 없고 내부 서비스 구성만 드러난다. 저장은 이 사용자용 문구로 하고, 원문은
 * 호출부가 로그로 남긴다.
 */
final class OperationFailureSummary {

    private OperationFailureSummary() {
    }

    static String of(OperationType type, OperationStatus status) {
        boolean partial = status == OperationStatus.partially_succeeded;
        return switch (type) {
            case ingest -> partial
                    ? "Wiki ingest를 일부만 반영했습니다." : "Wiki ingest에 실패했습니다.";
            case lint -> partial
                    ? "Wiki 정합성 검사를 일부만 반영했습니다." : "Wiki 정합성 검사에 실패했습니다.";
            case restore -> partial
                    ? "되돌리기를 일부만 반영했습니다." : "되돌리기에 실패했습니다.";
            case document_edit -> partial
                    ? "AI 편집을 일부만 반영했습니다." : "AI 편집을 반영하지 못했습니다.";
            case wiki_edit -> "Wiki 본문 수정을 반영하지 못했습니다.";
        };
    }

    /**
     * ai가 보낸 복구 실패 코드의 요약문. {@code restore_conflict}는 재생 결과가 뒤 작업과 충돌해 ai가 쓰기 전에
     * 멈춘 경우다(ai ADR 0042). 원장도 바꾸지 않았으므로 뒤 작업부터 되돌리면 다시 할 수 있다.
     */
    static String restoreFailure(String code) {
        return "restore_conflict".equals(code)
                ? "뒤에 실행한 작업과 충돌해 되돌리지 않았습니다. 뒤 작업부터 되돌려 주세요."
                : of(OperationType.restore, OperationStatus.failed);
    }
}
