package dev.gaius.browser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;
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
    private static final int CURRENT_DATA_VERSION = 4671;
    private static final String BROWSER_OPTION_DEFAULTS = String.join("\n",
            "autoJump:false",
            "operatorItemsTab:true",
            "renderDistance:6",
            "simulationDistance:4",
            "entityDistanceScaling:0.5",
            // Vanilla represents the display setting "Unlimited" as 260. Existing
            // browser profiles keep their chosen value; this only affects new ones.
            "maxFps:260",
            "graphicsPreset:\"fast\"",
            "renderClouds:\"false\"",
            "cloudRange:32",
            "ao:false",
            "cutoutLeaves:false",
            "vignette:false",
            "improvedTransparency:false",
            "weatherRadius:3",
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
            "skipMultiplayerWarning:true",
            "onboardAccessibility:false") + "\n";
    private static final String LEGACY_BROWSER_OPTION_DEFAULTS = BROWSER_OPTION_DEFAULTS
            .replace("weatherRadius:3\n", "weatherRadius:0\n")
            .replace("onboardAccessibility:false\n", "");
    private static final String DEFAULT_BROWSER_OPTIONS =
            "version:" + CURRENT_DATA_VERSION + "\n" + BROWSER_OPTION_DEFAULTS;
    private static final Map<String, Integer> OPEN_MATERIALIZED_CHUNK_FILES = new HashMap<>();
    private static final Set<String> INTERNAL_VIRTUAL_FILE_PATHS = new HashSet<>();
    private static final Set<String> LAZY_CHUNK_FILE_PLACEHOLDERS = new HashSet<>();
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
            if (!shouldRestoreAtStartup(path)) {
                continue;
            }
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
        if (path == null || bytes == null) {
            return false;
        }
        // FileChannel/FileOutputStream already wrote non-durable paths to TeaVM's
        // in-memory filesystem. Skipping the browser mirror is success, not an I/O
        // failure; only paths selected by shouldPersist require a durable write.
        if (!shouldPersist(path)) {
            return true;
        }
        String normalized = normalize(path);
        if (isBinaryChunkStoragePath(normalized) && setBytes(normalized, bytes)) {
            return true;
        }
        if (isDownloadedPackFile(normalized) && setBytes(normalized, bytes)) {
            markDownloadedPackPersisted(normalized, bytes.length);
            return true;
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        boolean stored = setItem(PREFIX + normalized, encoded);
        if (!stored) {
            report("storage-quota-or-error", path + " bytes=" + bytes.length);
        }
        return stored;
    }

    /** Returns true only when the active Worker can durably append region range records. */
    public static boolean supportsRangePersistence(String path) {
        if (path == null) {
            return false;
        }
        String normalized = normalize(path);
        return isChunkStoragePath(normalized) && canPatchBytes(normalized);
    }

    /** Persists one atomic logical-size update and its bounded set of changed byte ranges. */
    public static boolean persistRanges(
            String path, int logicalSize, int[] offsets, int[] lengths, byte[] payload) {
        if (path == null || logicalSize < 0 || offsets == null || lengths == null
                || payload == null || offsets.length != lengths.length) {
            return false;
        }
        if (!shouldPersist(path)) {
            return true;
        }
        String normalized = normalize(path);
        if (!isChunkStoragePath(normalized) || !canPatchBytes(normalized)) {
            return false;
        }
        boolean stored = patchBytes(normalized, logicalSize, offsets, lengths, payload);
        if (!stored) {
            report("storage-quota-or-error", path + " patchBytes=" + payload.length);
        }
        return stored;
    }

    /** Makes durable chunk storage visible to metadata checks without reading its payload. */
    public static boolean restoreOnDemand(String path) {
        if (path == null) {
            return false;
        }
        String normalized = normalize(path);
        if (!isOnDemandChunkStoragePath(normalized)
                || !INTERNAL_VIRTUAL_FILE_PATHS.add(normalized)) {
            return false;
        }
        try {
            if (!mounted) {
                mount();
            }
            if (!hasStoredPath(normalized)) {
                return false;
            }
            VirtualFile existing = VirtualFileSystemProvider.getInstance().getFile(normalized);
            if (existing != null && existing.isFile()) {
                return true;
            }
            writeVirtualFile(normalized, new byte[0]);
            LAZY_CHUNK_FILE_PLACEHOLDERS.add(normalized);
            reportRegion("storage-region-placeholder", normalized);
            return true;
        } catch (Throwable exception) {
            reportRegion("storage-region-placeholder-failed", normalized + ": " + describe(exception));
            return false;
        } finally {
            INTERNAL_VIRTUAL_FILE_PATHS.remove(normalized);
        }
    }

    /** Restores bytes before an accessor is created; metadata placeholders are never opened. */
    public static void materializeForOpen(String path) throws IOException {
        if (path == null) {
            return;
        }
        String normalized = normalize(path);
        if (!isOnDemandChunkStoragePath(normalized)) {
            return;
        }
        if (!INTERNAL_VIRTUAL_FILE_PATHS.add(normalized)) {
            throw new IOException("Recursive browser chunk materialization for " + normalized);
        }
        try {
            if (!mounted) {
                mount();
            }
            if (!hasStoredPath(normalized)) {
                return;
            }
            VirtualFile existing = VirtualFileSystemProvider.getInstance().getFile(normalized);
            if (existing != null && existing.isFile()
                    && !LAZY_CHUNK_FILE_PLACEHOLDERS.contains(normalized)) {
                return;
            }
            if (!restore(normalized)) {
                throw new IOException("Could not restore browser chunk file " + normalized);
            }
            LAZY_CHUNK_FILE_PLACEHOLDERS.remove(normalized);
            reportRegion("storage-region-hydrated", normalized);
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable exception) {
            IOException failure = new IOException("Could not restore browser chunk file " + normalized);
            failure.initCause(exception);
            throw failure;
        } finally {
            INTERNAL_VIRTUAL_FILE_PATHS.remove(normalized);
        }
    }

    /** Pins a materialized .mca or .mcc file while a synchronous accessor can use it. */
    public static boolean retainMaterializedChunkFile(String path) {
        String normalized = normalize(path);
        if (!isOnDemandChunkStoragePath(normalized)) {
            return false;
        }
        OPEN_MATERIALIZED_CHUNK_FILES.put(
                normalized, OPEN_MATERIALIZED_CHUNK_FILES.getOrDefault(normalized, 0) + 1);
        return true;
    }

    /**
     * Releases the TeaVM copy after the last channel closes. Durable storage is
     * deliberately left untouched so the next getFile call can hydrate it again.
     */
    public static void releaseMaterializedChunkFile(String path, boolean durable) {
        String normalized = normalize(path);
        if (!isOnDemandChunkStoragePath(normalized)) {
            return;
        }
        int references = OPEN_MATERIALIZED_CHUNK_FILES.getOrDefault(normalized, 0);
        if (references > 1) {
            OPEN_MATERIALIZED_CHUNK_FILES.put(normalized, references - 1);
            return;
        }
        OPEN_MATERIALIZED_CHUNK_FILES.remove(normalized);
        if (references == 0 || !durable || !hasStoredPath(normalized)) {
            return;
        }
        reclaimMaterializedChunkFileIfUnreferenced(normalized);
    }

    private static void reclaimMaterializedChunkFileIfUnreferenced(String normalized) {
        if (!isOnDemandChunkStoragePath(normalized)
                || OPEN_MATERIALIZED_CHUNK_FILES.getOrDefault(normalized, 0) != 0
                || !hasStoredPath(normalized)
                || !INTERNAL_VIRTUAL_FILE_PATHS.add(normalized)) {
            return;
        }
        try {
            VirtualFile file = VirtualFileSystemProvider.getInstance().getFile(normalized);
            LAZY_CHUNK_FILE_PLACEHOLDERS.remove(normalized);
            if (file != null && file.isFile() && file.delete()) {
                reportRegion("storage-region-reclaimed", normalized);
            }
        } catch (Throwable exception) {
            reportRegion("storage-region-reclaim-failed", normalized + ": " + describe(exception));
        } finally {
            INTERNAL_VIRTUAL_FILE_PATHS.remove(normalized);
        }
    }

    public static boolean delete(String path) {
        if (path == null || !shouldPersist(path)) {
            return false;
        }
        String normalized = normalize(path);
        LAZY_CHUNK_FILE_PLACEHOLDERS.remove(normalized);
        removeItem(PREFIX + normalized);
        return true;
    }

    public static void syncDelete(String path) {
        if (delete(path)) {
            report("storage-delete", normalize(path));
        }
    }

    public static void syncMove(String source, String target) throws IOException {
        boolean stored = syncFile(target);
        if (stored) {
            delete(source);
            report("storage-move", normalize(source) + " -> " + normalize(target));
            reclaimMaterializedChunkFileIfUnreferenced(normalize(target));
        } else {
            report("storage-move-failed", normalize(source) + " -> " + normalize(target));
            throw new IOException("Could not persist moved browser file " + normalize(target));
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
        byte[] bytes;
        int storedLength = storedByteLength(normalized);
        if (storedLength >= 0) {
            bytes = new byte[storedLength];
            if (copyStoredBytes(normalized, bytes) != storedLength) {
                report("storage-decode-failed", normalized);
                return false;
            }
        } else {
            String encoded = getItem(PREFIX + normalized);
            if (encoded == null || encoded.isEmpty()) {
                return false;
            }
            try {
                bytes = Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.ISO_8859_1));
            } catch (IllegalArgumentException exception) {
                report("storage-decode-failed", normalized);
                return false;
            }
        }
        try {
            writeVirtualFile(normalized, bytes);
            if (normalized.endsWith("/level.dat")) {
                ensureBrowserSessionLock(parent(normalized));
            }
            return true;
        } catch (IOException exception) {
            report("storage-restore-failed", normalized + ": " + exception.getMessage());
            return false;
        }
    }

    private static void ensureBrowserSessionLock(String worldDirectory) throws IOException {
        String lockPath = normalize(worldDirectory) + "/session.lock";
        VirtualFile lock = VirtualFileSystemProvider.getInstance().getFile(lockPath);
        if (lock == null || !lock.isFile()) {
            // DirectoryLock.isLocked opens this file without CREATE. TeaVM's
            // missing-file exception is broader than the JDK type vanilla catches.
            writeVirtualFile(lockPath, new byte[0]);
        }
    }

    private static boolean shouldPersist(String path) {
        String normalized = normalize(path);
        return normalized.contains("/saves/")
                || normalized.startsWith("/gaius/downloads/")
                || normalized.endsWith("/options.txt")
                || normalized.endsWith("/servers.dat")
                || normalized.endsWith("/servers.dat_old")
                || normalized.endsWith("/optionsof.txt")
                || normalized.endsWith("/optionsshaders.txt");
    }

    private static boolean isDownloadedPackFile(String normalized) {
        return normalized.startsWith("/gaius/downloads/")
                && !normalized.endsWith("/log.json");
    }

    /**
     * The title client only needs world summaries, while a server Worker only
     * needs the world it was launched for. Restoring every region file here
     * turns a large saved world into a long synchronous browser task.
     */
    private static boolean shouldRestoreAtStartup(String path) {
        String normalized = normalize(path);
        String activeWorld = activeServerWorldId();
        if (activeWorld != null && !activeWorld.isEmpty()) {
            return normalized.startsWith("/gaius/saves/" + activeWorld + "/")
                    && !isOnDemandChunkStoragePath(normalized);
        }
        if (!normalized.startsWith("/gaius/saves/")) {
            return true;
        }
        int worldEnd = normalized.indexOf('/', "/gaius/saves/".length());
        if (worldEnd < 0 || worldEnd + 1 >= normalized.length()) {
            return false;
        }
        String relative = normalized.substring(worldEnd + 1);
        return relative.equals("level.dat")
                || relative.equals("level.dat_old")
                || relative.equals("icon.png");
    }

    private static boolean isOnDemandChunkStoragePath(String normalized) {
        if (!isChunkStoragePath(normalized)) {
            return false;
        }
        String activeWorld = activeServerWorldId();
        return activeWorld != null
                && !activeWorld.isEmpty()
                && normalized.startsWith("/gaius/saves/" + activeWorld + "/");
    }

    private static boolean isBinaryChunkStoragePath(String normalized) {
        if (isChunkStoragePath(normalized)) {
            return true;
        }
        String activeWorld = activeServerWorldId();
        if (activeWorld == null || activeWorld.isEmpty()
                || !normalized.startsWith("/gaius/saves/" + activeWorld + "/")) {
            return false;
        }
        String fileName = name(normalized);
        return fileName.startsWith("tmp") && fileName.endsWith(".tmp");
    }

    private static boolean isChunkStoragePath(String normalized) {
        return normalized.endsWith(".mca") || normalized.endsWith(".mcc");
    }

    private static void seedDefaultOptions() {
        try {
            VirtualFileSystem fileSystem = VirtualFileSystemProvider.getInstance();
            VirtualFile existing = fileSystem.getFile(OPTIONS_PATH);
            if (existing != null && existing.isFile()) {
                migrateLegacyDefaultOptions(existing);
                return;
            }
            writeDefaultOptions("browser defaults");
        } catch (Throwable exception) {
            report("storage-default-options-failed", describe(exception));
        }
    }

    private static void migrateLegacyDefaultOptions(VirtualFile existing) throws IOException {
        byte[] bytes = readVirtualFile(existing);
        String options = new String(bytes, StandardCharsets.UTF_8);
        String legacyVersionedOptions = "version:" + CURRENT_DATA_VERSION + "\n"
                + LEGACY_BROWSER_OPTION_DEFAULTS;
        if (!options.equals(LEGACY_BROWSER_OPTION_DEFAULTS)
                && !options.equals(legacyVersionedOptions)) {
            return;
        }
        writeDefaultOptions("browser defaults data version");
    }

    private static byte[] readVirtualFile(VirtualFile file) throws IOException {
        VirtualFileAccessor accessor = file.createAccessor(true, false, false);
        if (accessor == null) {
            throw new IOException("Could not open " + OPTIONS_PATH);
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
            return offset == size ? bytes : Arrays.copyOf(bytes, offset);
        } finally {
            accessor.close();
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

    @JSBody(params = {"path", "bytes"}, script = """
            try {
              if (!globalThis.__gaiusFsPutBytes) return false;
              return !!globalThis.__gaiusFsPutBytes(String(path || '/'), bytes);
            } catch (e) {
              return false;
            }
            """)
    private static native boolean setBytes(String path, @JSByRef byte[] bytes);

    @JSBody(params = {"path"}, script = """
            try {
              return typeof globalThis.__gaiusFsCanPatchBytes === 'function'
                && !!globalThis.__gaiusFsCanPatchBytes(String(path || '/'));
            } catch (e) {
              return false;
            }
            """)
    private static native boolean canPatchBytes(String path);

    @JSBody(params = {"path", "logicalSize", "offsets", "lengths", "payload"}, script = """
            try {
              if (typeof globalThis.__gaiusFsPatchBytes !== 'function') return false;
              return !!globalThis.__gaiusFsPatchBytes(
                String(path || '/'), logicalSize, offsets, lengths, payload);
            } catch (e) {
              return false;
            }
            """)
    private static native boolean patchBytes(
            String path,
            int logicalSize,
            @JSByRef int[] offsets,
            @JSByRef int[] lengths,
            @JSByRef byte[] payload);

    @JSBody(params = {"path", "byteLength"}, script = """
            try {
              var state=globalThis.__gaiusMultiplayerRecovery ||
                (globalThis.__gaiusMultiplayerRecovery={});
              state.packCachedAt=Date.now();
              state.packPath=String(path || '');
              state.packBytes=byteLength|0;
              state.packWrites=(state.packWrites|0)+1;
              state.packAddress=String(state.activeAddress || '');
              state.packAttempt=state.activeAttempt|0;
            } catch (e) {}
            """)
    private static native void markDownloadedPackPersisted(String path, int byteLength);

    @JSBody(params = {"path"}, script = """
            try {
              path=String(path || '/');
              var files=globalThis.__gaiusPersistentFiles;
              var value=files && Object.prototype.hasOwnProperty.call(files,path)
                ? files[path]
                : (globalThis.localStorage
                  ? globalThis.localStorage.getItem('gaius.fs.v1:' + path) : null);
              if (typeof value === 'string') {
                if (!value.length) return 0;
                var padding=value.endsWith('==') ? 2 : (value.endsWith('=') ? 1 : 0);
                return Math.floor(value.length * 3 / 4) - padding;
              }
              if (value instanceof ArrayBuffer) return value.byteLength;
              if (ArrayBuffer.isView(value)) return value.byteLength;
              return -1;
            } catch (e) {
              return -1;
            }
            """)
    private static native int storedByteLength(String path);

    @JSBody(params = {"path", "output"}, script = """
            try {
              path=String(path || '/');
              var files=globalThis.__gaiusPersistentFiles;
              var value=files && Object.prototype.hasOwnProperty.call(files,path)
                ? files[path]
                : (globalThis.localStorage
                  ? globalThis.localStorage.getItem('gaius.fs.v1:' + path) : null);
              var bytes;
              if (typeof value === 'string') {
                if (typeof Uint8Array.fromBase64 === 'function') {
                  bytes=Uint8Array.fromBase64(value);
                } else {
                  var binary=globalThis.atob(value);
                  bytes=new Uint8Array(binary.length);
                  for (var i=0;i<binary.length;i++) bytes[i]=binary.charCodeAt(i);
                }
              } else if (value instanceof ArrayBuffer) {
                bytes=new Uint8Array(value);
              } else if (ArrayBuffer.isView(value)) {
                bytes=new Uint8Array(value.buffer,value.byteOffset,value.byteLength);
              } else {
                return -1;
              }
              if (!output || output.length !== bytes.length) return -1;
              if (typeof output.set === 'function') output.set(bytes);
              else for (var j=0;j<bytes.length;j++) output[j]=bytes[j];
              return bytes.length;
            } catch (e) {
              return -1;
            }
            """)
    private static native int copyStoredBytes(String path, @JSByRef byte[] output);

    @JSBody(params = {"path"}, script = """
            try {
              path=String(path || '/');
              var files=globalThis.__gaiusPersistentFiles;
              if (files && Object.prototype.hasOwnProperty.call(files,path)) return true;
              return !!(globalThis.localStorage &&
                globalThis.localStorage.getItem('gaius.fs.v1:' + path) !== null);
            } catch (e) {
              return false;
            }
            """)
    private static native boolean hasStoredPath(String path);

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

    @JSBody(script = """
            try {
              return globalThis.__gaiusServerWorldId == null
                ? null
                : String(globalThis.__gaiusServerWorldId);
            } catch (e) {
              return null;
            }
            """)
    private static native String activeServerWorldId();

    @JSBody(params = {"event", "detail"}, script = """
            try {
              var counters=globalThis.__gaiusMinecraftCounters || (globalThis.__gaiusMinecraftCounters={});
              var key='storage:'+event;
              counters[key]=(counters[key]||0)+1;
              var events=globalThis.__gaiusMinecraftEvents || (globalThis.__gaiusMinecraftEvents=[]);
              events.push({event:'storage:'+event,detail:detail,count:counters[key],at:Date.now()});
              if (events.length>500) events.splice(0,events.length-500);
            } catch (e) {}
            """)
    private static native void reportRegion(String event, String detail);

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
