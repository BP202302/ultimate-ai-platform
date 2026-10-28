package ai.ultimate.tools.image;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** CPU-only local transform benchmark; no model, network or tenant workspace. */
public class ImageProcessingBenchmark {
 static long value(String name) {
  try {return Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/"+name)).trim());}catch(Exception e){return -1;}
 }
 static long cpu() {
  try {for(String line:Files.readAllLines(Path.of("/sys/fs/cgroup/cpu.stat")))if(line.startsWith("usage_usec "))return Long.parseLong(line.substring(11));}catch(Exception ignored){}return -1;
 }
 static long disk(Path dir) {
  try(var files=Files.walk(dir)){return files.filter(x->Files.isRegularFile(x,LinkOption.NOFOLLOW_LINKS)).mapToLong(x->{try{return Files.size(x);}catch(Exception e){return 0;}}).sum();}catch(Exception e){return -1;}
 }
 static long fds() {try(var files=Files.list(Path.of("/proc/self/fd"))){return files.count();}catch(Exception e){return -1;}}
 static void run(String name,int side,int count,boolean concurrent,String format) throws Exception {
  Path root=Files.createTempDirectory("image-reference-bench-");List<Path> inputs=new ArrayList<>();long bytes=0;
  try {
   BufferedImage image=new BufferedImage(side,side/2,BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<image.getHeight();y++)for(int x=0;x<side;x++)image.setRGB(x,y,((x*255/side)<<16)|((y*255/image.getHeight())<<8)|64);
   for(int i=0;i<count;i++){Path dir=Files.createDirectory(root.resolve("item-"+i));Path input=dir.resolve("input.png");ImageIO.write(image,"png",input.toFile());inputs.add(input);bytes+=Files.size(input);}
   AtomicBoolean stop=new AtomicBoolean();AtomicLong peakMemory=new AtomicLong(value("memory.current")),peakDisk=new AtomicLong(disk(root));
   Thread sampler=new Thread(()->{while(!stop.get()){peakMemory.accumulateAndGet(value("memory.current"),Math::max);peakDisk.accumulateAndGet(disk(root),Math::max);try{Thread.sleep(10);}catch(InterruptedException e){break;}}});sampler.setDaemon(true);
   long beforeFd=fds(),beforeCpu=cpu(),start=System.nanoTime();sampler.start();List<Path> outputs=new ArrayList<>();
   try {
    ExecutorService pool=Executors.newFixedThreadPool(concurrent?2:1);
    try {
     List<Future<Path>> futures=new ArrayList<>();
     for(Path input:inputs)futures.add(pool.submit(()->new ImageTransform("/usr/bin/convert",Duration.ofSeconds(30)).transform(input,input.getParent(),new ImageTransform.Options(format,1024,1024,false,100,100,85,""))));
     for(Future<Path> result:futures)outputs.add(result.get(40,TimeUnit.SECONDS));
    }finally{pool.shutdownNow();if(!pool.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("worker did not stop");}
   }finally{stop.set(true);sampler.join(2000);}
   long nanos=System.nanoTime()-start,afterCpu=cpu(),afterFd=fds();long outputBytes=0;
   for(Path output:outputs){outputBytes+=Files.size(output);byte[] prefix;try(var in=Files.newInputStream(output)){prefix=in.readNBytes(12);}if(prefix.length<12)throw new IllegalStateException("empty output");
    if(format.equals("webp")){if(!new String(prefix,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")||!new String(prefix,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))throw new IllegalStateException("wrong output format");}
    else{BufferedImage decoded=ImageIO.read(output.toFile());if(decoded==null||decoded.getWidth()!=Math.min(side,1024)||decoded.getHeight()!=Math.min(side,1024)/2)throw new IllegalStateException("incorrect dimensions");}
   }
   long children=ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count();if(children!=0)throw new IllegalStateException("live descendants remain");
   System.out.printf(Locale.ROOT,"%s,%d,%d,%d,%s,%.3f,%.3f,%d,%d,%d,%d,%d,%d%n",name,side,side/2,count,format,nanos/1e6,beforeCpu<0||afterCpu<0?-1:(afterCpu-beforeCpu)*100000.0/nanos,peakMemory.get(),peakDisk.get(),bytes,outputBytes,afterFd-beforeFd,children);
  }finally{try(var files=Files.walk(root)){for(Path x:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(x);}if(Files.exists(root))throw new IllegalStateException("scratch cleanup failed");}
 }
 public static void main(String[] args)throws Exception {
  System.out.println("scenario,width,height,files,format,wall_ms,cpu_core_percent,sampled_cgroup_memory_bytes,sampled_scratch_bytes,input_bytes,output_bytes,jvm_fd_delta,live_descendants");
  run("normal-png",512,1,false,"png");run("normal-jpeg",512,1,false,"jpeg");run("high-resolution",2048,1,false,"png");run("large-webp",4096,1,false,"webp");run("sequential-batch",1024,3,false,"png");run("repeated-sequential",512,10,false,"png");run("concurrent-two",2048,2,true,"png");
 }
}
