# Managed image processing

`ImageProcessingTool` is discovered as an `UltimateTool` Spring component. It uses ImageMagick 6 `convert` at `/usr/bin/convert`. Install ImageMagick with PNG/JPEG/WebP support and DejaVu Sans fonts (Ubuntu: `apt-get install imagemagick fonts-dejavu-core`). Java21 and a POSIX filesystem providing SecureDirectoryStream are required. Missing infrastructure returns an error; there is no insecure filesystem fallback.

Provision directories as the service account:

```
~/ultimate-managed-workspaces/<authenticated-user-UUID>/session-1/
  photo.png
  out/
```

The UUID is copied from OrchestratorRequest into Reactor context and the Spring AI tool context by the Ollama/Gemini providers. It is not a model argument. Calls without a trusted UUID fail closed, including MCP/direct callers that do not supply application-owned context. CLI callers must authenticate through the existing application; do not accept a tenant UUID supplied in a prompt. New directories/files should not be writable by other OS users.

Example model-visible arguments:

```json
{"workspace":"session-1","inputPaths":["photo.png"],"outputDirectory":"out","format":"webp","width":800,"height":600,"crop":false,"brightness":100,"saturation":100,"quality":85,"watermark":"Private"}
```

Inputs are PNG/JPEG; outputs PNG/JPEG/WebP. Fit mode preserves aspect ratio without upscaling; crop mode creates exact centered dimensions and can upscale. Color parameters are percentages, 100 unchanged. Metadata is stripped after orientation correction and watermarking. Watermarks are plain ASCII letters/digits/space/dot/underscore/dash, at most80 characters. They are visible labeling, not tamperproof provenance or DRM.

Limits:10 files/batch,20MiB/input,80MiB/batch,20MiB/output,4096 output dimensions,32 path components,256 directory handles,2 concurrent batches/component and30seconds/process. ImageMagick has additional64MiB memory/128MiB map/disk,1thread and8image-cache-slot limits. Only the first input frame is processed. Raster coder prefixes and policies disable other coders, delegates, filters and indirect `@` reads. Original inputs are never passed to a shell or modified. Diagnostics are drained with bounded capture.

All inputs transform in private temporary directories before final publication. Descriptor-relative NOFOLLOW opens and CREATE_NEW publication reject path/symlink substitution and existing output names. Results use unique batch UUID names. If publication or cleanup fails before commit, created outputs roll back. Scratch is removed on ordinary success/error/timeout/interruption; final files remain only in the requested output directory. This finally-based cleanup cannot run after SIGKILL, JVM failure or host termination. A forced-JVM-exit probe left one private scratch directory and a native worker alive; the probe killed the worker and deleted its own fixtures afterward. Use an externally managed process/container lifecycle with group termination and a private ephemeral temporary filesystem to contain that failure. This PR does not implement abandoned-scratch recovery or transactional publication recovery after abrupt process death. The probe interrupted transformation before publication; it does not establish atomicity if the JVM dies during publication. The application does not delete inputs. Output filenames in the response refer to the selected directory; a directory renamed concurrently can move its files but cannot redirect writes outside its opened descriptor.

This is an application filesystem/process boundary, not a kernel sandbox against a malicious host OS administrator or another process running as the service UID. Deploy the service with OS/container isolation and quotas appropriate to untrusted image decoders. Native libraries must receive normal security updates. No paid API or inference is needed for image processing.

Validation: run `mvn -B -ntp test` and `mvn -B -ntp compile` in `server`. Real ImageMagick tests require the packages above. The tests exercise actual conversion/crop/watermark/strip, tenant rejection, descriptor races, rollback, overwrite refusal, limits and subprocess deadlines. Existing integration-test exclusions in the upstream POM are unchanged.
