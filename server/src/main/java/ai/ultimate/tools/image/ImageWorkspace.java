package ai.ultimate.tools.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Filesystem validation only. The tenant UUID must come from trusted application context,
 * never from an AI tool argument. This class does not grant authorization by itself. */
public final class ImageWorkspace {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long MAX_BATCH_BYTES = 80L * 1024 * 1024;
    private final Path workspace;

    public ImageWorkspace(Path managedRoot, UUID authenticatedTenant, String relativeWorkspace)
            throws IOException {
        if (authenticatedTenant == null) throw new IllegalArgumentException("Authenticated tenant required");
        Path root = managedRoot.toRealPath();
        Path tenantCandidate = root.resolve(authenticatedTenant.toString());
        rejectSymlinks(root, tenantCandidate);
        Path tenant = tenantCandidate.toRealPath();
        if (!tenant.startsWith(root) || tenant.equals(root))
            throw new IOException("Tenant escapes managed root");
        rejectSymlinks(root, tenant);
        workspace = resolveInside(tenant, relativeWorkspace, true);
    }

    public Path directory() { return workspace; }

    public Path outputDirectory(String relative) throws IOException {
        return resolveInside(workspace, relative, true);
    }

    /** Resolve the complete batch before any process starts or temporary data is created. */
    public List<Path> inputs(List<String> relativePaths) throws IOException {
        if (relativePaths == null || relativePaths.isEmpty() || relativePaths.size() > 10)
            throw new IllegalArgumentException("Provide 1-10 input images");
        List<Path> result = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        long bytes = 0;
        for (String relative : relativePaths) {
            Path input = resolveInside(workspace, relative, false);
            // Reject hard-linked files: a managed path alone does not establish ownership
            // of an inode also reachable outside this workspace.
            try {
                if (((Number) Files.getAttribute(input, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                        .longValue() != 1) throw new IOException("Hard-linked inputs are not allowed");
            } catch (UnsupportedOperationException e) {
                throw new IOException("Filesystem cannot verify input link ownership", e);
            }
            long size = Files.size(input);
            if (size == 0 || size > MAX_BYTES) throw new IOException("Input size exceeds limits");
            bytes += size;
            if (bytes > MAX_BATCH_BYTES) throw new IOException("Batch size exceeds limits");
            if (!seen.add(input)) throw new IOException("Duplicate image input");
            result.add(input);
        }
        return List.copyOf(result);
    }

    private static Path resolveInside(Path root, String relative, boolean directory) throws IOException {
        if (relative == null || relative.isBlank() || relative.length() > 1024)
            throw new IllegalArgumentException("Relative path required");
        Path part = Path.of(relative);
        if (part.isAbsolute()) throw new IOException("Absolute paths are not allowed");
        // Reject parent components even when normalization would bring them back inside.
        for (Path segment : part) if (segment.toString().equals(".."))
            throw new IOException("Parent traversal is not allowed");
        Path candidate = root.resolve(part).normalize();
        if (!candidate.startsWith(root)) throw new IOException("Path escapes workspace");
        rejectSymlinks(root, candidate);
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) throw new IOException("Canonical path escapes workspace");
        if (directory ? !Files.isDirectory(real) : !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS))
            throw new IOException(directory ? "Existing directory required" : "Regular image file required");
        return real;
    }

    private static void rejectSymlinks(Path root, Path target) throws IOException {
        Path cursor = root;
        for (Path part : root.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symbolic links are not allowed");
        }
    }

    /** Walk without following links, delete children before parents, and expose failures. */
    public static void cleanup(Path scratch) throws IOException {
        if (scratch == null || !Files.exists(scratch, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> paths;
        try (var walk = Files.walk(scratch)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        IOException failure = null;
        for (Path path : paths) {
            try { Files.deleteIfExists(path); }
            catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
