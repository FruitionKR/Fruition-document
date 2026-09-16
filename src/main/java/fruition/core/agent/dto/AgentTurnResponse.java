package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 이 엔드포인트는 나머지 필드를 camelCase로 주고받는다.
 * applyOperationId만 snake_case인 이유는, 저장 요청이 이 값을
 * {@code @RequestPart("apply_operation_id")}로 받기 때문이다. 이름이 어긋나면
 * 클라이언트가 값을 읽지 못해 AI 작업 로그가 남지 않는다.
 *
 * @param applyOperationId 이 편집안을 적용할 때 저장 요청에 실어야 하는 표.
 *                         Backend가 발급한 값이라야 AI 작업 로그가 남는다.
 */
@Schema(description = "Markdown Agent 편집 턴 결과. apply_operation_id를 뺀 나머지 필드는 camelCase다.")
public record AgentTurnResponse(
        @Schema(description = "대상 문서 ID. 문서를 열지 않은 턴은 null이다.",
                example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83", nullable = true)
        String documentId,

        @Schema(description = "이 턴이 기준으로 삼은 문서 버전. 문서를 열지 않은 턴은 null이다.",
                example = "3", nullable = true)
        Long baseVersion,

        @Schema(description = "이 턴의 run ID. 상태 조회에 쓴다.",
                example = "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String requestId,

        @JsonProperty("apply_operation_id")
        @Schema(description = "편집안을 적용할 때 저장 요청에 apply_operation_id로 실어야 하는 값. "
                + "서버가 발급한 값이라야 AI 작업 로그가 남는다. 문서를 열지 않은 턴은 null이다.",
                nullable = true)
        String applyOperationId,

        @Schema(description = "턴 처리 상태", example = "completed")
        String status,

        @Schema(description = "완료된 턴의 편집 제안 결과")
        JsonNode result,

        @Schema(description = "실패 사유. 성공이면 null이다.")
        String error
) {}
