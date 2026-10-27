package ai.ultimate.tools.image;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Descriptor-relative NOFOLLOW operations: a tenant cannot swap a path into a symlink
 * between validation and an open/publication. Requires SecureDirectoryStream support. */
public final class ImageFiles implements AutoCloseable {
    private final List<SecureDirectoryStream<Path>> handles = new ArrayList<>();
    private final SecureDirectoryStream<Path> workspace;
    public ImageFiles(Path root, UUID tenant, String relativeWorkspace) throws IOException {
        if (tenant == null) throw new IOException("Authenticated user required");
        var first = Files.newDirectoryStream(root.toRealPath());
        if (!(first instanceof SecureDirectoryStream<Path> secure)) {
            first.close(); throw new IOException("SecureDirectoryStream filesystem required");
        }
        handles.add(secure);
        try {
            workspace = directory(directory(secure, tenant.toString()), relativeWorkspace);
        } catch (IOException | RuntimeException e) {
            try { close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    private static Path relative(String value) throws IOException {
        if (value == null || value.isBlank() || value.length()>1024) throw new IOException("Relative path required");
        Path p=Path.of(value);
        if (p.getNameCount()>32) throw new IOException("Path exceeds depth limit");
        if (p.isAbsolute()) throw new IOException("Absolute path rejected");
        for (Path part:p) if (part.toString().equals("..")) throw new IOException("Parent traversal rejected");
        return p.normalize();
    }
    private SecureDirectoryStream<Path> directory(SecureDirectoryStream<Path> from,String name) throws IOException {
        var current=from;
        for (Path part:relative(name)) {
            if (part.toString().isEmpty() || part.toString().equals(".")) continue;
            if(handles.size()>=256)throw new IOException("Directory handle limit exceeded");
            current=current.newDirectoryStream(part,LinkOption.NOFOLLOW_LINKS); handles.add(current);
        }
        return current;
    }
    private SecureDirectoryStream<Path> parent(Path path) throws IOException {
        return path.getParent()==null?workspace:directory(workspace,path.getParent().toString());
    }
    public void stage(String name,Path destination) throws IOException {
        Path path=relative(name);var dir=parent(path);Path leaf=path.getFileName();
        var attrs=dir.getFileAttributeView(leaf,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!attrs.isRegularFile() || attrs.size()==0 || attrs.size()>20L*1024*1024) throw new IOException("Invalid image input size/type");
        try (var input=dir.newByteChannel(leaf,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
             var output=Files.newByteChannel(destination,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE))) {
            transfer(input,output,20L*1024*1024);
        }
    }
    public Output output(String relativeDirectory) throws IOException {
        return new Output(directory(workspace,relativeDirectory));
    }
    static void transfer(SeekableByteChannel from,SeekableByteChannel to,long limit) throws IOException {
        ByteBuffer buffer=ByteBuffer.allocate(16384);long total=0;
        while (from.read(buffer)!=-1) {
            total+=buffer.position();if(total>limit) throw new IOException("File exceeds size limit");
            buffer.flip();while(buffer.hasRemaining()) to.write(buffer);buffer.clear();
        }
    }
    public static final class Output implements AutoCloseable {
        private final SecureDirectoryStream<Path> directory;
        private final List<Path> published=new ArrayList<>();
        private boolean committed;
        private Output(SecureDirectoryStream<Path> directory){this.directory=directory;}
        public void publish(Path staged,String name) throws IOException {
            if(!name.matches("[a-z0-9-]+\\.(png|jpeg|webp)")) throw new IOException("Invalid generated output name");
            Path leaf=Path.of(name);
            try(var target=directory.newByteChannel(leaf,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))) {
                published.add(leaf);
                try(var input=Files.newByteChannel(staged,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){transfer(input,target,20L*1024*1024);}
            }
        }
        public void commit(){committed=true;}
        @Override public void close() throws IOException {
            if(!committed) {IOException failure=null;for(Path p:published) try{directory.deleteFile(p);}catch(IOException e){failure=e;} if(failure!=null)throw failure;}
        }
    }
    @Override public void close() throws IOException {
        IOException failure=null;
        for(int i=handles.size()-1;i>=0;i--) try{handles.get(i).close();}catch(IOException e){failure=e;}
        if(failure!=null)throw failure;
    }
}
