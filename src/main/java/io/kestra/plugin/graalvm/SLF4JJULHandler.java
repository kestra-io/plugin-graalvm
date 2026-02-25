package io.kestra.plugin.graalvm;

import java.util.logging.LogRecord;

import org.slf4j.Logger;
import org.slf4j.bridge.SLF4JBridgeHandler;

class SLF4JJULHandler extends SLF4JBridgeHandler {
    private final Logger logger;

    SLF4JJULHandler(Logger logger) {
        this.logger = logger;
    }

    @Override
    protected Logger getSLF4JLogger(LogRecord record) {
        return this.logger;
    }
}
