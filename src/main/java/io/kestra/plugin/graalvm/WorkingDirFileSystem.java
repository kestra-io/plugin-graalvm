package io.kestra.plugin.graalvm;

import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// Restricts guest file access to the task working directory and read-only allowed paths, resolving symlinks first.
// Guest code cannot create symlinks, so it cannot swap one in between the check and the file operation.
public final class WorkingDirFileSystem implements FileSystem {
    static final String ALLOWED_PATHS = "allowed-paths";
    private static final String TEMP_DIRECTORY_NAME = ".tmp";
    // same as Linux MAXSYMLINKS
    private static final int MAX_SYMBOLIC_LINKS = 40;
    private static final Set<OpenOption> WRITE_OPTIONS = Set.of(
        StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.CREATE, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.DELETE_ON_CLOSE
    );

    private final FileSystem delegate = FileSystem.newDefaultFileSystem();
    private final Path root;
    // real paths of the directories from the allowed-paths plugin configuration, which scripts can only read
    private final List<Path> readOnlyRoots = new ArrayList<>();
    // paths visited to resolve the working dir and the allowed paths as given, which may go through host symlinks
    // such as /var -> /private/var on macOS; chosen by the host, not the script, so probing them reveals nothing
    private final Set<Path> pathsToRoot = new HashSet<>();
    private final String pluginType;
    private volatile Path currentWorkingDirectory;

    WorkingDirFileSystem(Path workingDirectory) throws IOException {
        this(workingDirectory, List.of(), null);
    }

    WorkingDirFileSystem(Path workingDirectory, List<Path> allowedPaths, String pluginType) throws IOException {
        this.root = workingDirectory.toRealPath();
        this.pluginType = pluginType;
        addPathsTo(root, workingDirectory);
        for (var allowedPath : allowedPaths) {
            Path realPath;
            try {
                realPath = allowedPath.toRealPath();
            } catch (NoSuchFileException e) {
                throw new IllegalArgumentException("The directory '" + allowedPath + "' from the `" + ALLOWED_PATHS + "` plugin configuration of `" + pluginType + "` does not exist.", e);
            }
            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException("The path '" + allowedPath + "' from the `" + ALLOWED_PATHS + "` plugin configuration of `" + pluginType + "` is not a directory.");
            }
            readOnlyRoots.add(realPath);
            addPathsTo(realPath, allowedPath);
        }
        this.currentWorkingDirectory = this.root;
    }

    private void addPathsTo(Path realPath, Path givenPath) throws IOException {
        for (var ancestor = realPath; ancestor != null; ancestor = ancestor.getParent()) {
            pathsToRoot.add(ancestor);
        }
        walk(givenPath, givenPath.toAbsolutePath(), true, pathsToRoot::add);
    }

    public static FileSystem create(Path workingDirectory) throws IOException {
        return create(workingDirectory, List.of(), null);
    }

    // The Python and Ruby stdlib live in the engine-wide resource cache shared by every task on the worker,
    // so scripts can read it but never write to it. Paths outside the working dir, the allowed paths and the paths
    // leading to them go to a read-only view of the internal resources (anything else is denied by GraalVM).
    public static FileSystem create(Path workingDirectory, List<Path> allowedPaths, String pluginType) throws IOException {
        var workingDir = new WorkingDirFileSystem(workingDirectory, allowedPaths, pluginType);
        var internalResources = new ReadOnlyInternalResources(
            FileSystem.newReadOnlyFileSystem(FileSystem.allowInternalResourceAccess(FileSystem.newDenyIOFileSystem())),
            workingDir
        );
        return FileSystem.newCompositeFileSystem(
            workingDir,
            FileSystem.Selector.of(internalResources, path -> !workingDir.isAccessible(path))
        );
    }

    // Whether the working dir file system should handle the path (and decide to allow or deny it); false means the
    // path is outside of it and may only be an internal resource. Follows symlinks like realPath, so a working dir or
    // an allowed path reached through a host symlink matches, and never touches the host for a name outside them.
    boolean isAccessible(Path path) {
        var absolute = toAbsolutePath(path);
        var normalized = absolute.normalize();
        // lexically inside: symlinks, if any, are checked by the operation itself
        if (isInsideRoots(normalized) || pathsToRoot.contains(normalized)) {
            return true;
        }
        try {
            var resolved = walk(path, absolute, false, candidate -> {
                if (!isInsideRoots(candidate) && !pathsToRoot.contains(candidate)) {
                    throw deny(path);
                }
            });
            return isInsideRoots(resolved) || pathsToRoot.contains(resolved);
        } catch (SecurityException e) {
            return false;
        } catch (IOException e) {
            // let the working dir file system report the error itself
            return true;
        }
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
        var resolved = resolve(path, linkOptions);
        delegate.checkAccess(resolved, modes, linkOptions);
        // checked after the host, so a missing file is still reported as missing
        if (modes.contains(AccessMode.WRITE) && !resolved.startsWith(root)) {
            throw new AccessDeniedException(path.toString(), null, "directories from the `" + ALLOWED_PATHS + "` plugin configuration are read-only for GraalVM scripts");
        }
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(resolveWritable(dir, false), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        delegate.delete(resolveWritable(path, false));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        var followFinalLink = !options.contains(LinkOption.NOFOLLOW_LINKS);
        var resolved = options.stream().anyMatch(WRITE_OPTIONS::contains)
            ? resolveWritable(path, followFinalLink)
            : realPath(path, followFinalLink);
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
        delegate.setAttribute(resolveWritable(path, !Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS)), attribute, value, options);
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
        delegate.copy(resolvedSource, resolveWritable(target, false), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        var resolvedSource = resolveWritable(source, false);
        denyIfContainsSymbolicLink(source, resolvedSource);
        delegate.move(resolvedSource, resolveWritable(target, false), options);
    }

    @Override
    public void createLink(Path link, Path existing) throws IOException {
        // a hard link shares the file content, so linking a read-only file would let the script write to it
        delegate.createLink(resolveWritable(link, false), resolveWritable(existing, true));
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
        return realPath(path, true);
    }

    private Path resolveNoFollow(Path path) throws IOException {
        return realPath(path, false);
    }

    // Resolves the path one name at a time like the OS, following symlinks (including dangling ones, which the
    // OS follows on create). A name outside the working dir, the allowed paths and the paths to them is denied
    // before the host is touched, so errors from the host never reveal what exists outside of them.
    private Path realPath(Path original, boolean followFinalLink) throws IOException {
        var resolved = walk(original, toAbsolutePath(original), followFinalLink, candidate -> {
            if (!isInsideRoots(candidate) && !pathsToRoot.contains(candidate)) {
                throw deny(original);
            }
        });
        if (!isInsideRoots(resolved)) {
            throw deny(original);
        }
        return resolved;
    }

    // only the working dir is writable, the allowed paths are read-only
    private Path resolveWritable(Path original, boolean followFinalLink) throws IOException {
        var resolved = realPath(original, followFinalLink);
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Writing to '" + original + "' is denied: directories from the `" + ALLOWED_PATHS + "` plugin configuration are read-only for GraalVM scripts. Write into the task working directory '" + root + "' instead.");
        }
        return resolved;
    }

    private boolean isInsideRoots(Path path) {
        if (path.startsWith(root)) {
            return true;
        }
        for (var readOnlyRoot : readOnlyRoots) {
            if (path.startsWith(readOnlyRoot)) {
                return true;
            }
        }
        return false;
    }

    // visit is called on each name before the host is touched for it
    private static Path walk(Path original, Path absolute, boolean followFinalLink, Consumer<Path> visit) throws IOException {
        var pending = new ArrayDeque<Path>();
        absolute.forEach(pending::add);
        var current = absolute.getRoot();
        var currentExists = true;
        var linksFollowed = 0;

        while (!pending.isEmpty()) {
            var name = pending.removeFirst().toString();
            if (name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                // like the OS, never go back up through a missing directory
                if (!currentExists) {
                    throw new NoSuchFileException(original.toString());
                }
                // current never contains a symlink, so its parent is the real parent
                current = current.getParent() == null ? current : current.getParent();
                continue;
            }

            var candidate = current.resolve(name);
            visit.accept(candidate);
            current = candidate;
            if (!currentExists) {
                continue;
            }

            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                currentExists = false;
                continue;
            }
            if (attributes.isSymbolicLink() && (followFinalLink || !pending.isEmpty())) {
                if (++linksFollowed > MAX_SYMBOLIC_LINKS) {
                    throw new FileSystemException(original.toString(), null, "Too many levels of symbolic links");
                }
                var target = Files.readSymbolicLink(candidate);
                var targetNames = new ArrayList<Path>();
                target.forEach(targetNames::add);
                for (var i = targetNames.size() - 1; i >= 0; i--) {
                    pending.addFirst(targetNames.get(i));
                }
                current = target.isAbsolute() ? target.getRoot() : candidate.getParent();
            } else if (!attributes.isDirectory() && !pending.isEmpty()) {
                throw new NotDirectoryException(original.toString());
            }
        }
        return current;
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
        return new SecurityException(denialMessage(path));
    }

    // never lists the allowed paths: scripts cannot read the plugin configuration
    private String denialMessage(Path path) {
        var allowed = readOnlyRoots.isEmpty() ? "" : " and the directories allowed for this task";
        return "Access to '" + path + "' is denied: GraalVM scripts can only access files inside the task working directory '" + root + "'" + allowed
            + ". To let scripts read another directory, add it to the `" + ALLOWED_PATHS + "` plugin configuration of " + (pluginType != null ? "`" + pluginType + "`" : "this task type") + ".";
    }

    // Replaces GraalVM's opaque denial with an actionable message.
    private record ReadOnlyInternalResources(FileSystem delegate, WorkingDirFileSystem workingDir) implements FileSystem {
        private interface IOCall<T> {
            T call() throws IOException;
        }

        // resolve against the working dir cwd used for routing, not the delegate's
        private Path abs(Path path) {
            return workingDir.toAbsolutePath(path);
        }

        private <T> T guard(Path path, IOCall<T> call) throws IOException {
            try {
                return call.call();
            } catch (SecurityException e) {
                throw new SecurityException(workingDir.denialMessage(path) + " The bundled standard library is read-only.", e);
            }
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
            guard(path, () -> {
                delegate.checkAccess(abs(path), modes, linkOptions);
                return null;
            });
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
            guard(dir, () -> {
                delegate.createDirectory(abs(dir), attrs);
                return null;
            });
        }

        @Override
        public void delete(Path path) throws IOException {
            guard(path, () -> {
                delegate.delete(abs(path));
                return null;
            });
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            return guard(path, () -> delegate.newByteChannel(abs(path), options, attrs));
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
            return guard(dir, () -> delegate.newDirectoryStream(abs(dir), filter));
        }

        @Override
        public Path toAbsolutePath(Path path) {
            return workingDir.toAbsolutePath(path);
        }

        @Override
        public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
            return guard(path, () -> delegate.toRealPath(abs(path), linkOptions));
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
            return guard(path, () -> delegate.readAttributes(abs(path), attributes, options));
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
            guard(path, () -> {
                delegate.setAttribute(abs(path), attribute, value, options);
                return null;
            });
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            guard(target, () -> {
                delegate.copy(abs(source), abs(target), options);
                return null;
            });
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            guard(source, () -> {
                delegate.move(abs(source), abs(target), options);
                return null;
            });
        }

        @Override
        public void createLink(Path link, Path existing) throws IOException {
            guard(link, () -> {
                delegate.createLink(abs(link), abs(existing));
                return null;
            });
        }

        @Override
        public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
            guard(link, () -> {
                delegate.createSymbolicLink(abs(link), target, attrs);
                return null;
            });
        }

        @Override
        public Path readSymbolicLink(Path link) throws IOException {
            return guard(link, () -> delegate.readSymbolicLink(abs(link)));
        }

        @Override
        public void setCurrentWorkingDirectory(Path currentWorkingDirectory) {
            delegate.setCurrentWorkingDirectory(currentWorkingDirectory);
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
            return delegate.getMimeType(path);
        }

        @Override
        public Charset getEncoding(Path path) {
            return delegate.getEncoding(path);
        }

        @Override
        public Path getTempDirectory() {
            return delegate.getTempDirectory();
        }

        @Override
        public boolean isSameFile(Path path1, Path path2, LinkOption... options) throws IOException {
            return guard(path1, () -> delegate.isSameFile(abs(path1), abs(path2), options));
        }
    }
}
