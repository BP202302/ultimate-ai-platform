package ai.ultimate.tools.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Analytic reference images: inspect decoded artifacts, not just process exit. */
class ImageReferenceCorpusTest {
    @TempDir Path root;
    int sequence;
    Path fixture(BufferedImage image, String format) throws Exception {
        Path dir=Files.createDirectory(root.resolve("case-"+(sequence++)));
        Path input=dir.resolve("input."+format);
        assertTrue(ImageIO.write(image,format,input.toFile()));
        return input;
    }
    BufferedImage solid(int width,int height,int rgb) {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) image.setRGB(x,y,rgb);
        return image;
    }
    Path transform(Path input,String format,int width,int height,boolean crop,int brightness,int saturation,String watermark) throws Exception {
        return new ImageTransform("/usr/bin/convert",Duration.ofSeconds(15)).transform(input,input.getParent(),
            new ImageTransform.Options(format,width,height,crop,brightness,saturation,100,watermark));
    }
    void near(int expected,int actual,int tolerance) { assertTrue(Math.abs(expected-actual)<=tolerance,"expected "+expected+", actual "+actual); }
    String identify(Path path,String pattern) throws Exception {
        Process process=new ProcessBuilder("/usr/bin/identify","-format",pattern,path.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        String text=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),text);return text;
    }
    @Test void pngPreservesKnownChannelsAndDoesNotUpscale() throws Exception {
        Path output=transform(fixture(solid(60,40,0xC04020),"png"),"png",100,100,false,100,100,"");
        BufferedImage image=ImageIO.read(output.toFile());assertEquals(60,image.getWidth());assertEquals(40,image.getHeight());
        int c=image.getRGB(20,20);near(192,(c>>16)&255,1);near(64,(c>>8)&255,1);near(32,c&255,1);
    }
    @Test void fitsAspectRatioAndCropsToRequestedDimensions() throws Exception {
        Path fitted=transform(fixture(solid(120,80,0x306090),"png"),"png",30,30,false,100,100,"");
        assertEquals("PNG 30 20",identify(fitted,"%m %w %h"));
        Path cropped=transform(fixture(solid(120,80,0x306090),"png"),"png",30,30,true,100,100,"");
        assertEquals("PNG 30 30",identify(cropped,"%m %w %h"));
    }
    @Test void brightnessAndSaturationChangeDecodedPixels() throws Exception {
        Path darker=transform(fixture(solid(40,20,0x502814),"png"),"png",40,20,false,50,100,"");
        int c=ImageIO.read(darker.toFile()).getRGB(10,10);near(40,(c>>16)&255,2);near(20,(c>>8)&255,2);near(10,c&255,2);
        Path gray=transform(fixture(solid(40,20,0xC04020),"png"),"png",40,20,false,100,0,"");
        c=ImageIO.read(gray.toFile()).getRGB(10,10);near((c>>16)&255,(c>>8)&255,1);near((c>>8)&255,c&255,1);
    }
    @Test void alphaIsPreservedOnPngAndWebp() throws Exception {
        for(String format:new String[]{"png","webp"}) {
            BufferedImage image=new BufferedImage(40,20,BufferedImage.TYPE_INT_ARGB);
            for(int y=5;y<15;y++) for(int x=10;x<30;x++) image.setRGB(x,y,0xFFFF0000);
            Path out=transform(fixture(image,"png"),format,40,20,false,100,100,"");
            assertEquals(format.equals("png")?"PNG 40 20 false":"WEBP 40 20 false",identify(out,"%m %w %h %[opaque]"));
            if(format.equals("png")) { BufferedImage result=ImageIO.read(out.toFile());assertEquals(0,result.getRGB(0,0)>>>24);assertEquals(255,result.getRGB(20,10)>>>24); }
        }
    }
    @Test void jpegRoundTripRetainsDimensionsAndFlatColorWithinLossyTolerance() throws Exception {
        Path jpg=transform(fixture(solid(60,40,0xC04020),"png"),"jpeg",60,40,false,100,100,"");
        assertEquals("JPEG 60 40",identify(jpg,"%m %w %h"));
        BufferedImage decoded=ImageIO.read(jpg.toFile());Path png=transform(fixture(decoded,"jpeg"),"png",60,40,false,100,100,"");
        int c=ImageIO.read(png.toFile()).getRGB(20,20);near(192,(c>>16)&255,8);near(64,(c>>8)&255,8);near(32,c&255,8);
    }
    @Test void watermarkChangesArtifactWithoutChangingDimensionsOrInput() throws Exception {
        Path input=fixture(solid(256,128,0x404040),"png");byte[] original=Files.readAllBytes(input);
        BufferedImage output=ImageIO.read(transform(input,"png",256,128,false,100,100,"Reference").toFile());
        int changed=0;for(int y=0;y<128;y++) for(int x=0;x<256;x++) if((output.getRGB(x,y)&0xFFFFFF)!=0x404040)changed++;
        assertTrue(changed>40);assertArrayEquals(original,Files.readAllBytes(input));assertEquals(256,output.getWidth());assertEquals(128,output.getHeight());
    }
    void jpegSegment(Path jpeg,int marker,byte[] payload) throws Exception {
        byte[] original=Files.readAllBytes(jpeg);ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(original,0,2);out.write(255);out.write(marker);int length=payload.length+2;out.write(length>>8);out.write(length&255);out.write(payload);out.write(original,2,original.length-2);Files.write(jpeg,out.toByteArray());
    }
    @Test void correctsExifOrientationBeforeStrippingMetadata() throws Exception {
        BufferedImage source=solid(80,40,0x0000FF);for(int y=0;y<40;y++)for(int x=0;x<40;x++)source.setRGB(x,y,0xFF0000);
        Path input=fixture(source,"jpeg");ByteBuffer exif=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        exif.put(new byte[]{69,120,105,102,0,0,73,73}).putShort((short)42).putInt(8).putShort((short)1).putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)6).putShort((short)0).putInt(0);
        jpegSegment(input,225,exif.array());assertEquals("RightTop",identify(input,"%[orientation]"));
        Path out=transform(input,"jpeg",100,100,false,100,100,"");BufferedImage result=ImageIO.read(out.toFile());assertEquals(40,result.getWidth());assertEquals(80,result.getHeight());
        assertTrue(((result.getRGB(10,10)>>16)&255)>200);assertTrue((result.getRGB(10,70)&255)>200);
        assertFalse(new String(Files.readAllBytes(out),StandardCharsets.ISO_8859_1).contains("Exif"));
    }
    @Test void removesEmbeddedIccProfileFromFinalJpeg() throws Exception {
        Path input=fixture(solid(40,20,0xC04020),"jpeg");ByteArrayOutputStream payload=new ByteArrayOutputStream();
        payload.write("ICC_PROFILE".getBytes(StandardCharsets.US_ASCII));payload.write(0);payload.write(1);payload.write(1);payload.write(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());jpegSegment(input,226,payload.toByteArray());
        assertTrue(identify(input,"%[profiles]").contains("icc"));Path out=transform(input,"jpeg",40,20,false,100,100,"");
        assertFalse(new String(Files.readAllBytes(out),StandardCharsets.ISO_8859_1).contains("ICC_PROFILE"));assertEquals("JPEG 40 20",identify(out,"%m %w %h"));
    }
    @Test void malformedPngFailsWithoutSuccessfulArtifact() throws Exception {
        Path input=Files.write(Files.createDirectory(root.resolve("malformed")).resolve("input.png"),new byte[]{(byte)137,80,78,71,13,10,26,10,0,0});
        assertThrows(java.io.IOException.class,()->transform(input,"png",10,10,false,100,100,""));
        Path out=input.getParent().resolve("result.png");assertTrue(!Files.exists(out)||Files.size(out)==0);
    }
    @Test void rejectsDimensionsBeyondNativePolicy() throws Exception {
        Path input=fixture(solid(16385,1,0x123456),"png");assertThrows(java.io.IOException.class,()->transform(input,"png",40,20,false,100,100,""));
    }
}
