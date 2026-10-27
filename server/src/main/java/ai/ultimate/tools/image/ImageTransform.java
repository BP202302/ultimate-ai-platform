package ai.ultimate.tools.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded CLI transform. Input must be a private staged copy, never a user CLI expression. */
public final class ImageTransform {
    public record Options(String format, int width, int height, boolean crop,
                          int brightness, int saturation, int quality, String watermark) {
        public Options {
            if (!Set.of("png", "jpeg", "webp").contains(format))
                throw new IllegalArgumentException("Output must be png, jpeg or webp");
            if (width < 1 || height < 1 || width > 4096 || height > 4096)
                throw new IllegalArgumentException("Dimensions must be 1-4096");
            if (brightness < 0 || brightness > 200 || saturation < 0 || saturation > 200)
                throw new IllegalArgumentException("Color percentages must be 0-200");
            if (quality < 1 || quality > 100) throw new IllegalArgumentException("Quality must be 1-100");
            if (watermark == null || !watermark.matches("[A-Za-z0-9 ._-]{0,80}"))
                throw new IllegalArgumentException("Watermark must be at most 80 plain ASCII characters");
        }
    }

    private final String executable;
    private final Duration timeout;
    public ImageTransform(String executable, Duration timeout) {
        if (executable == null || executable.isBlank() || timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Executable and positive timeout required");
        this.executable = executable; this.timeout = timeout;
    }

    /** Only PNG/JPEG signatures are accepted; coder prefixes prevent content-driven delegates. */
    private static String inputFormat(Path input) throws IOException {
        byte[] prefix;
        try (var stream = Files.newInputStream(input)) { prefix = stream.readNBytes(8); }
        if (java.util.Arrays.equals(prefix, new byte[]{(byte)137,80,78,71,13,10,26,10})) return "png";
        if (prefix.length >= 3 && prefix[0] == (byte)255 && prefix[1] == (byte)216 && prefix[2] == (byte)255) return "jpeg";
        throw new IOException("Only PNG and JPEG input images are supported");
    }

    public Path transform(Path stagedInput, Path privateScratch, Options options) throws IOException, InterruptedException {
        if (options == null) throw new IllegalArgumentException("Options required");
        Path scratch = privateScratch.toRealPath();
        Path input = stagedInput.toRealPath();
        if (!input.getParent().equals(scratch) || Files.isSymbolicLink(stagedInput) || !Files.isRegularFile(input))
            throw new IOException("Input must be staged directly inside private scratch");
        String format = inputFormat(input);
        Path output = scratch.resolve("result." + options.format());
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output already exists");
        Path config = Files.createDirectory(scratch.resolve("config"));
        // Disable all delegates, filters and indirect path reads. Permit only explicit raster codecs.
        Files.writeString(config.resolve("policy.xml"), """
            <policymap>
              <policy domain="delegate" rights="none" pattern="*"/>
              <policy domain="filter" rights="none" pattern="*"/>
              <policy domain="path" rights="none" pattern="@*"/>
              <policy domain="coder" rights="none" pattern="*"/>
              <policy domain="coder" rights="read|write" pattern="{PNG,JPEG,WEBP}"/>
              <policy domain="resource" name="memory" value="64MiB"/>
              <policy domain="resource" name="map" value="128MiB"/>
              <policy domain="resource" name="disk" value="128MiB"/>
              <policy domain="resource" name="width" value="16384"/>
              <policy domain="resource" name="height" value="16384"/>
              <policy domain="resource" name="list-length" value="8"/>
              <policy domain="resource" name="thread" value="1"/>
              <policy domain="resource" name="time" value="30"/>
            </policymap>
            """);
        List<String> args = new ArrayList<>(List.of(executable, "-limit", "thread", "1",
            format + ":" + input + "[0]", "-auto-orient", "-resize",
            options.width() + "x" + options.height() + (options.crop() ? "^" : ">")));
        if (options.crop()) args.addAll(List.of("-gravity", "center", "-extent", options.width()+"x"+options.height()));
        args.addAll(List.of("-modulate", options.brightness()+", "+options.saturation()+",100"));
        if (!options.watermark().isEmpty()) args.addAll(List.of("-gravity", "southeast", "-fill", "white",
            "-undercolor", "#00000080", "-font", "DejaVu-Sans", "-pointsize", "14", "-annotate", "+4+4", options.watermark()));
        args.addAll(List.of("-strip", "-quality", Integer.toString(options.quality()), options.format()+":"+output));
        
        ProcessBuilder builder = new ProcessBuilder(args).directory(scratch.toFile())
            .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(java.util.Map.of("PATH", "/usr/bin:/bin", "HOME", scratch.toString(),
            "TMPDIR", scratch.toString(), "MAGICK_TEMPORARY_PATH", scratch.toString(),
            "MAGICK_CONFIGURE_PATH", config.toString(), "LC_ALL", "C"));
        Process process = builder.start();
        StringBuilder diagnostic = new StringBuilder();
        Thread drainer = Thread.startVirtualThread(() -> {
            try (var in = process.getInputStream()) {
                byte[] buffer = new byte[1024]; int n;
                while ((n = in.read(buffer)) != -1) {
                    int keep = Math.min(n, 4096 - diagnostic.length());
                    if (keep > 0) diagnostic.append(new String(buffer, 0, keep, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) { }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Image processing timed out");
            drainer.join(2000);
            if (process.exitValue() != 0) throw new IOException("ImageMagick failed (exit " + process.exitValue()+"): " + diagnostic);
            if (Files.isSymbolicLink(output) || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                || Files.size(output) == 0 || Files.size(output) > 20L*1024*1024)
                throw new IOException("Image output is missing or exceeds limits");
            return output.toRealPath();
        } finally {
            process.descendants().forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }
}
