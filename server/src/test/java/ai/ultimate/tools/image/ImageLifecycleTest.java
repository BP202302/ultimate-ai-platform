package ai.ultimate.tools.image;

import ai.ultimate.tools.builtin.ImageProcessingTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded application-level lifecycle checks; no provider or model is called. */
class ImageLifecycleTest {
    @TempDir Path root;
    final UUID tenant = UUID.randomUUID();

    Path fixture() throws Exception {
        Path workspace = Files.createDirectories(root.resolve(tenant.toString()).resolve("session"));
        Files.createDirectory(workspace.resolve("out"));
        ImageIO.write(new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "png",
            workspace.resolve("input.png").toFile());
        Files.writeString(workspace.resolve("bad.png"), "invalid");
        return workspace;
    }
    String call(ImageProcessingTool tool, String input) {
        return tool.processImages("session", List.of(input), "out", "png",
            32, 32, false, 100, 100, 85, "", new ToolContext(Map.of("ultimate.userId", tenant)));
    }
    Set<Path> scratchDirectories() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return new HashSet<>(paths.filter(x -> x.getFileName().toString().startsWith("ultimate-images-")).toList());
        }
    }
    long descriptors() throws Exception {
        try (var stream = Files.list(Path.of("/proc/self/fd"))) { return stream.count(); }
    }
    void await(Path file) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(file) && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(Files.exists(file), "Worker did not reach gate: " + file);
    }
    Path gatedConverter() throws Exception {
        Path executable = root.resolve("gated-convert");
        // Root is JUnit-created and never contains tool arguments. The real converter
        // receives the original argument vector unchanged after the controlled gate.
        Files.writeString(executable, "#!/bin/sh\nprintf '%s' \"$$\" > '" + root + "/pid-'$$\n"
            + "while [ ! -f '" + root + "/release' ]; do sleep 0.01; done\n"
            + "exec /usr/bin/convert \"$@\"\n");
        assertTrue(executable.toFile().setExecutable(true));
        return executable;
    }
    List<Path> gates() throws Exception {
        try (var paths = Files.list(root)) {
            return paths.filter(x -> x.getFileName().toString().startsWith("pid-")).toList();
        }
    }
    void awaitGates(int count) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (gates().size() < count && System.nanoTime() < end) Thread.sleep(10);
        assertEquals(count, gates().size());
    }
    @Test void repeatedApplicationRequestsDoNotLeakDescriptorsOrOverwriteInputs() throws Exception {
        Set<Path> scratchBefore = scratchDirectories();
        Path workspace = fixture();
        byte[] original = Files.readAllBytes(workspace.resolve("input.png"));
        var tool = new ImageProcessingTool(root, new ImageTransform("/usr/bin/convert", Duration.ofSeconds(10)));
        assertTrue(call(tool, "input.png").startsWith("Image processing completed"));
        long before = descriptors();
        for (int i = 0; i < 100; i++) {
            boolean invalid = i % 5 == 0;
            String result = call(tool, invalid ? "bad.png" : "input.png");
            assertTrue(result.startsWith(invalid ? "Image processing error" : "Image processing completed"), result);
        }
        assertEquals(before, descriptors(), "Descriptors grew after 100 mixed requests");
        assertArrayEquals(original, Files.readAllBytes(workspace.resolve("input.png")));
        try (var outputs = Files.list(workspace.resolve("out"))) {
            List<Path> files = outputs.toList();
            assertEquals(81, files.size());
            for (Path output : files) {
                BufferedImage image = ImageIO.read(output.toFile());
                assertEquals(32, image.getWidth()); assertEquals(16, image.getHeight());
            }
        }
        assertEquals(scratchBefore, scratchDirectories());
        assertEquals(0, ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count());
    }
    @Test void twoConcurrentBatchesRunAndThirdIsBusyThenPermitRecovers() throws Exception {
        Set<Path> scratchBefore = scratchDirectories();
        Path workspace = fixture();
        var tool = new ImageProcessingTool(root, new ImageTransform(gatedConverter().toString(), Duration.ofSeconds(15)));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = workers.submit(() -> call(tool, "input.png"));
            Future<String> second = workers.submit(() -> call(tool, "input.png"));
            awaitGates(2);
            assertEquals("Image processing error: busy, retry later", call(tool, "input.png"));
            Files.writeString(root.resolve("release"), "go");
            assertTrue(first.get(20, TimeUnit.SECONDS).startsWith("Image processing completed"));
            assertTrue(second.get(20, TimeUnit.SECONDS).startsWith("Image processing completed"));
            assertTrue(call(tool, "input.png").startsWith("Image processing completed"));
            try (var outputs = Files.list(workspace.resolve("out"))) { assertEquals(3, outputs.count()); }
        } finally {
            Files.writeString(root.resolve("release"), "go");
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals(scratchBefore, scratchDirectories());
        assertEquals(0, ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count());
    }
    @Test void interruptionStopsWorkerAndReturnsSemaphorePermit() throws Exception {
        Set<Path> scratchBefore = scratchDirectories();
        fixture();
        var tool = new ImageProcessingTool(root, new ImageTransform(gatedConverter().toString(), Duration.ofSeconds(15)));
        AtomicReference<String> response = new AtomicReference<>();
        Thread worker = new Thread(() -> response.set(call(tool, "input.png")));
        try {
            worker.start(); awaitGates(1);
            long pid = Long.parseLong(Files.readString(gates().getFirst()));
            worker.interrupt(); worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(scratchBefore, scratchDirectories());
            assertEquals("Image processing error: interrupted", response.get());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            Files.writeString(root.resolve("release"), "go");
            assertTrue(call(tool, "input.png").startsWith("Image processing completed"));
        } finally {
            Files.writeString(root.resolve("release"), "go");
            worker.interrupt(); worker.join(5000);
        }
    }
}
