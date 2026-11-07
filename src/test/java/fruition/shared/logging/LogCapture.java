package fruition.shared.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.List;

public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> output = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing(); super.append(event);
        }
    };
    public LogCapture(Class<?> type) {
        logger = (Logger) LoggerFactory.getLogger(type);
        output.start(); logger.addAppender(output);
    }
    public List<String> messages() {
        return output.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
    public List<ILoggingEvent> events() { return List.copyOf(output.list); }
    @Override public void close() { logger.detachAppender(output); output.stop(); }
}
