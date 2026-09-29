package io.github.kubaj12.online_store.catalogpricing.persistence;

import io.github.kubaj12.online_store.catalogpricing.application.ImageContent;
import io.github.kubaj12.online_store.catalogpricing.application.ImageReference;
import io.github.kubaj12.online_store.catalogpricing.application.ImageStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

/** Stores sanitized raster binaries under generated opaque names, outside the web root. */
public class FileSystemImageStorage implements ImageStorage {
    private final Path configuredRoot;
    private volatile Path initializedRoot;
    public FileSystemImageStorage(Path configuredRoot) {
        if (configuredRoot == null) throw new IllegalArgumentException("image storage root is required");
        this.configuredRoot=configuredRoot.toAbsolutePath().normalize();
    }
    @Override public ImageReference store(ImageContent content) {
        ImageReference ref=new ImageReference(UUID.randomUUID().toString().replace("-",""));
        Path target;
        try {
            target=path(ref,true);
            Files.write(target,content.bytes(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            return ref;
        } catch(IOException | RuntimeException failure) {
            // Files.write may create the final file before a later write fails. The
            // reference is not returned to the caller, so remove that partial object here.
            try {
                Path partial=path(ref,false);
                Files.deleteIfExists(partial);
            } catch(IOException | RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            if(failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            throw new IllegalStateException("Could not store image",failure);
        }
    }
    @Override public Optional<ImageContent> load(ImageReference reference) {
        try {
            Path path=path(reference,false);
            if(!Files.isRegularFile(path)) return Optional.empty();
            byte[] bytes=Files.readAllBytes(path); String type=sniff(bytes); return Optional.of(new ImageContent(type,bytes));
        }
        catch(java.nio.file.NoSuchFileException ex) { return Optional.empty(); }
        catch(IOException ex) { throw new IllegalStateException("Could not read image",ex); }
    }
    @Override public void delete(ImageReference reference) {
        try { Files.deleteIfExists(path(reference,false)); }
        catch(IOException ex) { throw new IllegalStateException("Could not delete image",ex); }
    }
    private Path path(ImageReference ref, boolean createRoot) throws IOException {
        Path root=initializedRoot;
        if(root==null && createRoot) {
            synchronized(this) {
                root=initializedRoot;
                if(root==null) { Files.createDirectories(configuredRoot); root=configuredRoot.toRealPath(); initializedRoot=root; }
            }
        }
        if(root==null) root=configuredRoot;
        Path candidate=root.resolve(ref.value()+".img").normalize();
        if(!candidate.getParent().equals(root)) throw new IllegalArgumentException("Invalid image reference");
        return candidate;
    }
    private static String sniff(byte[] bytes) {
        if(bytes.length>=8 && bytes[0]==(byte)0x89 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G') return "image/png";
        if(bytes.length>=3 && bytes[0]==(byte)0xff && bytes[1]==(byte)0xd8 && bytes[2]==(byte)0xff) return "image/jpeg";
        throw new IllegalStateException("Stored image has an unsupported format");
    }
}
