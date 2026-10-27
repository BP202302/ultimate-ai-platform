package ai.ultimate.tools.image;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ImageWorkspaceTest {
    @TempDir Path root;
    UUID tenant;
    Path workspace;
    ImageWorkspace files;

    @BeforeEach void setup() throws Exception {
        tenant = UUID.randomUUID();
        workspace = Files.createDirectories(root.resolve(tenant.toString()).resolve("session"));
        Files.createDirectory(workspace.resolve("out"));
        Files.write(workspace.resolve("image.png"), new byte[]{1, 2, 3});
        files = new ImageWorkspace(root, tenant, "session");
    }

    @Test void resolvesCanonicalInputAndOutput() throws Exception {
        assertEquals(List.of(workspace.resolve("image.png").toRealPath()), files.inputs(List.of("image.png")));
        assertEquals(workspace.resolve("out").toRealPath(), files.outputDirectory("out"));
    }
    @Test void requiresTrustedTenant() {
        assertThrows(IllegalArgumentException.class, () -> new ImageWorkspace(root, null, "session"));
    }
    @Test void rejectsAbsoluteWorkspace() {
        assertThrows(IOException.class, () -> new ImageWorkspace(root, tenant, workspace.toString()));
    }
    @Test void cannotSelectAnotherTenantWorkspace() throws Exception {
        UUID other = UUID.randomUUID();
        Files.createDirectories(root.resolve(other.toString()).resolve("session"));
        assertThrows(IOException.class, () -> new ImageWorkspace(root, tenant, "../" + other + "/session"));
    }
    @Test void rejectsParentTraversal() {
        assertThrows(IOException.class, () -> files.inputs(List.of("out/../image.png")));
        assertThrows(IOException.class, () -> files.outputDirectory("../"));
    }
    @Test void rejectsSymlinkedInputAndDirectory() throws Exception {
        Files.createSymbolicLink(workspace.resolve("alias.png"), workspace.resolve("image.png"));
        Files.createSymbolicLink(workspace.resolve("alias-dir"), workspace.resolve("out"));
        assertThrows(IOException.class, () -> files.inputs(List.of("alias.png")));
        assertThrows(IOException.class, () -> files.outputDirectory("alias-dir"));
    }
    @Test void rejectsSymlinkedTenantEvenIfInsideRoot() throws Exception {
        UUID alias = UUID.randomUUID();
        Files.createSymbolicLink(root.resolve(alias.toString()), root.resolve(tenant.toString()));
        assertThrows(IOException.class, () -> new ImageWorkspace(root, alias, "session"));
    }
    @Test void rejectsMissingAndDirectoryInputs() {
        assertThrows(IOException.class, () -> files.inputs(List.of("absent.png")));
        assertThrows(IOException.class, () -> files.inputs(List.of("out")));
    }
    @Test void rejectsDuplicateAndOversizedBatches() {
        assertThrows(IOException.class, () -> files.inputs(List.of("image.png", "./image.png")));
        assertThrows(IllegalArgumentException.class, () -> files.inputs(java.util.Collections.nCopies(11, "image.png")));
    }
    @Test void rejectsEmptyAndOversizedInputs() throws Exception {
        Files.createFile(workspace.resolve("empty.png"));
        assertThrows(IOException.class, () -> files.inputs(List.of("empty.png")));
        try (var f = new java.io.RandomAccessFile(workspace.resolve("large.png").toFile(), "rw")) {
            f.setLength(20L * 1024 * 1024 + 1);
        }
        assertThrows(IOException.class, () -> files.inputs(List.of("large.png")));
    }
    @Test void rejectsHardLinkedInput() throws Exception {
        Files.createLink(workspace.resolve("linked.png"), workspace.resolve("image.png"));
        assertThrows(IOException.class, () -> files.inputs(List.of("linked.png")));
    }
    @Test void cleanupDoesNotFollowSymlinksAndLeavesDeliverables() throws Exception {
        Path scratch = Files.createTempDirectory(workspace, ".images-");
        Path outside = Files.writeString(root.resolve("keep"), "keep");
        Files.createSymbolicLink(scratch.resolve("outside"), outside);
        Files.createDirectories(scratch.resolve("nested"));
        Files.writeString(scratch.resolve("nested/data"), "scratch");
        Files.writeString(workspace.resolve("out/result.webp"), "result");
        ImageWorkspace.cleanup(scratch);
        assertFalse(Files.exists(scratch));
        assertEquals("keep", Files.readString(outside));
        assertEquals("result", Files.readString(workspace.resolve("out/result.webp")));
    }
}
