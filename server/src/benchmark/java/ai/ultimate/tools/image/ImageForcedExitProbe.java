package ai.ultimate.tools.image;

import ai.ultimate.tools.builtin.ImageProcessingTool;
import org.springframework.ai.chat.model.ToolContext;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Isolated Linux lifecycle observation, not a passing crash-cleanup guarantee.
 * Run only in a disposable container; the native helper intentionally waits.
 */
public final class ImageForcedExitProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("child")) {
            Path root = Path.of(args[1]);
            UUID tenant = UUID.fromString(args[2]);
            var tool = new ImageProcessingTool(root,
                new ImageTransform(root.resolve("native-wait").toString(), Duration.ofSeconds(30)));
            tool.processImages("session", List.of("input.png"), "out", "png", 32, 32,
                false, 100, 100, 85, "", new ToolContext(Map.of("ultimate.userId", tenant)));
            return;
        }
        Path root = Files.createTempDirectory("image-forced-exit-probe-");
        UUID tenant = UUID.randomUUID();
        Path workspace = Files.createDirectories(root.resolve(tenant.toString()).resolve("session"));
        Path output = Files.createDirectory(workspace.resolve("out"));
        Path childTmp = Files.createDirectory(root.resolve("tmp"));
        ImageIO.write(new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB),
            "png", workspace.resolve("input.png").toFile());
        Path marker = root.resolve("native.pid");
        Path executable = root.resolve("native-wait");
        Files.writeString(executable, "#!/bin/sh\nprintf '%s' \"$$\" > '" + marker
            + "'\nexec /bin/sleep 30\n");
        if (!executable.toFile().setExecutable(true)) throw new IllegalStateException("Cannot make helper executable");
        Process child = null;
        ProcessHandle nativeWorker = null;
        try {
            child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + childTmp, "-cp", System.getProperty("java.class.path"),
                ImageForcedExitProbe.class.getName(), "child", root.toString(), tenant.toString())
                .inheritIO().start();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while ((!Files.exists(marker) || Files.size(marker) == 0) && System.nanoTime() < deadline)
                Thread.sleep(10);
            if (!Files.exists(marker) || Files.size(marker) == 0) throw new IllegalStateException("No native-worker marker");
            nativeWorker = ProcessHandle.of(Long.parseLong(Files.readString(marker))).orElseThrow();
            child.destroyForcibly();
            if (!child.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Child JVM did not terminate");
            long scratchCount;
            try (var paths = Files.list(childTmp)) { scratchCount = paths.count(); }
            long outputs;
            try (var paths = Files.list(output)) { outputs = paths.count(); }
            System.out.println("event,jvm_exit,scratch_directories_remaining,native_worker_alive,published_outputs");
            System.out.printf("forced_jvm_exit,%d,%d,%s,%d%n", child.exitValue(),
                scratchCount, nativeWorker.isAlive(), outputs);
            if (scratchCount != 1 || outputs != 0)
                throw new IllegalStateException("Unexpected forced-exit observation");
        } finally {
            if (nativeWorker != null && nativeWorker.isAlive()) nativeWorker.destroyForcibly();
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            ImageWorkspace.cleanup(root);
            System.out.println("probe_fixture_cleanup=" + !Files.exists(root));
        }
    }
}
