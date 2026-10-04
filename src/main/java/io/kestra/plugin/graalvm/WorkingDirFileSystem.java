package io.kestra.plugin.graalvm;

import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

// Restricts guest file access to the task working directory, resolving symlinks before checking paths.
// Guest code cannot create symlinks, so it cannot swap one in between the check and the file operation.
public final class WorkingDirFileSystem implements FileSystem {
    private static final String TEMP_DIRECTORY_NAME = ".tmp";
    // same as Linux MAXSYMLINKS
    private static final int MAX_DANGLING_LINKS = 40;

    private final FileSystem delegate = FileSystem.newDefaultFileSystem();
    private final Path root;
    private volatile Path currentWorkingDirectory;

    WorkingDirFileSystem(Path workingDirectory) throws IOException {
        this.root = workingDirectory.toRealPath();
        this.currentWorkingDirectory = this.root;
    }

    // internal resource access is needed for the Python and Ruby stdlib
    public static FileSystem create(Path workingDirectory) throws IOException {
        return FileSystem.allowInternalResourceAccess(new WorkingDirFileSystem(workingDirectory));
    }

    @Override
    public Path parsePath(URI uri) {
        return delegate.parsePath(uri);
    }

    @Override
    public Path parsePath(String path) {
        return delegate.parsePath(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        delegate.checkAccess(resolve(path, linkOptions), modes, linkOptions);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(resolveNoFollow(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        delegate.delete(resolveNoFollow(path));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        var resolved = options.contains(LinkOption.NOFOLLOW_LINKS) ? resolveNoFollow(path) : resolveFollow(path);
        return delegate.newByteChannel(resolved, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        return delegate.newDirectoryStream(resolveFollow(dir), filter);
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return path.isAbsolute() ? path : currentWorkingDirectory.resolve(path);
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
        return delegate.toRealPath(resolve(path, linkOptions), linkOptions);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return delegate.readAttributes(resolve(path, options), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        delegate.setAttribute(resolve(path, options), attribute, value, options);
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        boolean noFollow = Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS);
        Path resolvedSource;
        if (noFollow) {
            resolvedSource = resolveNoFollow(source);
            denyIfSymbolicLink(source, resolvedSource);
        } else {
            resolvedSource = resolveFollow(source);
        }
        delegate.copy(resolvedSource, resolveNoFollow(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        var resolvedSource = resolveNoFollow(source);
        denyIfContainsSymbolicLink(source, resolvedSource);
        delegate.move(resolvedSource, resolveNoFollow(target), options);
    }

    @Override
    public void createLink(Path link, Path existing) throws IOException {
        delegate.createLink(resolveNoFollow(link), resolveFollow(existing));
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) {
        throw new SecurityException("Creating symbolic links is not allowed in GraalVM scripts: '" + link + "' -> '" + target + "'. Write or copy the file into the task working directory instead.");
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return delegate.readSymbolicLink(resolveNoFollow(link));
    }

    @Override
    public void setCurrentWorkingDirectory(Path currentWorkingDirectory) {
        if (!currentWorkingDirectory.isAbsolute()) {
            throw new IllegalArgumentException("The current working directory must be an absolute path: " + currentWorkingDirectory);
        }
        try {
            this.currentWorkingDirectory = resolveFollow(currentWorkingDirectory);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to set the current working directory to '" + currentWorkingDirectory + "'", e);
        }
    }

    @Override
    public String getSeparator() {
        return delegate.getSeparator();
    }

    @Override
    public String getPathSeparator() {
        return delegate.getPathSeparator();
    }

    @Override
    public String getMimeType(Path path) {
        try {
            return delegate.getMimeType(resolveFollow(path));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    @Override
    public Charset getEncoding(Path path) {
        try {
            return delegate.getEncoding(resolveFollow(path));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    @Override
    public Path getTempDirectory() {
        var tempDirectory = root.resolve(TEMP_DIRECTORY_NAME);
        try {
            Files.createDirectories(tempDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create the temporary directory '" + tempDirectory + "' inside the task working directory", e);
        }
        return tempDirectory;
    }

    @Override
    public boolean isSameFile(Path path1, Path path2, LinkOption... options) throws IOException {
        return delegate.isSameFile(resolve(path1, options), resolve(path2, options), options);
    }

    private Path resolve(Path path, LinkOption... linkOptions) throws IOException {
        return Arrays.asList(linkOptions).contains(LinkOption.NOFOLLOW_LINKS) ? resolveNoFollow(path) : resolveFollow(path);
    }

    private Path resolveFollow(Path path) throws IOException {
        return checkInsideRoot(path, realPath(path, toAbsolutePath(path), 0));
    }

    // not checked against the root, so the working dir's own parent can be resolved too
    private Path realPath(Path original, Path absolute, int danglingLinksFollowed) throws IOException {
        var existing = absolute;
        while (existing != null) {
            Path real;
            try {
                real = existing.toRealPath();
            } catch (NoSuchFileException e) {
                if (Files.isSymbolicLink(existing)) {
                    // the OS would follow a dangling link on create, so resolve it too
                    if (danglingLinksFollowed >= MAX_DANGLING_LINKS) {
                        throw new FileSystemException(original.toString(), null, "Too many levels of symbolic links");
                    }
                    var target = existing.resolveSibling(Files.readSymbolicLink(existing));
                    return realPath(original, target.resolve(remainder(existing, absolute)), danglingLinksFollowed + 1);
                }
                existing = existing.getParent();
                continue;
            }
            var missing = remainder(existing, absolute);
            // like the OS, never go back up through a missing directory: .. would be resolved as text
            for (var name : missing) {
                if (name.toString().equals("..")) {
                    throw new NoSuchFileException(original.toString());
                }
            }
            return real.resolve(missing).normalize();
        }
        throw deny(original);
    }

    private Path resolveNoFollow(Path path) throws IOException {
        var absolute = toAbsolutePath(path).normalize();
        var parent = absolute.getParent();
        var fileName = absolute.getFileName();
        if (parent == null || fileName == null) {
            throw deny(path);
        }
        return checkInsideRoot(path, realPath(path, parent, 0).resolve(fileName));
    }

    // unlike relativize(), keeps .. as is
    private static Path remainder(Path ancestor, Path path) {
        var count = ancestor.getNameCount();
        return count == path.getNameCount() ? Path.of("") : path.subpath(count, path.getNameCount());
    }

    private Path checkInsideRoot(Path original, Path resolved) {
        if (!resolved.startsWith(root)) {
            throw deny(original);
        }
        return resolved;
    }

    private void denyIfSymbolicLink(Path original, Path resolved) {
        if (Files.isSymbolicLink(resolved)) {
            throw new SecurityException("Moving or copying the symbolic link '" + original + "' is not allowed in GraalVM scripts. Copy the file it points to instead.");
        }
    }

    private void denyIfContainsSymbolicLink(Path original, Path resolved) throws IOException {
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            denyIfSymbolicLink(original, resolved);
            return;
        }
        try (var paths = Files.walk(resolved)) {
            if (paths.anyMatch(Files::isSymbolicLink)) {
                throw new SecurityException("Moving '" + original + "' is not allowed in GraalVM scripts because it contains a symbolic link.");
            }
        }
    }

    private SecurityException deny(Path path) {
        return new SecurityException("Access to '" + path + "' is denied: GraalVM scripts can only access files inside the task working directory '" + root + "'.");
    }
}
