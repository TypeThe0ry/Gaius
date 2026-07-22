package dev.gaius.browser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.teavm.jso.JSBody;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;
import org.teavm.runtime.fs.VirtualFileSystem;
import org.teavm.runtime.fs.VirtualFileSystemProvider;

/** Browser persistence mirror for TeaVM's in-memory filesystem.
 *
 * <p>The Java side must stay synchronous because it sits behind {@code java.io}
 * and {@code java.nio}. The page preloads IndexedDB into
 * {@code globalThis.__gaiusPersistentFiles} before invoking Minecraft, then these
 * methods synchronously read/write that mirror and enqueue durable IndexedDB
 * writes from JavaScript.</p>
 */
public final class BrowserFilePersistence {
    private static final String PREFIX = "gaius.fs.v1:";
    private static final String OPTIONS_PATH = "/gaius/options.txt";
    private static final String DEFAULT_BROWSER_OPTIONS = String.join("\n",
            "autoJump:false",
            "operatorItemsTab:true",
            "renderDistance:6",
            "simulationDistance:4",
            "entityDistanceScaling:0.5",
            "maxFps:120",
            "graphicsPreset:\"fast\"",
            "renderClouds:\"false\"",
            "cloudRange:32",
            "ao:false",
            "cutoutLeaves:false",
            "vignette:false",
            "improvedTransparency:false",
            "weatherRadius:0",
            "chunkSectionFadeInTime:0.0",
            "prioritizeChunkUpdates:0",
            "mipmapLevels:0",
            "maxAnisotropyBit:1",
            "textureFiltering:0",
            "biomeBlendRadius:0",
            "particles:2",
            "enableVsync:false",
            "entityShadows:false",
            "bobView:false",
            "menuBackgroundBlurriness:0",
            "panoramaSpeed:0.0",
            "screenEffectScale:0.0",
            "fovEffectScale:0.0",
            "darknessEffectScale:0.0",
            "pauseOnLostFocus:false",
            "darkMojangStudiosBackground:false",
            "hideSplashTexts:true",
            "showAutosaveIndicator:false",
            "skipMultiplayerWarning:true") + "\n";
    private static boolean mounted;

    private BrowserFilePersistence() {
    }

    public static void mount() {
        if (mounted) {
            return;
        }
        mounted = true;
        String[] paths = storedPaths(PREFIX);
        int restored = 0;
        for (String path : paths) {
            try {
                if (restore(path)) {
                    restored++;
                }
            } catch (Throwable exception) {
                report("storage-restore-crashed", normalize(path) + ": " + describe(exception));
            }
        }
        seedDefaultOptions();
        report("storage-mounted", restored + " files");
        report("storage-backend", backendName());
    }

    public static boolean persist(String path, byte[] bytes) {
        if (path == null || bytes == null || !shouldPersist(path)) {
            return false;
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        boolean stored = setItem(PREFIX + normalize(path), encoded);
        if (!stored) {
            report("storage-quota-or-error", path + " bytes=" + bytes.length);
        }
        return stored;
    }

    public static boolean delete(String path) {
        if (path == null || !shouldPersist(path)) {
            return false;
        }
        removeItem(PREFIX + normalize(path));
        return true;
    }

    public static void syncDelete(String path) {
        if (delete(path)) {
            report("storage-delete", normalize(path));
        }
    }

    public static void syncMove(String source, String target) {
        boolean stored = syncFile(target);
        if (stored) {
            delete(source);
            report("storage-move", normalize(source) + " -> " + normalize(target));
        } else {
            report("storage-move-failed", normalize(source) + " -> " + normalize(target));
        }
    }

    public static boolean syncFile(String path) {
        if (path == null || !shouldPersist(path)) {
            return false;
        }
        String normalized = normalize(path);
        try {
            VirtualFileSystem fileSystem = VirtualFileSystemProvider.getInstance();
            VirtualFile file = fileSystem.getFile(normalized);
            if (file == null || !file.isFile()) {
                delete(normalized);
                return false;
            }
            VirtualFileAccessor accessor = file.createAccessor(true, false, false);
            if (accessor == null) {
                report("storage-sync-failed", normalized + ": open failed");
                return false;
            }
            try {
                int size = accessor.size();
                byte[] bytes = new byte[size];
                accessor.seek(0);
                int offset = 0;
                while (offset < size) {
                    int read = accessor.read(bytes, offset, size - offset);
                    if (read <= 0) {
                        break;
                    }
                    offset += read;
                }
                if (offset < size) {
                    bytes = Arrays.copyOf(bytes, offset);
                }
                boolean stored = persist(normalized, bytes);
                if (stored) {
                    report("storage-sync-file", normalized + " bytes=" + bytes.length);
                }
                return stored;
            } finally {
                accessor.close();
            }
        } catch (IOException exception) {
            report("storage-sync-failed", normalized + ": " + exception.getMessage());
            return false;
        }
    }

    private static boolean restore(String path) {
        String normalized = normalize(path);
        String encoded = getItem(PREFIX + normalized);
        if (encoded == null || encoded.isEmpty()) {
            return false;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IllegalArgumentException exception) {
            report("storage-decode-failed", normalized);
            return false;
        }
        try {
            writeVirtualFile(normalized, bytes);
            return true;
        } catch (IOException exception) {
            report("storage-restore-failed", normalized + ": " + exception.getMessage());
            return false;
        }
    }

    private static boolean shouldPersist(String path) {
        String normalized = normalize(path);
        return normalized.contains("/saves/")
                || normalized.endsWith("/options.txt")
                || normalized.endsWith("/servers.dat")
                || normalized.endsWith("/servers.dat_old")
                || normalized.endsWith("/optionsof.txt")
                || normalized.endsWith("/optionsshaders.txt");
    }

    private static void seedDefaultOptions() {
        try {
            VirtualFileSystem fileSystem = VirtualFileSystemProvider.getInstance();
            VirtualFile existing = fileSystem.getFile(OPTIONS_PATH);
            if (existing != null && existing.isFile()) {
                return;
            }
            writeDefaultOptions("browser defaults");
        } catch (Throwable exception) {
            report("storage-default-options-failed", describe(exception));
        }
    }

    private static void writeDefaultOptions(String detail) throws IOException {
        byte[] bytes = DEFAULT_BROWSER_OPTIONS.getBytes(StandardCharsets.UTF_8);
        writeVirtualFile(OPTIONS_PATH, bytes);
        persist(OPTIONS_PATH, bytes);
        report("storage-default-options", detail);
    }

    private static String describe(Throwable exception) {
        if (exception == null) {
            return "unknown";
        }
        String message = exception.getMessage();
        String name = exception.getClass().getName();
        return message == null || message.isEmpty() ? name : name + ": " + message;
    }

    private static void writeVirtualFile(String path, byte[] bytes) throws IOException {
        VirtualFileSystem fileSystem = VirtualFileSystemProvider.getInstance();
        ensureParentDirectories(fileSystem, path);
        String parentPath = parent(path);
        String name = name(path);
        VirtualFile parent = fileSystem.getFile(parentPath);
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("Could not open parent directory " + parentPath);
        }
        VirtualFile file = fileSystem.getFile(path);
        if ((file == null || !file.isFile()) && !parent.createFile(name)) {
            file = fileSystem.getFile(path);
            if (file == null || !file.isFile()) {
                throw new IOException("Could not create " + path);
            }
        }
        file = fileSystem.getFile(path);
        if (file == null || !file.isFile()) {
            throw new IOException("Could not open " + path);
        }
        VirtualFileAccessor accessor = file.createAccessor(false, true, false);
        if (accessor == null) {
            throw new IOException("Could not open " + path);
        }
        accessor.resize(0);
        accessor.write(bytes, 0, bytes.length);
        accessor.flush();
        accessor.close();
    }

    private static void ensureParentDirectories(VirtualFileSystem fileSystem, String path) {
        String parentPath = parent(path);
        if (parentPath.isEmpty() || parentPath.equals("/")) {
            return;
        }
        ensureParentDirectories(fileSystem, parentPath);
        VirtualFile parent = fileSystem.getFile(parent(parentPath));
        VirtualFile directory = fileSystem.getFile(parentPath);
        if ((directory == null || !directory.isDirectory()) && parent != null && parent.isDirectory()) {
            parent.createDirectory(name(parentPath));
        }
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String normalized = path.replace('\\', '/');
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String parent(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash <= 0 ? "/" : normalized.substring(0, slash);
    }

    private static String name(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    @JSBody(params = {"prefix"}, script = """
            try {
              var files=globalThis.__gaiusPersistentFiles;
              if (files) return Object.keys(files);
              var result=[];
              var storage=globalThis.localStorage;
              if (!storage) return result;
              for (var i=0;i<storage.length;i++) {
                var key=storage.key(i);
                if (key && key.indexOf(prefix)===0) result.push(key.substring(prefix.length));
              }
              return result;
            } catch (e) {
              return [];
            }
            """)
    private static native String[] storedPaths(String prefix);

    @JSBody(params = {"key"}, script = """
            try {
              var prefix='gaius.fs.v1:';
              var files=globalThis.__gaiusPersistentFiles;
              if (files && key && key.indexOf(prefix)===0) {
                var path=key.substring(prefix.length);
                return Object.prototype.hasOwnProperty.call(files,path) ? files[path] : null;
              }
              return globalThis.localStorage ? globalThis.localStorage.getItem(key) : null;
            } catch (e) {
              return null;
            }
            """)
    private static native String getItem(String key);

    @JSBody(params = {"key", "value"}, script = """
            try {
              var prefix='gaius.fs.v1:';
              if (key && key.indexOf(prefix)===0 && globalThis.__gaiusFsPut) {
                return !!globalThis.__gaiusFsPut(key.substring(prefix.length), value);
              }
              if (!globalThis.localStorage) return false;
              globalThis.localStorage.setItem(key,value);
              return true;
            } catch (e) {
              return false;
            }
            """)
    private static native boolean setItem(String key, String value);

    @JSBody(params = {"key"}, script = """
            try {
              var prefix='gaius.fs.v1:';
              if (key && key.indexOf(prefix)===0 && globalThis.__gaiusFsDelete) {
                globalThis.__gaiusFsDelete(key.substring(prefix.length));
                return;
              }
              if (globalThis.localStorage) globalThis.localStorage.removeItem(key);
            } catch (e) {}
            """)
    private static native void removeItem(String key);

    @JSBody(script = """
            if (globalThis.__gaiusFsBackend) return String(globalThis.__gaiusFsBackend);
            if (globalThis.__gaiusPersistentFiles) return 'memory';
            try {
              return globalThis.localStorage ? 'localStorage' : 'none';
            } catch (e) {
              return 'none';
            }
            """)
    private static native String backendName();

    @JSBody(params = {"event", "detail"}, script = """
            try {
              var counters=globalThis.__gaiusMinecraftCounters || (globalThis.__gaiusMinecraftCounters={});
              var key='storage:'+event+':'+detail;
              counters[key]=(counters[key]||0)+1;
              var events=globalThis.__gaiusMinecraftEvents || (globalThis.__gaiusMinecraftEvents=[]);
              events.push({event:'storage:'+event,detail:detail,count:counters[key],at:Date.now()});
              if (events.length>500) events.splice(0,events.length-500);
            } catch (e) {}
            """)
    private static native void report(String event, String detail);
}
