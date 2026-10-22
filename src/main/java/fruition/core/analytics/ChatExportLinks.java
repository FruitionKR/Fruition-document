package fruition.core.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import fruition.core.chat.exception.InvalidChatWikiExportRequestException;
import java.util.UUID;

/** C6 is product provenance: persist even when analytics is disabled. */
@Component
public class ChatExportLinks {
    private final JdbcTemplate jdbc;
    public ChatExportLinks(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void save(UUID export, String document, String actor, String conversation, String mode) {
        jdbc.update("INSERT INTO chat_export_links(export_id,document_id,user_id,conversation_id,mode) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                export, document, actor, conversation, mode);
        Boolean matches = jdbc.queryForObject("SELECT document_id=? AND user_id=? AND conversation_id=? AND mode=? FROM chat_export_links WHERE export_id=?",
                Boolean.class, document, actor, conversation, mode, export);
        if (!Boolean.TRUE.equals(matches)) throw new InvalidChatWikiExportRequestException("export_id가 다른 내보내기에 사용되었습니다.");
    }
}
