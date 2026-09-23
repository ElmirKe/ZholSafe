package kz.zholsafe.smoke;

import kz.zholsafe.ai.ModelNotAvailableException;
import kz.zholsafe.ai.infer.ModelFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@link ModelFiles} over a plain filesystem root (desktop counterpart of the app's AssetModelFiles). */
final class FileModelFiles implements ModelFiles {

    private final Path root;

    FileModelFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.isRegularFile(root.resolve(relativePath));
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        return Files.newInputStream(root.resolve(relativePath));
    }

    @Override
    public String absolutePath(String relativePath) throws ModelNotAvailableException {
        Path p = root.resolve(relativePath);
        if (!Files.isRegularFile(p)) {
            throw new ModelNotAvailableException(relativePath, "file not found under " + root);
        }
        return p.toString();
    }
}
