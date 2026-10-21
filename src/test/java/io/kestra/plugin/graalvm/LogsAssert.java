package io.kestra.plugin.graalvm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kestra.core.runners.RunContext;

import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

/**
 * Captures what a task logs through {@code runContext.logger()} so tests can assert that every printed line
 * was logged. The reader threads are joined before a task returns, so no waiting is needed.
 */
public final class LogsAssert implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogsAssert(RunContext runContext) {
        this.logger = (Logger) runContext.logger();
        appender.start();
        logger.addAppender(appender);
    }

    public void assertLines(Level level, String prefix, List<String> expected) {
        List<String> actual;
        synchronized (appender.list) {
            actual = appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(prefix))
                .toList();
        }
        assertThat(actual, contains(expected.toArray()));
    }

    public static List<String> numbered(String prefix, int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> prefix + i).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
