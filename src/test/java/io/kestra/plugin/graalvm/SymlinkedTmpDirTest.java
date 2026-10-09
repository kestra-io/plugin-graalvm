package io.kestra.plugin.graalvm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SymlinkedTmpDirTest {
    @Test
    void tasksRunWhenWorkingDirIsReachedThroughSymbolicLink(@TempDir Path tempDir) throws Exception {
        var realTmp = Files.createDirectory(tempDir.resolve("realtmp"));
        var linkTmp = Files.createSymbolicLink(tempDir.resolve("linktmp"), realTmp);

        ForkedJvm.run(
            List.of("-Djava.io.tmpdir=" + linkTmp),
            SymlinkedTmpDirForkMain.class,
            SymlinkedTmpDirForkMain.SUCCESS_MARKER
        );
    }
}
