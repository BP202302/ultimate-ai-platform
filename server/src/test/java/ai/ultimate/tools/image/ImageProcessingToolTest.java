package ai.ultimate.tools.image;
import ai.ultimate.tools.builtin.ImageProcessingTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
class ImageProcessingToolTest {
 @TempDir Path root;UUID tenant;Path workspace;ImageProcessingTool tool;
 @BeforeEach void setup() throws Exception {
  tenant=UUID.randomUUID();workspace=Files.createDirectories(root.resolve(tenant.toString()).resolve("session"));
  Files.createDirectory(workspace.resolve("out"));
  BufferedImage image=new BufferedImage(80,80,BufferedImage.TYPE_INT_RGB);
  ImageIO.write(image,"png",workspace.resolve("input.png").toFile());
  tool=new ImageProcessingTool(root,new ImageTransform("/usr/bin/convert",Duration.ofSeconds(15)));
 }
 String invoke(List<String> inputs,String watermark,ToolContext context) {
  return tool.processImages("session",inputs,"out","png",32,32,true,110,80,85,watermark,context);
 }
 ToolContext context(){return new ToolContext(Map.of("ultimate.userId",tenant));}
 long outputs() throws Exception {try(var s=Files.list(workspace.resolve("out"))){return s.count();}}
 @Test void missingContextFailsClosed() throws Exception {
  assertTrue(invoke(List.of("input.png"),"",null).contains("authenticated"));assertEquals(0,outputs());
 }
 @Test void rejectsStringUserIdNotSuppliedByTrustedApplication() {
  assertTrue(invoke(List.of("input.png"),"",new ToolContext(Map.of("ultimate.userId",tenant.toString()))).contains("authenticated"));
 }
 @Test void authenticBatchPublishesDistinctImagesAndPreservesInputs() throws Exception {
  Files.copy(workspace.resolve("input.png"),workspace.resolve("second.png"));
  assertTrue(invoke(List.of("input.png","second.png"),"",context()).startsWith("Image processing completed"));
  assertEquals(2,outputs());assertTrue(Files.exists(workspace.resolve("input.png")));
 }
 @Test void invalidSecondImageRollsBackEntireBatch() throws Exception {
  Files.writeString(workspace.resolve("bad.png"),"not a PNG");
  assertTrue(invoke(List.of("input.png","bad.png"),"",context()).startsWith("Image processing error"));assertEquals(0,outputs());
 }
 @Test void differentTenantCannotReadWorkspace() throws Exception {
  assertTrue(invoke(List.of("input.png"),"",new ToolContext(Map.of("ultimate.userId",UUID.randomUUID()))).startsWith("Image processing error"));assertEquals(0,outputs());
 }
 @Test void realWatermarkAndStrip() throws Exception {
  Process set=new ProcessBuilder("/usr/bin/convert",workspace.resolve("input.png").toString(),"-set","comment","private-metadata",workspace.resolve("comment.png").toString()).start();assertEquals(0,set.waitFor());
  String result=invoke(List.of("comment.png"),"Private",context());assertTrue(result.startsWith("Image processing completed"),result);
  try(var paths=Files.list(workspace.resolve("out"))){Path out=paths.findFirst().orElseThrow();assertFalse(new String(Files.readAllBytes(out),java.nio.charset.StandardCharsets.ISO_8859_1).contains("private-metadata"));BufferedImage image=ImageIO.read(out.toFile());assertEquals(32,image.getWidth());
    boolean visible=false;for(int x=0;x<image.getWidth();x++)for(int y=0;y<image.getHeight();y++)if((image.getRGB(x,y)&0xffffff)!=0)visible=true;
    assertTrue(visible,"Watermark must change pixels on the black fixture");}
 }
 @Test void callbackSchemaOmitsTrustedContext() {
  var callbacks=MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks();
  assertEquals(1,callbacks.length);assertFalse(callbacks[0].getToolDefinition().inputSchema().contains("ultimate.userId"));
 }
 @Test void descriptorOutputCannotBeRedirectedBySymlinkSwap() throws Exception {
  Path external=Files.createDirectory(root.resolve("external"));
  try(var files=new ImageFiles(root,tenant,"session");var out=files.output("out")) {
   Files.move(workspace.resolve("out"),workspace.resolve("old-out"));Files.createSymbolicLink(workspace.resolve("out"),external);
   out.publish(workspace.resolve("input.png"),"safe.png");out.commit();
  }
  assertTrue(Files.exists(workspace.resolve("old-out/safe.png")));assertFalse(Files.exists(external.resolve("safe.png")));
 }
 @Test void descriptorRejectsInputSymlinkSwap() throws Exception {
  Path external=Files.writeString(root.resolve("private.png"),"private");
  try(var files=new ImageFiles(root,tenant,"session")) {
   Files.delete(workspace.resolve("input.png"));Files.createSymbolicLink(workspace.resolve("input.png"),external);
   assertThrows(java.io.IOException.class,()->files.stage("input.png",workspace.resolve("staged")));
  }
 }
 @Test void publicationRollsBackOnFailureWithoutOverwriting() throws Exception {
  Files.writeString(workspace.resolve("out/existing.png"),"keep");
  try(var files=new ImageFiles(root,tenant,"session");var out=files.output("out")) {
   out.publish(workspace.resolve("input.png"),"new.png");assertThrows(java.io.IOException.class,()->out.publish(workspace.resolve("input.png"),"existing.png"));
  }
  assertFalse(Files.exists(workspace.resolve("out/new.png")));assertEquals("keep",Files.readString(workspace.resolve("out/existing.png")));
 }
 long workspaceDescriptors() throws Exception {
  long count=0;try(var list=Files.list(Path.of("/proc/self/fd"))){for(Path entry:list.toList())try{if(Files.readSymbolicLink(entry).toString().startsWith(root.toString()))count++;}catch(java.io.IOException ignored){}}
  return count;
 }
 @Test void failedConstructorClosesDescriptors() throws Exception {
  long before=workspaceDescriptors();
  for(int i=0;i<5;i++)assertThrows(java.io.IOException.class,()->new ImageFiles(root,tenant,"missing"));
  assertEquals(before,workspaceDescriptors());
 }
}
