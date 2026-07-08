package net.minecraft.server.packs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * Browser resource-pack implementation for the official vanilla pack.
 *
 * <p>The desktop implementation resolves vanilla assets through jar/file-system
 * paths. In TeaVM those paths do not exist, while the actual 1.21.11 resources
 * are embedded as classpath resources by {@code MinecraftResourceSupplier}. This
 * class keeps the public Minecraft pack contract and redirects resource lookup
 * to the embedded classpath resource table.</p>
 */
public class VanillaPackResources implements PackResources {
    private static final String RESOURCE_LIST = "dev/gaius/browser/minecraft-resources.txt";
    private static final String FALLBACK_PACK_ICON = "assets/minecraft/textures/misc/unknown_pack.png";

    private final PackLocationInfo location;
    private final BuiltInMetadata metadata;
    private final Set<String> namespaces;
    private final String[] resources;
    private final Set<String> resourceSet;
    private final Map<String, ListedResource[]> listedResourceCache = new HashMap<>();

    VanillaPackResources(
            PackLocationInfo location,
            BuiltInMetadata metadata,
            Set<String> namespaces,
            List<Path> rootPaths,
            Map<PackType, List<Path>> pathsForType) {
        this.location = location;
        this.metadata = metadata;
        this.namespaces = namespaces;
        this.resources = loadResourceList();
        this.resourceSet = new HashSet<>(List.of(this.resources));
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        if (path.length == 0) {
            return null;
        }
        String resource = String.join("/", path);
        return rootSupplierIfPresent(resource);
    }

    public void listRawPaths(PackType type, Identifier id, java.util.function.Consumer<Path> output) {
        // Browser classpath resources do not have stable java.nio.file.Path values.
    }

    @Override
    public void listResources(
            PackType type, String namespace, String path, PackResources.ResourceOutput output) {
        ListedResource[] listedResources = listedResources(type, namespace, path);
        for (ListedResource resource : listedResources) {
            output.accept(resource.id, openClasspathResource(resource.resource));
        }
    }

    private ListedResource[] listedResources(PackType type, String namespace, String path) {
        String key = type.getDirectory() + "\n" + namespace + "\n" + path;
        ListedResource[] cached = listedResourceCache.get(key);
        if (cached != null) {
            return cached;
        }
        String root = type.getDirectory() + "/" + namespace + "/";
        String prefix = root + (path.isEmpty() ? "" : path + "/");
        List<ListedResource> listedResources = new ArrayList<>();
        for (String resource : resources) {
            if (!resource.startsWith(prefix)) {
                continue;
            }
            String relative = resource.substring(root.length());
            Identifier id = Identifier.tryBuild(namespace, relative);
            if (id != null) {
                listedResources.add(new ListedResource(id, resource));
            }
        }
        cached = listedResources.toArray(ListedResource[]::new);
        listedResourceCache.put(key, cached);
        return cached;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        String resource = type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath();
        return supplierIfPresent(resource);
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return namespaces;
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> type) {
        return metadata.get(type);
    }

    @Override
    public PackLocationInfo location() {
        return location;
    }

    @Override
    public void close() {
    }

    public ResourceProvider asProvider() {
        return id -> Optional.ofNullable(getResource(PackType.CLIENT_RESOURCES, id))
                .map(supplier -> new Resource(this, supplier));
    }

    private IoSupplier<InputStream> supplierIfPresent(String resource) {
        if (!exists(resource)) {
            return null;
        }
        return openClasspathResource(resource);
    }

    private IoSupplier<InputStream> rootSupplierIfPresent(String resource) {
        if (!exists(resource) && !existsOnClasspath(resource)) {
            if ("pack.png".equals(resource)) {
                return supplierIfPresent(FALLBACK_PACK_ICON);
            }
            return null;
        }
        return openClasspathResource(resource);
    }

    private static IoSupplier<InputStream> openClasspathResource(String resource) {
        return () -> {
            InputStream input = openResourceStream(resource);
            if (input == null) {
                throw new IOException("Missing embedded vanilla resource: " + resource);
            }
            return new ByteArrayInputStream(input.readAllBytes());
        };
    }

    private boolean exists(String resource) {
        return resourceSet.contains(resource);
    }

    private static boolean existsOnClasspath(String resource) {
        try (InputStream input = openResourceStream(resource)) {
            return input != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static InputStream openResourceStream(String resource) {
        String normalized = resource.startsWith("/") ? resource.substring(1) : resource;
        InputStream input = VanillaPackResources.class.getResourceAsStream("/" + normalized);
        if (input != null) {
            return input;
        }
        return VanillaPackResources.class.getClassLoader().getResourceAsStream(normalized);
    }

    private static String[] loadResourceList() {
        try (InputStream input = VanillaPackResources.class.getClassLoader()
                .getResourceAsStream(RESOURCE_LIST)) {
            if (input == null) {
                return new String[0];
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = text.split("\\r?\\n");
            List<String> resources = new ArrayList<>(lines.length);
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    resources.add(trimmed);
                }
            }
            return resources.toArray(String[]::new);
        } catch (IOException e) {
            return new String[0];
        }
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
