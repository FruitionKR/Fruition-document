package fruition.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전송 경계의 정책. 콘솔 로그는 그대로 두고 PostHog에는 허용한 필드만 보낸다.
 * 모르는 {@code key=value}의 값은 버린다(fail closed). 새 필드를 검색하려면 여기에 명시적으로 추가한다.
 */
final class PostHogLogPolicy {
    record Sanitized(String body, Map<String, String> attributes) {}

    private static final int MAX_BODY = 2000;
    private static final int MAX_FRAMES = 30;
    private static final Pattern KEY = Pattern.compile("(?<![\\p{L}\\p{N}_.-])([\\p{L}_][\\p{L}\\p{N}_.-]{0,40})=");
    private static final Pattern CODE_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern FIELD_VALUE = Pattern.compile("[A-Za-z0-9_.:/-]{1,256}");
    /** 쉼표로 이은 식별자 목록. 값 전체가 맞아야 받는다. */
    private static final Pattern LIST_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}(?:,[A-Za-z0-9_.:-]{1,64}){0,19}");
    private static final Pattern[] SECRETS = {
            Pattern.compile("(?i)bearer\\s+\\S+"),
            Pattern.compile("eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]*"),
            Pattern.compile("ph[cx]_\\w+"),
            Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"),
            Pattern.compile("https?://\\S+"),
            Pattern.compile("(?<![/\\w])(?:sources|tmp|meetings|assets)/[\\w./-]+")};
    /** 값 전체가 코드여야 하는 필드. 자유 문장이 섞이면 통째로 버린다. */
    private static final Set<String> CODE_FIELDS = Set.of("operation", "stage", "errorCode", "upstreamCode", "code",
            "reason", "errorType", "type", "status", "kind", "method", "retryable", "partial", "trigger", "action",
            "origin", "source", "skillMode", "toolName", "validationField", "provider", "role", "purpose", "mfa",
            "runStatus", "previousStatus", "rollback", "targetType", "access", "scope", "productCode", "model", "mergeStatus", "choice", "dryRun", "contentEdit", "organize", "marketingOptIn", "termsVersion", "privacyVersion", "signup", "mode", "scopeType");
    /** 첫 단어만 식별자·수치로 받고 뒤따르는 문장은 버린다. */
    private static final Set<String> VALUE_FIELDS = Set.of("requestId", "flowId", "userId", "workspaceId", "documentId",
            "sourceDocumentId", "placeholderDocumentId", "folderId", "meetingId", "skillId", "runId", "parentRunId",
            "eventId", "operationId", "targetOperationId", "restoreOperationId", "applyOperationId", "sessionId",
            "importId", "invitationId", "orderId", "conflictId", "messageId", "pageId", "failureKey", "uri", "changeId",
            "resourceId", "targetId", "parentFolderId", "fromFolderId", "toFolderId", "amountKrw", "refundKrw", "exportId", "itemCount", "totalBytes", "queued", "remaining", "messageCount", "reportedCreated", "reportedFailed", "reportedSkipped", "targetUserId", "actorId", "elapsedMs", "attempt", "attempts", "count", "size", "byteSize",
            "partCount", "totalPages", "startPage", "version", "baseVersion", "fromVersion", "toVersion", "newVersion",
            "revision", "baseRevision", "changedResources", "remainingChanges", "undoneChanges", "totalCount",
            "suppressedCount", "batchCount", "segmentCount", "closeCode", "documents", "folders", "revokedSessions",
            "auditId", "grantId", "clientIdHash", "targetCount");
    private static final Set<String> LIST_FIELDS = Set.of("targetIds");
    private static final Map<String, String> TEXT_FIELDS = Map.of("원인", "cause", "설명", "description");

    private PostHogLogPolicy() {}

    /** 기본 정책: fruition WARN·ERROR 전체, INFO는 operation 필드가 있는 업무 단계만, 외부 라이브러리는 ERROR만. */
    static boolean shouldSend(ILoggingEvent event) {
        String logger = event.getLoggerName();
        // 전송 실패 로그가 다시 전송되는 재귀를 막는다.
        if (logger.startsWith("io.opentelemetry") || logger.startsWith("okhttp3") || logger.equals(PostHogLogExport.class.getName())) return false;
        Level level = event.getLevel();
        if (!logger.startsWith("fruition.")) return level.isGreaterOrEqual(Level.ERROR);
        if (level.isGreaterOrEqual(Level.WARN)) return true;
        // MCP 감사 JSON에는 요청 입력이 섞인다. 같은 호출은 fruition.mcp.activity의 정화된 업무 로그로 보낸다.
        if (logger.equals("fruition.mcp.audit")) return false;
        return level.equals(Level.INFO) && hasOperation(event.getFormattedMessage());
    }

    private static boolean hasOperation(String message) {
        return message != null && message.contains("operation=");
    }

    static Sanitized sanitize(ILoggingEvent event) {
        Map<String, String> attributes = new LinkedHashMap<>();
        String logger = event.getLoggerName();
        attributes.put("logger", logger);
        // 외부 라이브러리 문구에는 접속 문자열·요청 값이 섞일 수 있어 문구를 보내지 않는다.
        String body = logger.startsWith("fruition.") ? sanitizeMessage(event.getFormattedMessage(), attributes)
                : "[외부 라이브러리 오류] 콘솔 로그와 logger 필드를 확인해야 합니다.";
        event.getMDCPropertyMap().forEach((key, value) -> {
            if (Set.of("requestId", "userId", "flowId").contains(key) && value != null && FIELD_VALUE.matcher(value).matches()) {
                // 인증 필터가 넣은 값이 본문에 섞인 값보다 믿을 만하다.
                attributes.put(key, value);
            }
        });
        // 프론트 PostHog distinct_id와 access 사용자 ID가 같을 때만 사람 프로필에 연결된다.
        if (attributes.containsKey("userId")) attributes.put("posthogDistinctId", attributes.get("userId"));
        // 로그의 sessionId는 채팅 세션이다. PostHog의 sessionId(리플레이)로 오인되지 않게 이름을 바꾼다.
        String chatSession = attributes.remove("sessionId");
        if (chatSession != null) attributes.put("chatSessionId", chatSession);
        addThrowable(event.getThrowableProxy(), attributes);
        return new Sanitized(body, attributes);
    }

    private static String sanitizeMessage(String message, Map<String, String> attributes) {
        if (message == null) return "";
        StringBuilder body = new StringBuilder();
        Matcher matcher = KEY.matcher(message);
        int position = 0;
        String key = null;
        while (matcher.find()) {
            appendSegment(body, key, message.substring(position, matcher.start()), attributes);
            key = matcher.group(1);
            position = matcher.end();
        }
        appendSegment(body, key, message.substring(position), attributes);
        String scrubbed = body.toString().strip();
        for (Pattern secret : SECRETS) scrubbed = secret.matcher(scrubbed).replaceAll("***");
        return scrubbed.length() <= MAX_BODY ? scrubbed : scrubbed.substring(0, MAX_BODY) + "…";
    }

    private static void appendSegment(StringBuilder body, String key, String raw, Map<String, String> attributes) {
        if (key == null) {
            body.append(raw);
            return;
        }
        String value = raw.strip();
        body.append(key).append('=');
        if (TEXT_FIELDS.containsKey(key)) {
            body.append(value).append(' ');
            attributes.putIfAbsent(TEXT_FIELDS.get(key), value);
        } else if (CODE_FIELDS.contains(key) && CODE_VALUE.matcher(value).matches()) {
            body.append(value).append(' ');
            attributes.putIfAbsent(key, value);
        } else if (LIST_FIELDS.contains(key) && LIST_VALUE.matcher(value).matches()) {
            body.append(value).append(' ');
            attributes.putIfAbsent(key, value);
        } else if (VALUE_FIELDS.contains(key)) {
            String first = value.split("\\s+", 2)[0];
            if (FIELD_VALUE.matcher(first).matches()) {
                body.append(first).append(first.length() < value.length() ? " … " : " ");
                // 뒤따르는 예외 문구 속 key=value가 템플릿 필드를 덮어쓰지 못하게 처음 값만 받는다.
                attributes.putIfAbsent(key, first);
            } else {
                body.append("*** ");
            }
        } else {
            body.append("*** ");
        }
    }

    /** 예외 메시지는 사용자 값이 섞일 수 있어 빼고 타입과 코드 위치만 보낸다. */
    private static void addThrowable(IThrowableProxy throwable, Map<String, String> attributes) {
        if (throwable == null) return;
        attributes.put("exception.type", throwable.getClassName());
        StringBuilder trace = new StringBuilder();
        int frames = 0;
        IThrowableProxy root = throwable;
        for (IThrowableProxy current = throwable; current != null; current = current.getCause()) {
            root = current;
            trace.append(current == throwable ? "" : "Caused by: ").append(current.getClassName()).append('\n');
            for (StackTraceElementProxy frame : current.getStackTraceElementProxyArray()) {
                if (frames++ >= MAX_FRAMES) break;
                trace.append("  at ").append(frame.getStackTraceElement()).append('\n');
            }
        }
        attributes.put("exception.root_type", root.getClassName());
        attributes.put("exception.stacktrace", trace.toString());
    }
}
