package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장된 채팅 세션을 llmPipeline이 처리할 수 있는 Markdown 원문과 문답 단위 source block으로 직렬화한다.
 * 형식은 docs/backlog/spec/chat-to-wiki-contract.md §4를 따른다.
 *
 * 문답(pair) 1쌍이 하나의 block이다. 원본을 특정하는 {@code session_id:pair_id} provenance는 Markdown 본문이
 * 아니라 block의 {@code blockId}로만 전달한다. 본문에 id를 넣지 않으므로 사용자에게 그대로 보여줄 수 있다.
 */
@Component
public class ChatWikiMarkdownSerializer {

    /** 직렬화 결과. markdown은 사용자에게 보여줄 본문, blocks는 export 시점에 문서에 저장할 provenance 단위다. */
    public record ChatWikiSource(String markdown, List<ChatSourceBlock> blocks) {

        /** 완결된 문답이 하나도 없으면 위키화할 것이 없다. */
        public boolean isEmpty() {
            return blocks.isEmpty();
        }
    }

    /** 문답 1쌍. blockId는 {@code session_id:pair_id}이며 text에는 그 id가 들어가지 않는다. */
    public record ChatSourceBlock(String blockId, String text) {}

    /** completed user+assistant가 모두 있는 문답만 대화 순서대로 직렬화한다. messages는 created_at 오름차순 가정. */
    public ChatWikiSource serialize(ChatSession session, List<ChatMessage> messages) {
        // pair_id별로 user/assistant 발화를 모은다 (등장 순서 보존).
        Map<String, String[]> pairs = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            if (!"completed".equals(message.getStatus())) {
                continue;
            }
            String[] qa = pairs.computeIfAbsent(message.getPairId(), k -> new String[2]);
            if ("user".equals(message.getRole())) {
                qa[0] = message.getContent();
            } else if ("assistant".equals(message.getRole())) {
                qa[1] = message.getContent();
            }
        }

        List<ChatSourceBlock> blocks = new ArrayList<>();
        // 제목은 export 서비스가 마스킹된 첫 질문에서 만들고, 여기서는 문답 원문만 조립한다.
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> entry : pairs.entrySet()) {
            String user = entry.getValue()[0];
            String assistant = entry.getValue()[1];
            if (user == null || assistant == null) {
                continue; // 불완전한 문답은 제외 (§4: user·assistant 모두 포함)
            }
            String blockId = session.getId() + ":" + entry.getKey();
            // 질문 줄 끝의 공백 2칸은 Markdown hard break다. 빈 줄을 쓰면 문답 1쌍이 두 블록으로
            // 쪼개져 인용이 질문을 잃는다. 파이프라인은 공백을 정규화하므로 블록 텍스트는 그대로다.
            String text = "Q : " + collapseBlankLines(user) + "  \nA : " + collapseBlankLines(assistant);
            blocks.add(new ChatSourceBlock(blockId, text));
            sb.append(text).append("\n\n");
        }
        return new ChatWikiSource(sb.toString(), List.copyOf(blocks));
    }

    /** §4: 문답 1쌍 안에는 빈 줄을 넣지 않는다. 발화 내부의 빈 줄을 단일 개행으로 접는다. */
    private String collapseBlankLines(String content) {
        return content.strip().replaceAll("\\n\\s*\\n+", "\n");
    }
}
