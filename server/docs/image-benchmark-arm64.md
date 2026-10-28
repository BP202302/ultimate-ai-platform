# Local image-transform measurements — 2026-10-28

Measured on the authorized native Linux ARM64 VM, inside the existing Java21 /
ImageMagick6 container with 2 CPU cores and a 3 GiB memory limit. No GPU or model
was used. This measures `ImageTransform`, not the full AI orchestration layer or
managed-workspace publication. Inputs are deterministic analytic PNG gradients.
Generated files are decoded (PNG/JPEG) or checked for WebP signatures; the separate
reference corpus asserts more detailed output properties. Every scratch tree is
removed after its scenario. All seven rows completed; JVM descriptor deltas and
remaining native descendants were zero. This is bounded local evidence, not a
proof of crash recovery, leak freedom at arbitrary duration, or production acceptance.

| Scenario | Input | Files | Output | Wall ms | Sampled container RAM MiB | Sampled scratch MiB |
|---|---|---:|---|---:|---:|---:|
| normal-png | 512×256 | 1 | png | 72.3 | 36.6 | 0.09 |
| normal-jpeg | 512×256 | 1 | jpeg | 24.2 | 40.8 | 0.09 |
| high-resolution | 2048×1024 | 1 | png | 212.2 | 115.5 | 0.39 |
| large-webp | 4096×2048 | 1 | webp | 694.9 | 239.4 | 64.88 |
| sequential-batch | 1024×512 | 3 | png | 199.1 | 157.5 | 0.31 |
| repeated-sequential | 512×256 | 10 | png | 297.4 | 155.6 | 0.89 |
| concurrent-two | 2048×1024 | 2 | png | 260.8 | 208.6 | 0.78 |

Raw measurements: [image-benchmark-arm64.csv](image-benchmark-arm64.csv), including
input/output bytes and cgroup CPU usage as a percentage of one core (up to 200%
under the two-core quota). Memory is total cgroup memory, including JVM and native
children, sampled every 10 ms; disk is the sampled total private scratch size.
Sampling can miss brief peaks, and allocator/JIT state carries between rows.
These are single trials in the shown order, without controlled warmup, percentiles,
confidence intervals, competing AI workload, reference x86 hardware, or VRAM data.
The repeat case is ten separate transforms in one sequential batch. Concurrency
is two native transforms with separate scratch directories; larger-scale concurrent
application requests and very long repeated runs remain unverified.

## Reproduce

From `server`, with Java21, ImageMagick6, DejaVu Sans and already compiled classes:

```sh
mkdir -p target/benchmark-classes
javac -cp target/classes -d target/benchmark-classes src/benchmark/java/ai/ultimate/tools/image/ImageProcessingBenchmark.java
java -cp target/classes:target/benchmark-classes ai.ultimate.tools.image.ImageProcessingBenchmark
```

Run with cgroup v2 CPU/memory accounting available. Values of -1 mean an accounting
file was unavailable, not zero usage. Fixture creation is outside the timed region;
processing, worker creation/termination and sampler shutdown are included. Filesystem
cleanup and decoded artifact checks follow the timed region and fail the run if
unsuccessful. No host OOM or harmful resource-exhaustion experiment was performed.
