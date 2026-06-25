package dev.gaius.browser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.teavm.classlib.ResourceSupplier;
import org.teavm.classlib.ResourceSupplierContext;

/**
 * Supplies classpath resources that the official Minecraft client accesses through
 * {@code Class#getResourceAsStream}. TeaVM's JavaScript backend only embeds resources
 * named by {@link ResourceSupplier}s, so plain entries inside the client jar are not
 * visible in the browser unless they are listed here.
 */
public final class MinecraftResourceSupplier implements ResourceSupplier {
    private static final String RESOURCE_LIST = "/dev/gaius/browser/minecraft-resources.txt";
    private static final String[] FALLBACK_RESOURCES = {
            "assets/minecraft/lang/deprecated.json",
            "assets/minecraft/lang/en_us.json"
    };

    @Override
    public String[] supplyResources(ResourceSupplierContext context) {
        try (InputStream input = MinecraftResourceSupplier.class.getResourceAsStream(RESOURCE_LIST)) {
            if (input == null) {
                return FALLBACK_RESOURCES.clone();
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream((RESOURCE_LIST.substring(1) + "\n" + text).split("\\R"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toArray(String[]::new);
        } catch (IOException ignored) {
            return FALLBACK_RESOURCES.clone();
        }
    }
}
