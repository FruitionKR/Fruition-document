package fruition.core.speech;

/** 운영에서 답변 음성 듣기를 끈 상태(app.speech.synthesis.enabled=false). 403 SPEECH_SYNTHESIS_DISABLED로 응답한다. */
public class SpeechSynthesisDisabledException extends RuntimeException {
    public SpeechSynthesisDisabledException() {
        super("지금은 답변 듣기를 쓸 수 없어요.");
    }
}
