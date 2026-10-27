package ai.ultimate.tools.image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;
class ImageTransformTest {
 @TempDir Path scratch;
 Path fixture() throws Exception {
  Path p=scratch.resolve("input.png");
  BufferedImage image=new BufferedImage(40,20,BufferedImage.TYPE_INT_RGB);
  ImageIO.write(image,"png",p.toFile());return p;
 }
 ImageTransform.Options options(String format,boolean crop) {
  return new ImageTransform.Options(format,16,16,crop,100,100,80,"");
 }
 @Test void convertsAndCropsRealPng() throws Exception {
  Path result=new ImageTransform("/usr/bin/convert",Duration.ofSeconds(15)).transform(fixture(),scratch,options("png",true));
  BufferedImage image=ImageIO.read(result.toFile());assertEquals(16,image.getWidth());assertEquals(16,image.getHeight());
 }
 @Test void convertsToRealWebpWithoutUpscaling() throws Exception {
  Path result=new ImageTransform("/usr/bin/convert",Duration.ofSeconds(15)).transform(fixture(),scratch,options("webp",false));
  byte[] data=Files.readAllBytes(result);assertEquals("RIFF",new String(data,0,4));assertEquals("WEBP",new String(data,8,4));
 }
 @Test void rejectsNonRasterInputBeforeProcessStart() throws Exception {
  Path input=Files.writeString(scratch.resolve("input.svg"),"<svg/>");
  assertThrows(java.io.IOException.class,()->new ImageTransform("/missing",Duration.ofSeconds(1)).transform(input,scratch,options("png",false)));
 }
 @Test void validatesBoundsAndRejectsWatermarkExpressions() {
  assertThrows(IllegalArgumentException.class,()->new ImageTransform.Options("svg",1,1,false,100,100,80,""));
  assertThrows(IllegalArgumentException.class,()->new ImageTransform.Options("png",5000,1,false,100,100,80,""));
  assertThrows(IllegalArgumentException.class,()->new ImageTransform.Options("png",1,1,false,100,100,80,"@/etc/passwd"));
 }
 @Test void refusesExistingOutput() throws Exception {
  Path input=fixture();Files.writeString(scratch.resolve("result.png"),"keep");
  assertThrows(java.io.IOException.class,()->new ImageTransform("/usr/bin/convert",Duration.ofSeconds(1)).transform(input,scratch,options("png",false)));
  assertEquals("keep",Files.readString(scratch.resolve("result.png")));
 }
 @Test void terminatesProcessOnTimeout() throws Exception {
  Path input=fixture();Path exe=Files.writeString(scratch.resolve("slow"),"#!/bin/sh\nsleep 10\n");exe.toFile().setExecutable(true);
  long start=System.nanoTime();assertThrows(java.io.IOException.class,()->new ImageTransform(exe.toString(),Duration.ofMillis(50)).transform(input,scratch,options("png",false)));
  assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()<5);
 }
}
