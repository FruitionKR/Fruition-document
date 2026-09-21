package fruition.core.document.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ConvertedMarkdownChunks {
    private ConvertedMarkdownChunks() {}
    static List<String> split(String markdown) {
        // 후속 LLM 평가도 한 묶음 안에서 끝나도록 본문 64KiB 단위로 나눈다.
        int limit = 64 * 1024;
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bytes = 0;
        for (String line : markdown.split("(?<=\\n)", -1)) {
            int length = line.getBytes(StandardCharsets.UTF_8).length;
            if (length <= limit) {
                if (bytes + length > limit) { chunks.add(current.toString()); current.setLength(0); bytes = 0; }
                current.append(line); bytes += length;
                continue;
            }
            if (line.contains("data:image/")) {
                throw new IllegalArgumentException("단일 삽입 이미지가 편집 문서 처리 단위를 초과했습니다.");
            }
            for (int offset = 0; offset < line.length();) {
                int cp = line.codePointAt(offset);
                String value = new String(Character.toChars(cp));
                int count = value.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + count > limit) { chunks.add(current.toString()); current.setLength(0); bytes = 0; }
                current.append(value); bytes += count; offset += Character.charCount(cp);
            }
        }
        if (!current.isEmpty() || chunks.isEmpty()) chunks.add(current.toString());
        return chunks;
    }
}
