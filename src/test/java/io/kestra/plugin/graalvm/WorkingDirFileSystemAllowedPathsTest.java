package io.kestra.plugin.graalvm;

import org.graalvm.polyglot.io.FileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkingDirFileSystemAllowedPathsTest {
    private static final String PLUGIN_TYPE = "io.kestra.plugin.graalvm.python.Eval";

    @TempDir
    Path tempDir;

    private Path root;
    private Path allowed;
    private Path outside;
    private FileSystem fs;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("workingDir")).toRealPath();
        allowed = Files.createDirectory(tempDir.resolve("data")).toRealPath();
        Files.writeString(allowed.resolve("file.txt"), "data");
        Files.createDirectory(allowed.resolve("sub"));
        Files.writeString(allowed.resolve("sub/nested.txt"), "nested");
        outside = Files.writeString(tempDir.resolve("secret.txt"), "secret").toRealPath();
        fs = WorkingDirFileSystem.create(root, List.of(allowed), PLUGIN_TYPE);
        fs.setCurrentWorkingDirectory(root);
    }

    @Test
    void readsInsideAllowedPath() throws IOException {
        assertThat(read(allowed.resolve("file.txt")), is("data"));
        assertThat(read(allowed.resolve("sub/../sub/nested.txt")), is("nested"));
        assertThat(fs.readAttributes(allowed.resolve("file.txt"), "size").get("size"), is(4L));
        assertThat(list(allowed), containsInAnyOrder(allowed.resolve("file.txt"), allowed.resolve("sub")));

        // relative paths work after changing into the allowed path
        fs.setCurrentWorkingDirectory(allowed.resolve("sub"));
        assertThat(read(Path.of("nested.txt")), is("nested"));
        assertThat(read(Path.of("../file.txt")), is("data"));
    }

    @Test
    void workingDirStaysWritable() throws IOException {
        write(Path.of("out.txt"), "out");
        assertThat(Files.readString(root.resolve("out.txt")), is("out"));

        // copying out of an allowed path only reads it
        fs.copy(allowed.resolve("file.txt"), Path.of("copy.txt"));
        assertThat(Files.readString(root.resolve("copy.txt")), is("data"));
    }

    @Test
    void deniesWritingToAllowedPath() throws IOException {
        var file = allowed.resolve("file.txt");
        var writes = List.<ThrowingRunnable>of(
            () -> write(allowed.resolve("new.txt"), "x"),
            () -> write(file, "x"),
            () -> fs.newByteChannel(file, Set.of(StandardOpenOption.APPEND)).close(),
            () -> fs.newByteChannel(file, Set.of(StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE)).close(),
            () -> fs.delete(file),
            () -> fs.createDirectory(allowed.resolve("dir")),
            () -> fs.setAttribute(file, "lastModifiedTime", FileTime.fromMillis(0)),
            () -> fs.move(file, Path.of("moved.txt")),
            () -> fs.move(allowed.resolve("sub"), Path.of("movedDir")),
            () -> fs.copy(root, allowed.resolve("copied")),
            // a hard link shares the content, so writing through it would change the read-only file
            () -> fs.createLink(Path.of("hardLink.txt"), file)
        );
        Files.writeString(root.resolve("inside.txt"), "inside");

        for (var operation : writes) {
            var exception = assertThrows(SecurityException.class, operation::run);
            assertThat(exception.getMessage(), containsString("read-only"));
        }
        assertThrows(SecurityException.class, () -> fs.move(Path.of("inside.txt"), allowed.resolve("moved.txt")));

        assertThat(Files.readString(file), is("data"));
        assertThat(Files.getLastModifiedTime(file).toMillis(), not(0L));
        // a denied move may leave a copy in the working dir (only a read), but the allowed path never changes
        try (var entries = Files.list(allowed)) {
            assertThat(entries.map(Path::getFileName).map(Path::toString).toList(), containsInAnyOrder("file.txt", "sub"));
        }
        assertThat(Files.readString(allowed.resolve("sub/nested.txt")), is("nested"));
        assertThat(Files.exists(root.resolve("hardLink.txt")), is(false));
    }

    @Test
    void reportsAllowedPathAsNotWritable() throws IOException {
        fs.checkAccess(allowed.resolve("file.txt"), Set.of(AccessMode.READ));
        assertThrows(AccessDeniedException.class, () -> fs.checkAccess(allowed.resolve("file.txt"), Set.of(AccessMode.WRITE)));
        // a missing file is still reported as missing
        assertThrows(NoSuchFileException.class, () -> fs.checkAccess(allowed.resolve("missing.txt"), Set.of(AccessMode.WRITE)));
    }

    @Test
    void deniesTraversalOutOfAllowedPath() {
        assertDenied(allowed.resolve("../secret.txt"));
        assertDenied(allowed.resolve("sub/../../secret.txt"));
        assertDenied(allowed.getParent());
        fs.setCurrentWorkingDirectory(allowed);
        assertDenied(Path.of("../secret.txt"));
    }

    @Test
    void deniesSymbolicLinksInAllowedPathPointingOutside() throws IOException {
        Files.createSymbolicLink(allowed.resolve("link.txt"), outside);
        Files.createSymbolicLink(allowed.resolve("linkDir"), outside.getParent());
        Files.createSymbolicLink(allowed.resolve("dangling.txt"), tempDir.resolve("missing.txt"));
        Files.createSymbolicLink(allowed.resolve("inside.txt"), allowed.resolve("file.txt"));

        assertDenied(allowed.resolve("link.txt"));
        assertDenied(allowed.resolve("linkDir/secret.txt"));
        assertDenied(allowed.resolve("dangling.txt"));
        assertThrows(SecurityException.class, () -> write(allowed.resolve("dangling.txt"), "x"));
        assertThat(Files.exists(tempDir.resolve("missing.txt")), is(false));
        assertThat(read(allowed.resolve("inside.txt")), is("data"));
    }

    @Test
    void deniesProbingWhatExistsOutside() {
        // the same denial whether the outside path exists, is missing, or is a file used as a directory
        assertDenied(outside);
        assertDenied(tempDir.resolve("missing.txt"));
        assertDenied(outside.resolve("x"));
        assertDenied(allowed.resolve("../missing/../data/file.txt"));
    }

    @Test
    void denialNamesThePluginTypeButNotTheAllowedPaths() {
        var exception = assertThrows(SecurityException.class, () -> read(outside));
        assertThat(exception.getMessage(), containsString("`allowed-paths` plugin configuration of `" + PLUGIN_TYPE + "`"));
        assertThat(exception.getMessage(), not(containsString(allowed.toString())));
    }

    @Test
    void acceptsAllowedPathGivenThroughSymbolicLink() throws IOException {
        var linkedAllowed = Files.createSymbolicLink(tempDir.resolve("linkedData"), allowed);
        var linkedFs = WorkingDirFileSystem.create(root, List.of(linkedAllowed), PLUGIN_TYPE);

        try (var channel = linkedFs.newByteChannel(linkedAllowed.resolve("file.txt"), Set.of(StandardOpenOption.READ))) {
            assertThat(channel.size(), is(4L));
        }
        assertThrows(SecurityException.class, () -> linkedFs.newByteChannel(linkedAllowed.resolveSibling("secret.txt"), Set.of(StandardOpenOption.READ)));
    }

    @Test
    void rejectsMissingOrInvalidAllowedPath() {
        var missing = assertThrows(IllegalArgumentException.class, () -> WorkingDirFileSystem.create(root, List.of(tempDir.resolve("missing")), PLUGIN_TYPE));
        assertThat(missing.getMessage(), containsString("does not exist"));

        var notDirectory = assertThrows(IllegalArgumentException.class, () -> WorkingDirFileSystem.create(root, List.of(outside), PLUGIN_TYPE));
        assertThat(notDirectory.getMessage(), containsString("is not a directory"));
    }

    @Test
    void parsesAllowedPathsConfiguration() {
        assertThat(AbstractScript.parseAllowedPaths(null, PLUGIN_TYPE), empty());
        assertThat(AbstractScript.parseAllowedPaths(List.of("/mnt/data", "/srv"), PLUGIN_TYPE), contains(Path.of("/mnt/data"), Path.of("/srv")));

        for (var invalid : List.<Object>of("/mnt/data", List.of("relative/dir"), List.of(""), List.of(42))) {
            var exception = assertThrows(IllegalArgumentException.class, () -> AbstractScript.parseAllowedPaths(invalid, PLUGIN_TYPE));
            assertThat(exception.getMessage(), startsWith("Invalid `allowed-paths` plugin configuration for `" + PLUGIN_TYPE + "`"));
        }
    }

    private void assertDenied(Path path) {
        var exception = assertThrows(SecurityException.class, () -> read(path));
        assertThat(exception.getMessage(), containsString("is denied"));
    }

    private List<Path> list(Path dir) throws IOException {
        var entries = new ArrayList<Path>();
        try (var stream = fs.newDirectoryStream(dir, p -> true)) {
            stream.forEach(entries::add);
        }
        return entries;
    }

    private void write(Path path, String content) throws IOException {
        try (var channel = fs.newByteChannel(path, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE))) {
            channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private String read(Path path) throws IOException {
        try (var channel = fs.newByteChannel(path, Set.of(StandardOpenOption.READ))) {
            var buffer = ByteBuffer.allocate((int) channel.size());
            channel.read(buffer);
            return new String(buffer.array(), StandardCharsets.UTF_8);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
