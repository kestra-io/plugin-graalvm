package io.kestra.plugin.graalvm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkingDirFileSystemTest {
    @TempDir
    Path tempDir;

    private Path root;
    private Path outside;
    private WorkingDirFileSystem fs;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("workingDir")).toRealPath();
        outside = Files.writeString(tempDir.resolve("secret.txt"), "secret").toRealPath();
        fs = new WorkingDirFileSystem(root);
        fs.setCurrentWorkingDirectory(root);
    }

    @Test
    void readsAndWritesInsideWorkingDir() throws IOException {
        Files.createDirectory(root.resolve("nested"));
        write(Path.of("nested/../file.txt"), "hello");
        assertThat(read(root.resolve("file.txt")), is("hello"));

        fs.createDirectory(Path.of("dir"));
        write(root.resolve("dir/inner.txt"), "inner");
        assertThat(read(Path.of("dir/inner.txt")), is("inner"));

        var entries = new ArrayList<Path>();
        try (var stream = fs.newDirectoryStream(Path.of("dir"), p -> true)) {
            stream.forEach(entries::add);
        }
        assertThat(entries, contains(root.resolve("dir/inner.txt")));

        fs.move(Path.of("dir/inner.txt"), Path.of("moved.txt"));
        fs.copy(Path.of("moved.txt"), Path.of("copied.txt"));
        fs.delete(Path.of("moved.txt"));
        assertThat(read(Path.of("copied.txt")), is("inner"));
    }

    @Test
    void resolvesRelativePathsAgainstCurrentWorkingDirectory() throws IOException {
        Files.createDirectory(root.resolve("sub"));
        fs.setCurrentWorkingDirectory(root.resolve("sub"));
        write(Path.of("file.txt"), "hello");

        assertThat(Files.readString(root.resolve("sub/file.txt")), is("hello"));
    }

    @Test
    void deniesAbsolutePathOutside() {
        assertThrows(SecurityException.class, () -> read(outside));
        assertThrows(SecurityException.class, () -> fs.readAttributes(outside, "basic:size"));
        assertThrows(SecurityException.class, () -> fs.checkAccess(outside, Set.of(AccessMode.READ)));
        assertThrows(SecurityException.class, () -> fs.toRealPath(outside));
        assertThrows(SecurityException.class, () -> fs.newDirectoryStream(tempDir, p -> true));
        assertThrows(SecurityException.class, () -> fs.delete(outside));
        assertThrows(SecurityException.class, () -> fs.newDirectoryStream(Path.of("/"), p -> true));
    }

    @Test
    void deniesParentTraversal() {
        assertThrows(SecurityException.class, () -> read(Path.of("../secret.txt")));
        assertThrows(SecurityException.class, () -> read(root.resolve("../secret.txt")));
        // like the OS, a missing directory cannot be traversed with ..
        assertThrows(NoSuchFileException.class, () -> read(Path.of("missing/../../secret.txt")));
        assertThrows(SecurityException.class, () -> write(Path.of("../created.txt"), "x"));
        assertThat(Files.exists(tempDir.resolve("created.txt")), is(false));
    }

    @Test
    void deniesSymbolicLinkPointingOutside() throws IOException {
        Files.createSymbolicLink(root.resolve("link.txt"), outside);
        Files.createSymbolicLink(root.resolve("linkDir"), tempDir);

        assertThrows(SecurityException.class, () -> read(Path.of("link.txt")));
        assertThrows(SecurityException.class, () -> read(Path.of("linkDir/secret.txt")));
        assertThrows(SecurityException.class, () -> write(Path.of("linkDir/created.txt"), "x"));
        assertThrows(SecurityException.class, () -> fs.createDirectory(Path.of("linkDir/newDir")));
        assertThat(Files.exists(tempDir.resolve("created.txt")), is(false));
        assertThat(Files.exists(tempDir.resolve("newDir")), is(false));
    }

    @Test
    void deniesWritingThroughDanglingSymbolicLink() throws IOException {
        // the OS would follow the link and create its target outside
        Files.createSymbolicLink(root.resolve("dangling.txt"), tempDir.resolve("created.txt"));
        Files.createSymbolicLink(root.resolve("danglingDir"), tempDir.resolve("missingDir"));

        assertThrows(SecurityException.class, () -> write(Path.of("dangling.txt"), "x"));
        assertThrows(SecurityException.class, () -> write(Path.of("danglingDir/created.txt"), "x"));
        // mkdir does not follow a final link
        assertThrows(FileAlreadyExistsException.class, () -> fs.createDirectory(Path.of("danglingDir")));
        assertThat(Files.exists(tempDir.resolve("created.txt")), is(false));
        assertThat(Files.exists(tempDir.resolve("missingDir")), is(false));
    }

    @Test
    void followsDanglingSymbolicLinkPointingInside() throws IOException {
        Files.createSymbolicLink(root.resolve("dangling.txt"), Path.of("target.txt"));

        write(Path.of("dangling.txt"), "inside");

        assertThat(Files.readString(root.resolve("target.txt")), is("inside"));
    }

    @Test
    void deniesDanglingSymbolicLinkLoop() throws IOException {
        Files.createSymbolicLink(root.resolve("a"), root.resolve("b"));
        Files.createSymbolicLink(root.resolve("b"), root.resolve("a"));

        assertThrows(FileSystemException.class, () -> write(Path.of("a"), "x"));
    }

    @Test
    void allowsSymbolicLinkPointingInside() throws IOException {
        Files.writeString(root.resolve("target.txt"), "inside");
        Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("target.txt"));

        assertThat(read(Path.of("link.txt")), is("inside"));
    }

    @Test
    void operatesOnLinkItselfWithoutFollowingIt() throws IOException {
        Files.createSymbolicLink(root.resolve("link.txt"), outside);

        assertThat(fs.readSymbolicLink(Path.of("link.txt")), is(outside));
        assertThat(fs.readAttributes(Path.of("link.txt"), "basic:isSymbolicLink", LinkOption.NOFOLLOW_LINKS).get("isSymbolicLink"), is(true));

        fs.delete(Path.of("link.txt"));
        assertThat(Files.exists(root.resolve("link.txt"), LinkOption.NOFOLLOW_LINKS), is(false));
        assertThat(Files.readString(outside), is("secret"));
    }

    @Test
    void deniesDotDotAfterMissingDirectoryThroughSymbolicLink() throws IOException {
        Files.createSymbolicLink(root.resolve("linkDir"), tempDir);

        assertThrows(NoSuchFileException.class, () -> read(Path.of("missing/../linkDir/secret.txt")));
        assertThrows(NoSuchFileException.class, () -> write(Path.of("missing/../linkDir/created.txt"), "x"));
        assertThat(Files.exists(tempDir.resolve("created.txt")), is(false));
    }

    @Test
    void deniesMovingDirectoryContainingSymbolicLink() throws IOException {
        // otherwise a script could rename it over a directory another thread already checked
        Files.createDirectories(root.resolve("d"));
        Files.createSymbolicLink(root.resolve("d/b"), tempDir);
        Files.createDirectories(root.resolve("plain/sub"));

        assertThrows(SecurityException.class, () -> fs.move(Path.of("d"), Path.of("a")));
        fs.move(Path.of("plain"), Path.of("moved"));
        assertThat(Files.isDirectory(root.resolve("moved/sub")), is(true));
    }

    @Test
    void deniesCreatingOrRelocatingSymbolicLinks() throws IOException {
        Files.writeString(root.resolve("target.txt"), "inside");
        assertThrows(SecurityException.class, () -> fs.createSymbolicLink(Path.of("link.txt"), Path.of("target.txt")));

        Files.createSymbolicLink(root.resolve("hostLink"), tempDir);
        assertThrows(SecurityException.class, () -> fs.move(Path.of("hostLink"), Path.of("moved")));
        assertThrows(SecurityException.class, () -> fs.copy(Path.of("hostLink"), Path.of("copied"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void allowsNoFollowOperationsOnWorkingDirItself() throws IOException {
        Files.createDirectory(root.resolve("sub"));

        assertThat(fs.readAttributes(Path.of("."), "basic:isDirectory", LinkOption.NOFOLLOW_LINKS).get("isDirectory"), is(true));
        assertThat(fs.readAttributes(Path.of("sub/.."), "basic:isDirectory", LinkOption.NOFOLLOW_LINKS).get("isDirectory"), is(true));
        assertThat(fs.readAttributes(root, "basic:isDirectory", LinkOption.NOFOLLOW_LINKS).get("isDirectory"), is(true));
    }

    @Test
    void deniesCopyMoveAndHardLinkAcrossTheBoundary() throws IOException {
        Files.writeString(root.resolve("file.txt"), "inside");

        assertThrows(SecurityException.class, () -> fs.copy(outside, Path.of("stolen.txt")));
        assertThrows(SecurityException.class, () -> fs.copy(Path.of("file.txt"), tempDir.resolve("leaked.txt")));
        assertThrows(SecurityException.class, () -> fs.move(Path.of("file.txt"), tempDir.resolve("leaked.txt")));
        assertThrows(SecurityException.class, () -> fs.createLink(Path.of("hard.txt"), outside));
        assertThat(Files.exists(root.resolve("stolen.txt")), is(false));
        assertThat(Files.exists(tempDir.resolve("leaked.txt")), is(false));
    }

    @Test
    void deniesChangingCurrentWorkingDirectoryOutside() {
        assertThrows(SecurityException.class, () -> fs.setCurrentWorkingDirectory(tempDir));
    }

    @Test
    void temporaryDirectoryIsInsideWorkingDir() throws IOException {
        var temp = fs.getTempDirectory();

        assertThat(temp.startsWith(root), is(true));
        assertThat(Files.isDirectory(temp), is(true));
        write(temp.resolve("tmp.txt"), "tmp");
    }

    @Test
    void createRejectsMissingWorkingDir() {
        assertThrows(NoSuchFileException.class, () -> WorkingDirFileSystem.create(tempDir.resolve("missing")));
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
}
