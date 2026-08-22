package net.minecraft.server.packs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/** Serves the official 26.2 vanilla pack from Gaius' validated browser asset archive. */
public class VanillaPackResources implements PackResources {
    private static final String RESOURCE_LIST = "dev/gaius/browser/minecraft-resources.txt";
    private static final String FALLBACK_PACK_ICON = "assets/minecraft/textures/misc/unknown_pack.png";

    private final PackLocationInfo location;
    private final ResourceMetadata metadata;
    private final Set<String> namespaces;
    private final String[] resources;
    private final Set<String> resourceSet;
    private final Map<String, ListedResource[]> listedResourceCache = new HashMap<>();

    VanillaPackResources(
            PackLocationInfo location,
            ResourceMetadata metadata,
            Set<String> namespaces,
            List<Path> rootPaths,
            Map<PackType, List<Path>> pathsForType) {
        this.location = location;
        this.metadata = metadata;
        this.namespaces = namespaces;
        this.resources = sortedResourceCopy(loadResourceList());
        this.resourceSet = new HashSet<>(List.of(this.resources));
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        if (path.length == 0) {
            return null;
        }
        return rootSupplierIfPresent(String.join("/", path));
    }

    public void listRawPaths(PackType type, Identifier id, java.util.function.Consumer<Path> output) {
        // Browser resources do not have stable java.nio.file.Path values.
    }

    @Override
    public void listResources(
            PackType type, String namespace, String path, PackResources.ResourceOutput output) {
        for (ListedResource resource : listedResources(type, namespace, path)) {
            output.accept(resource.id, openClasspathResource(resource.resource));
        }
    }

    private ListedResource[] listedResources(PackType type, String namespace, String path) {
        String key = type.getDirectory() + "\n" + namespace + "\n" + path;
        ListedResource[] cached = this.listedResourceCache.get(key);
        if (cached != null) {
            return cached;
        }
        String root = type.getDirectory() + "/" + namespace + "/";
        String prefix = root + (path.isEmpty() ? "" : path + "/");
        List<ListedResource> matches = new ArrayList<>();
        int start = lowerBound(this.resources, prefix);
        for (int index = start; index < this.resources.length; index++) {
            String resource = this.resources[index];
            if (!resource.startsWith(prefix)) {
                break;
            }
            Identifier id = Identifier.tryBuild(namespace, resource.substring(root.length()));
            if (id != null) {
                matches.add(new ListedResource(id, resource));
            }
        }
        cached = matches.toArray(ListedResource[]::new);
        this.listedResourceCache.put(key, cached);
        return cached;
    }

    private static int lowerBound(String[] values, String target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle].compareTo(target) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        String resource = type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath();
        return supplierIfPresent(resource);
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return this.namespaces;
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> type) {
        return this.metadata.getSection(type).orElse(null);
    }

    @Override
    public PackLocationInfo location() {
        return this.location;
    }

    @Override
    public void close() {
    }

    public ResourceProvider asProvider() {
        return id -> Optional.ofNullable(getResource(PackType.CLIENT_RESOURCES, id))
                .map(supplier -> new Resource(this, supplier));
    }

    private IoSupplier<InputStream> supplierIfPresent(String resource) {
        return exists(resource) ? openClasspathResource(resource) : null;
    }

    private IoSupplier<InputStream> rootSupplierIfPresent(String resource) {
        if (!exists(resource) && !existsOnClasspath(resource)) {
            return "pack.png".equals(resource) ? supplierIfPresent(FALLBACK_PACK_ICON) : null;
        }
        return openClasspathResource(resource);
    }

    private static IoSupplier<InputStream> openClasspathResource(String resource) {
        return () -> {
            byte[] external = readExternalResource(resource);
            if (external != null) {
                return new ByteArrayInputStream(external);
            }
            InputStream input = openResourceStream(resource);
            if (input == null) {
                throw new IOException("Missing browser vanilla resource: " + resource);
            }
            return new ByteArrayInputStream(input.readAllBytes());
        };
    }

    private boolean exists(String resource) {
        return this.resourceSet.contains(resource);
    }

    private static boolean existsOnClasspath(String resource) {
        if (externalResourceLength(resource) >= 0) {
            return true;
        }
        try (InputStream input = openResourceStream(resource)) {
            return input != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static InputStream openResourceStream(String resource) {
        String normalized = resource.startsWith("/") ? resource.substring(1) : resource;
        return VanillaPackResources.class.getClassLoader().getResourceAsStream(normalized);
    }

    private static byte[] readExternalResource(String resource) {
        int length = externalResourceLength(resource);
        if (length < 0) {
            return null;
        }
        byte[] output = new byte[length];
        return copyExternalResource(resource, output) ? output : null;
    }

    @JSBody(params = "resource", script = """
            const root = globalThis.__gaiusVanillaAssets;
            if (!root || !root.bytes || !root.index) return -1;
            if (!Object.prototype.hasOwnProperty.call(root.index, resource)) return -1;
            const range = root.index[resource];
            return Array.isArray(range) && range.length === 2 ? range[1] | 0 : -1;
            """)
    private static native int externalResourceLength(String resource);

    @JSBody(params = {"resource", "output"}, script = """
            const root = globalThis.__gaiusVanillaAssets;
            if (!root || !root.bytes || !root.index) return false;
            if (!Object.prototype.hasOwnProperty.call(root.index, resource)) return false;
            const range = root.index[resource];
            if (!Array.isArray(range) || range.length !== 2) return false;
            const offset = range[0] | 0;
            const length = range[1] | 0;
            const target = output && output.data ? output.data : output;
            if (!target || target.length !== length || offset < 0 || length < 0) return false;
            const start = root.dataOffset + offset;
            const end = start + length;
            if (start < root.dataOffset || end > root.bytes.length) return false;
            target.set(root.bytes.subarray(start, end));
            return true;
            """)
    private static native boolean copyExternalResource(String resource, @JSByRef byte[] output);

    private static String[] loadResourceList() {
        try (InputStream input = VanillaPackResources.class.getClassLoader()
                .getResourceAsStream(RESOURCE_LIST)) {
            if (input == null) {
                return new String[0];
            }
            String[] lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n");
            List<String> result = new ArrayList<>(lines.length);
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result.toArray(String[]::new);
        } catch (IOException ignored) {
            return new String[0];
        }
    }

    /**
     * The browser resource-list producer is outside the runtime trust
     * boundary.  Keep lowerBound's Java String.compareTo precondition true
     * even when a stale, locale-sorted, or hand-authored list is supplied.
     */
    private static String[] sortedResourceCopy(String[] values) {
        String[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return copy;
    }

    private static final class ListedResource {
        private final Identifier id;
        private final String resource;

        private ListedResource(Identifier id, String resource) {
            this.id = id;
            this.resource = resource;
        }
    }
}
