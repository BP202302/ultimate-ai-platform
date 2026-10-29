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
  and production lifecycle/recovery validation remain required. A bounded container
  lifecycle check is now recorded below; it is not production recovery.
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
ephemeral temporary filesystem. The bounded container experiment below validates
one containment configuration;
production deployment and abrupt-publication recovery are still not implemented
or validated here. Reference x86/NVIDIA hardware, real provider inference and an
extended soak remain outstanding. No acceptance or payment is inferred from
this supplement.


## Disposable container lifecycle observation (2026-10-29 UTC)

The same probe now accepts a container argument. This mode refuses to run unless
its JVM is PID 1, creates its own managed-workspace fixture, and invokes
ImageProcessingTool in that JVM with the same synthetic waiting native helper.
There is no nested application JVM in this variant. The host waits for the
native.pid marker and confirms a live /bin/sleep 30 worker before killing only
the disposable probe container.

Measured on the same ARM64 validation image, Java 21 and Docker:

| Check | First start | Restart of the same container |
|---|---:|---:|
| JVM is PID 1; /tmp is a private tmpfs | yes | yes |
| Preexisting probe roots at startup | 0 | 0 |
| Private application scratch directories before kill | 1 | 1 |
| Native worker alive before kill | yes | yes |
| Container exit after host SIGKILL | 137 | 137 |
| Recorded native worker alive after kill | no | no |
| Previous probe root exists after restart | n/a | no |

The host records the worker's host PID and /proc start-time identity before
termination, then verifies it is no longer live; both recorded /proc entries
were absent after their respective kills. The old fixture root is absent on
restart and a new root is created. The host removes its probe container after
both cycles. The original nested-JVM probe still reproduces
forced_jvm_exit,137,1,true,0 and then cleans its own fixtures.

A negative control omits tmpfs and uses only the disposable container's writable
layer for /tmp. After its first SIGKILL, restarting that same container reports
container_preexisting_probe_roots=1 and exits 1 with
Prior probe storage survived restart. That control container and its layer are
then removed. Container termination contains descendants; persistent writable
storage alone does not make abandoned temporaries disappear.

The initial host harness checked the fixture's tmp subdirectory, but Java's
default temporary-directory helper had initialized earlier and the application
scratch was actually under /tmp. The harness was corrected to inspect /tmp;
the private-tmpfs and live-worker assertions were retained. The failed harness
log and final observations are preserved with the operator validation evidence.

Reproduction requires a disposable Linux Docker environment and a validation
image containing Java 21, ImageMagick 6 and DejaVu Sans. Compile this benchmark
against the already built project classes and resolved Maven runtime classpath,
as for the nested-JVM probe above. For the measured variant, the host used:

    docker run -d --name <unique-probe-name> --network none --read-only \
      --cap-drop ALL --security-opt no-new-privileges \
      --cpus 2 --memory 512m --pids-limit 128 --user 1001:1001 \
      --tmpfs /tmp:rw,exec,nosuid,nodev,size=64m,mode=1777 \
      -v <checkout>:/work:ro -v <maven-cache>:/cache:ro \
      -w /work/server --entrypoint java <validation-image> \
      -Xmx128m -cp <resolved-classpath-with-benchmark-classes> \
      ai.ultimate.tools.image.ImageForcedExitProbe container

Adapt the UID and read-only classpath mounts to the disposable environment.
Read container_probe_root from docker logs, wait for its native.pid marker,
and record the worker's host PID/identity with docker top and /proc before
docker kill --signal KILL <unique-probe-name>. Check docker wait returns 137
and that the recorded native process is gone. Start the same container once
more: the new startup must report zero preexisting probe roots, and the old
root must be absent. Repeat the kill/readback and remove only that named probe
container. Never target a live application container or unrelated process.

This is evidence for whole-container termination plus private ephemeral /tmp
during transformation. The waiting helper is synthetic, not a real decoder
crash, provider call, inference workload, power loss, or production deployment.
No kill during output publication was tested. The existing compose files are
unchanged; they are not certified by this experiment. Persistent tenant outputs
need their own abrupt-publication/recovery design. This experiment does not
make the Java finally blocks run after SIGKILL and does not establish automatic
application recovery or satisfy the remaining hardware and inference gates.
