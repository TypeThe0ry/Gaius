package net.minecraft;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Browser system report with the same public API as the desktop client.
 */
public class SystemReport {
    public static final long BYTES_PER_MEBIBYTE = 1_048_576L;

    private final Map<String, String> entries = new LinkedHashMap<>();

    public SystemReport() {
        WorldVersion version = SharedConstants.getCurrentVersion();
        setDetail("Minecraft Version", version.name());
        setDetail("Minecraft Version ID", version.id());
        setDetail("Operating System", "Web Browser");
        setDetail("Java Version", "TeaVM 0.15 / Java 21 compatibility");
        setDetail("Java VM Version", "JavaScript browser runtime");
        setDetail("Memory", "Managed by the browser");
        setDetail("CPUs", "Managed by the browser");
        setDetail("JVM Flags", "0 total; ");
        setDetail("Debug Flags", "0 total; ");
    }

    public void setDetail(String name, String value) {
        entries.put(name, value);
    }

    public void setDetail(String name, Supplier<String> value) {
        try {
            setDetail(name, value.get());
        } catch (Exception exception) {
            setDetail(name, "ERR");
        }
    }

    public static float sizeInMiB(long bytes) {
        return (float) bytes / BYTES_PER_MEBIBYTE;
    }

    public void appendToCrashReportString(StringBuilder output) {
        entries.forEach((name, value) -> output
                .append('\t').append(name).append(": ").append(value).append('\n'));
    }

    public String toLineSeparatedString() {
        StringBuilder output = new StringBuilder();
        entries.forEach((name, value) -> output
                .append(String.format(Locale.ROOT, "%s: %s%n", name, value)));
        return output.toString();
    }
}
