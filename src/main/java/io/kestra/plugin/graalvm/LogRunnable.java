package io.kestra.plugin.graalvm;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

class LogRunnable implements Runnable {
    private final InputStream inputStream;
    private final boolean isStdErr;
    private final Logger logger;


    protected LogRunnable(InputStream inputStream, boolean isStdErr, Logger logger) {
        this.inputStream = inputStream;
        this.isStdErr = isStdErr;
        this.logger = logger;
    }

    @Override
    public void run() {
        try {
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);

            try (BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    if (isStdErr){
                        logger.error(line);
                    } else {
                        logger.info(line);
                    }
                }
            }
        } catch (Exception e) {
            // silently fail if we cannot log a line
        }
    }
}
