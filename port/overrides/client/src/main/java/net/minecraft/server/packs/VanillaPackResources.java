package net.minecraft.server.packs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private final PackLocationInfo location;
    private final BuiltInMetadata metadata;
    private final Set<String> namespaces;
    private final String[] resources;

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
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        if (path.length == 0) {
            return null;
        }
        String resource = String.join("/", path);
        return supplierIfPresent(resource);
    }

    public void listRawPaths(PackType type, Identifier id, java.util.function.Consumer<Path> output) {
        // Browser classpath resources do not have stable java.nio.file.Path values.
    }

    @Override
    public void listResources(
            PackType type, String namespace, String path, PackResources.ResourceOutput output) {
        String root = type.getDirectory() + "/" + namespace + "/";
        String prefix = root + (path.isEmpty() ? "" : path + "/");
        for (String resource : resources) {
            if (!resource.startsWith(prefix)) {
                continue;
            }
            String relative = resource.substring(root.length());
            Identifier id = Identifier.tryBuild(namespace, relative);
            if (id != null) {
                output.accept(id, openClasspathResource(resource));
            }
        }
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

    private static IoSupplier<InputStream> supplierIfPresent(String resource) {
        if (!exists(resource)) {
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

    private static boolean exists(String resource) {
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
}
