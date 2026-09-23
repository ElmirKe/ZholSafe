package kz.zholsafe.ai;

import android.content.Context;
import android.content.res.AssetManager;

import kz.zholsafe.ai.infer.ModelFiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Model files from APK assets, with {@code app files/models/...} as an override location (so a
 * legitimately exported model can be pushed with adb without rebuilding). ONNX Runtime needs a
 * real path, so an asset model is materialised once into the app's no-backup files dir.
 * Nothing is downloaded; no network.
 */
public final class AssetModelFiles implements ModelFiles {

    private final AssetManager assets;
    private final File overrideRoot;
    private final File cacheRoot;

    public AssetModelFiles(Context context) {
        Context app = context.getApplicationContext();
        this.assets = app.getAssets();
        this.overrideRoot = new File(app.getFilesDir(), "");
        this.cacheRoot = new File(app.getNoBackupFilesDir(), "model-cache");
    }

    @Override
    public boolean exists(String relativePath) {
        if (new File(overrideRoot, relativePath).isFile()) {
            return true;
        }
        try (InputStream in = assets.open(relativePath)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        File override = new File(overrideRoot, relativePath);
        if (override.isFile()) {
            return new java.io.FileInputStream(override);
        }
        return assets.open(relativePath);
    }

    @Override
    public String absolutePath(String relativePath) throws ModelNotAvailableException {
        File override = new File(overrideRoot, relativePath);
        if (override.isFile()) {
            return override.getAbsolutePath();
        }
        File target = new File(cacheRoot, relativePath);
        try (InputStream in = assets.open(relativePath)) {
            long assetLen = assetLength(relativePath);
            if (target.isFile() && assetLen > 0 && target.length() == assetLen) {
                return target.getAbsolutePath();
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            File tmp = new File(target.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("rename failed for " + target);
            }
            return target.getAbsolutePath();
        } catch (IOException e) {
            throw new ModelNotAvailableException(relativePath, "not in assets or " + override + ": " + e.getMessage(), e);
        }
    }

    private long assetLength(String relativePath) {
        try (android.content.res.AssetFileDescriptor fd = assets.openFd(relativePath)) {
            return fd.getLength();
        } catch (IOException e) {
            return -1; // compressed asset; fall back to always-copy
        }
    }
}
