package io.kestra.plugin.graalvm;

import org.slf4j.Logger;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.util.logging.LogRecord;

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
