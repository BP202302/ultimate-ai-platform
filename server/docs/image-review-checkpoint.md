# Image-processing review checkpoint (2026-10-28)

Validation response to PR #60 review comment:
https://github.com/iyeanur6-cyber/ultimate-ai-platform/pull/60#issuecomment-6475537470

## Controlled artifact corpus

`ImageReferenceCorpusTest` builds analytic reference images locally without network
or model calls. Ten tests inspect the resulting files rather than just subprocess
success: PNG color channels and no-upscaling; fit/crop dimensions; brightness and
saturation; PNG/WebP alpha; lossy JPEG round trip; visible watermark and unchanged
input; EXIF orientation 6 followed by stripping; embedded sRGB ICC removal;
truncated PNG rejection; and native over-dimension policy rejection.

Run in `server`: `mvn -B -ntp -Dtest=ImageReferenceCorpusTest test`.
Requires Java 21, ImageMagick 6 convert/identify and DejaVu Sans. 10/10 pass
on native Linux ARM64, using the existing cached validation container.
Full `mvn -B -ntp -o clean verify` also passes: 376 tests, zero failures,
errors or skips, and the packaged application builds. The first container run
had an unwritable Java home and disabled DNS, causing logging-context and
existing browser-test failures. Repeating with a writable `/tmp` Java home and
normal DNS passes without changing test code or exclusions. Both logs are kept.

This corpus supplements the existing workspace/isolation/publication tests. It
is not a claim that every requested production property is established. ICC
removal is tested, not arbitrary cross-profile perceptual color correctness.
One EXIF orientation and one malformed PNG are tested, not every decoder case.
The large-dimension case rejects a narrow image; it is not a host-OOM experiment.
Input WebP is deliberately unsupported by the current API; output WebP is tested.

## Remaining review work

- Local resource/performance evidence now covers seven bounded scenarios: see
  [ARM64 measurements](image-benchmark-arm64.md) and the raw CSV. The reference
  hardware, production workload, long-run and forced-crash gaps remain open.
- Bounded application requests and a forced-JVM-exit probe are now recorded below.
  Abrupt-exit cleanup is not provided by JVM finally blocks; external containment
  and production lifecycle/recovery validation remain required.
- Preserve the distinction between direct deterministic tool tests and real
  provider/orchestrator/model integration. No live inference was executed.
- The requested x86/NVIDIA environment is unavailable in the authorized VM
  (ARM64, about 12 GB RAM, no GPU), so reference-hardware/VRAM claims cannot be made.
  Other model calls remain outside this operator's authorization.
- This supplement belongs to the existing PR; it is not a separate feature or
  a claim to reference-hardware validation. Upstream CI remains required.

These are local validation artifacts, not upstream acceptance. The USD250 bounty
remains conditional on maintainer/management/financial approval. No payment or
technical approval is implied by the passing corpus.

## Application requests, interruption and forced JVM exit

ImageLifecycleTest adds three independent application-level checks:

- 100 sequential requests through ImageProcessingTool: 80 valid conversions and
  20 invalid-image errors, after one warm-up. All 81 resulting PNGs decode with
  expected 32x16 dimensions; input bytes stay unchanged. File-descriptor count,
  private scratch-directory set and live descendant count return to baseline.
- Two batches held at a controlled executable gate run concurrently, while a
  third receives the documented busy response. After release, both invoke the
  real converter, publish distinct files, and a subsequent call succeeds.
- Interrupting a gated request returns the interruption error, stops the native
  worker, removes scratch, and returns the permit for a subsequent real conversion.

The gate is a local shell fixture around the native executable; it is not an AI
provider, authenticated network request, or inference test. These 100 requests
complete in a bounded unit-test run, not an hours-long soak or production load.
The complete mvn clean verify passes 376 tests with no failures/errors/skips.

ImageForcedExitProbe is a separate, explicitly destructive-to-its-child Linux
experiment for a disposable container. A child JVM invokes the application tool
with a synthetic waiting native helper, and the supervisor forcibly terminates
the child during transformation. Observed on the same ARM64 container:

    event,jvm_exit,scratch_directories_remaining,native_worker_alive,published_outputs
    forced_jvm_exit,137,1,true,0

The probe supervisor then kills the recorded helper and removes its private
fixture tree (probe_fixture_cleanup=true); the disposable container exits.
This is evidence of an unresolved abrupt-exit boundary, not successful automatic
recovery. It does not simulate a power loss or kill during output publication.
No untrusted third-party system was tested.

Reproduce only in a disposable Linux container with Java21 and the project test
runtime classpath: compile src/benchmark/java/ai/ultimate/tools/image/ImageForcedExitProbe.java
against target/classes plus resolved dependencies, then run
ai.ultimate.tools.image.ImageForcedExitProbe. The Maven runtime dependency
classpath is also recorded in Surefire XML. Do not run this probe against a live
application process.

Deployment must contain native descendants and temporary storage externally,
for example with a separately managed worker/container lifecycle and private
ephemeral temporary filesystem. That deployment and abrupt-publication recovery
are not implemented or validated here. Reference x86/NVIDIA hardware, real
provider inference and an extended soak remain outstanding. No acceptance or
payment is inferred from this supplement.
