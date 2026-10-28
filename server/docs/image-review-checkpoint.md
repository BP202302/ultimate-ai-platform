# Image-processing review checkpoint (2026-10-28)

Response in preparation to PR #60 review comment:
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
Full `mvn -B -ntp -o clean verify` also passes: 373 tests, zero failures,
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
- Audit descendant cleanup and forced-crash lifecycle in isolation; do not treat
  successful ordinary cleanup as proof of crash/host-termination cleanup.
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
