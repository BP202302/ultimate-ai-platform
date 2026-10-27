package ai.ultimate.tools.builtin;

import ai.ultimate.tools.UltimateTool;
import ai.ultimate.tools.image.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

@Component
public class ImageProcessingTool implements UltimateTool {
    private final java.util.concurrent.Semaphore permits=new java.util.concurrent.Semaphore(2);
    private final Path managedRoot;
    private final ImageTransform transform;
    public ImageProcessingTool(){this(Path.of(System.getProperty("user.home"),"ultimate-managed-workspaces"),new ImageTransform("/usr/bin/convert",Duration.ofSeconds(30)));}
    public ImageProcessingTool(Path root,ImageTransform transform){this.managedRoot=root;this.transform=transform;}
    @Tool(description="Convert PNG/JPEG images to PNG/JPEG/WebP, resize/crop, adjust brightness/saturation, strip EXIF and watermark. All paths are relative to the authenticated user's managed workspace. Up to ten files, 20MiB each, 80MiB total. Returns generated output names or an error; never overwrites files. Example workspace=session-1, inputPaths=[photo.png], outputDirectory=out, format=webp, width=800,height=600,crop=false,brightness=100,saturation=100,quality=80,watermark=Private.")
    public String processImages(
        @ToolParam(description="Relative existing workspace under authenticated tenant directory") String workspace,
        @ToolParam(description="1-10 relative PNG/JPEG file paths") List<String> inputPaths,
        @ToolParam(description="Relative existing output directory") String outputDirectory,
        @ToolParam(description="png, jpeg or webp") String format,
        @ToolParam(description="Output maximum width 1-4096") int width,
        @ToolParam(description="Output maximum height 1-4096") int height,
        @ToolParam(description="Center crop to exact dimensions instead of fitting") boolean crop,
        @ToolParam(description="Brightness percent 0-200, normal 100") int brightness,
        @ToolParam(description="Saturation percent 0-200, normal 100") int saturation,
        @ToolParam(description="Compression quality 1-100") int quality,
        @ToolParam(description="Optional plain ASCII watermark, at most 80 characters") String watermark,
        ToolContext context) {
        if(!permits.tryAcquire())return "Image processing error: busy, retry later";
        Path scratch=null;
        try {
            Object value=context==null?null:context.getContext().get("ultimate.userId");
            if(!(value instanceof UUID tenant)) return "Image processing error: authenticated user context required";
            var options=new ImageTransform.Options(format,width,height,crop,brightness,saturation,quality,watermark);
            var checked=new ImageWorkspace(managedRoot,tenant,workspace);
            checked.inputs(inputPaths); checked.outputDirectory(outputDirectory);
            scratch=Files.createTempDirectory("ultimate-images-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            List<String> outputs=new ArrayList<>();long bytes=0;
            try(var files=new ImageFiles(managedRoot,tenant,workspace);var publication=files.output(outputDirectory)) {
                List<Path> results=new ArrayList<>();
                for(int i=0;i<inputPaths.size();i++) {
                    Path job=Files.createDirectory(scratch.resolve("image-"+i));Path input=job.resolve("input");
                    files.stage(inputPaths.get(i),input);bytes+=Files.size(input);
                    if(bytes>80L*1024*1024)throw new java.io.IOException("Batch exceeds size limit");
                    results.add(transform.transform(input,job,options));
                }
                // No output is published until every image has transformed successfully.
                String batch=UUID.randomUUID().toString();
                for(int i=0;i<results.size();i++) {
                    String name="image-"+batch+"-"+i+"."+format;
                    publication.publish(results.get(i),name);outputs.add(outputDirectory+"/"+name);
                }
                ImageWorkspace.cleanup(scratch);scratch=null;
                publication.commit();
            }
            return "Image processing completed: "+String.join(", ",outputs);
        } catch(InterruptedException e){Thread.currentThread().interrupt();return "Image processing error: interrupted";}
        catch(Exception e){return "Image processing error: "+e.getClass().getSimpleName();}
        finally {try{ImageWorkspace.cleanup(scratch);}catch(java.io.IOException e){return "Image processing error: scratch cleanup failed";}finally{permits.release();}}
    }
}
