package io.kestra.plugin.graalvm;

import org.slf4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

/**
 * Pipes the script stdout/stderr to the task logger on platform threads.
 * <p>
 * The readers start immediately so a script printing more than the pipe buffer never blocks during eval.
 * Declare this <b>before</b> the polyglot context in a try-with-resources: the context is then closed first
 * (some languages, such as TruffleRuby, only write their buffered output at that point), and {@link #close()}
 * afterwards ends the write side and waits for the readers to hit EOF, so no byte is lost.
 */
final class ScriptLogs implements Closeable {
    private final PipedOutputStream out = new PipedOutputStream();
    private final PipedOutputStream err = new PipedOutputStream();
    private final PipedInputStream in;
    private final PipedInputStream inErr;
    private final Thread outReader;
    private final Thread errReader;

    ScriptLogs(Logger logger) throws IOException {
        this.in = new PipedInputStream(out, LogRunnable.PIPE_BUFFER_SIZE);
        this.inErr = new PipedInputStream(err, LogRunnable.PIPE_BUFFER_SIZE);
        this.outReader = Thread.ofPlatform().daemon().name("graalvm-log-out").start(new LogRunnable(in, false, logger));
        this.errReader = Thread.ofPlatform().daemon().name("graalvm-log-err").start(new LogRunnable(inErr, true, logger));
    }

    OutputStream out() {
        return out;
    }

    OutputStream err() {
        return err;
    }

    @Override
    public void close() {
        // closing the write side makes the readers return once the remaining buffered bytes are consumed
        closeQuietly(out);
        closeQuietly(err);
        try {
            outReader.join();
            errReader.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // only closed after the readers are done: closing a PipedInputStream discards unread data
            closeQuietly(in);
            closeQuietly(inErr);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing left to do
        }
    }
}
