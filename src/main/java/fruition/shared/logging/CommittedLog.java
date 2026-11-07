package fruition.shared.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;
import java.util.regex.Pattern;

/** Terminal state logs follow the database commit, including the originating request context. */
public final class CommittedLog {
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private CommittedLog() {}
    /** Never put arbitrary provider messages or model output in a code field. */
    public static String code(String value) {
        return value != null && CODE.matcher(value).matches() ? value : "unknown";
    }
    public static void warn(Logger logger, String template, Object... arguments) {
        afterCommit(() -> logger.warn(template, arguments));
    }
    public static void info(Logger logger, String template, Object... arguments) {
        afterCommit(() -> logger.info(template, arguments));
    }
    public static void error(Logger logger, String template, Object... arguments) {
        afterCommit(() -> logger.error(template, arguments));
    }
    static void afterCommit(Runnable record) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        Runnable contextual = () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context == null) MDC.clear(); else MDC.setContextMap(context);
                record.run();
            } finally {
                if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { contextual.run(); }
            });
        } else contextual.run();
    }
}
