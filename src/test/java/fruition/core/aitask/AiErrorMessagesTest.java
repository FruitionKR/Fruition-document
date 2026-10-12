package fruition.core.aitask;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiErrorMessagesTest {

    @Test
    void knownCodeMapsToKoreanMessage() {
        assertThat(AiErrorMessages.query("provider_rate_limited")).contains("요청이 많아");
        assertThat(AiErrorMessages.agent("SKILL_NOT_FOUND")).isEqualTo("선택한 Skill을 찾을 수 없습니다.");
        assertThat(AiErrorMessages.ingest("deadline_exceeded")).contains("시간이 초과");
    }

    @Test
    void prefixedCodesShareOneMessage() {
        assertThat(AiErrorMessages.agent("markdown_edit_target_missing")).contains("편집안");
        assertThat(AiErrorMessages.agent("rollback_child_failed")).contains("되돌리지");
    }

    @Test
    void explicitEntryWinsOverPrefixRule() {
        // rollback_failed·rollback_child_failed는 개별 항목, rollback_other는 접두사 규칙으로 풀린다.
        assertThat(AiErrorMessages.agent("rollback_failed")).contains("되돌리지");
        assertThat(AiErrorMessages.agent("rollback_child_failed")).contains("되돌리지");
        assertThat(AiErrorMessages.agent("rollback_other")).contains("되돌리지");
        assertThat(AiErrorMessages.agent("mutation_intent_required")).contains("구체적으로");
    }

    @Test
    void unknownBlankAndGenericCodesFallBackToFeatureDefault() {
        assertThat(AiErrorMessages.query("ValueError: secret detail")).isEqualTo("질의 처리 중 오류가 발생했습니다.");
        assertThat(AiErrorMessages.agent(null)).isEqualTo("Agent 처리 중 오류가 발생했습니다.");
        assertThat(AiErrorMessages.ingest("internal_error")).isEqualTo("위키 편입에 실패했습니다.");
        assertThat(AiErrorMessages.lint("something_new")).isEqualTo("위키 정리에 실패했습니다.");
    }

    @Test
    void convertAndTranscriptionShareCommonTextsAndKeepOwnDefaults() {
        assertThat(AiErrorMessages.convert("provider_rate_limited"))
                .isEqualTo("PDF 변환에 실패했습니다. " + AiErrorMessages.query("provider_rate_limited"));
        assertThat(AiErrorMessages.convert("input_rejected")).contains("암호가 걸렸거나");
        assertThat(AiErrorMessages.convert("Traceback secret")).isEqualTo("PDF 변환에 실패했습니다. 변환 중 오류가 발생했습니다.");
        assertThat(AiErrorMessages.transcription("provider_unavailable")).isEqualTo(AiErrorMessages.query("provider_unavailable"));
        assertThat(AiErrorMessages.transcription("MEETING_RECORDING_TOO_LONG")).contains("3시간");
        assertThat(AiErrorMessages.transcription("deadline_exceeded")).contains("녹음을 나눠");
        assertThat(AiErrorMessages.transcription("internal_error")).isEqualTo("녹음 파일을 전사하지 못했습니다. 다시 올려 주세요.");
    }

    @Test
    void newAiCodesAndCreditCodeHaveText() {
        assertThat(AiErrorMessages.agent("REFERENCE_DOCUMENT_TOO_LARGE")).contains("30,000자");
        assertThat(AiErrorMessages.agent("dependency_failed")).contains("건너뛰었습니다");
        assertThat(AiErrorMessages.ingest("INSUFFICIENT_CREDIT")).isEqualTo(AiErrorMessages.INSUFFICIENT_CREDIT);
    }
}
