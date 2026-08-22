package com.mojang.logging;

import java.util.Collections;
import java.util.Iterator;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/**
 * Browser-safe LogUtils: no java.lang.StackWalker, no log4j-core configuration
 * surface. Logger names are fixed because the browser runtime cannot walk the
 * Java call stack.
 */
public class LogUtils {
    public static final String FATAL_MARKER_ID = "FATAL";
    public static final Marker FATAL_MARKER = new GaiusMarker(FATAL_MARKER_ID);

    private static final String FALLBACK_LOGGER_NAME = "Minecraft";

    public LogUtils() {
    }

    public static boolean isLoggerActive() {
        return true;
    }

    public static void configureRootLoggingLevel(org.slf4j.event.Level level) {
        // The browser logger is a fixed console logger; nothing to configure.
    }

    public static Object defer(Supplier<Object> supplier) {
        return supplier.get();
    }

    public static Logger getLogger() {
        return LoggerFactory.getLogger(FALLBACK_LOGGER_NAME);
    }

    /** Minimal marker; the browser logger ignores markers entirely. */
    private static final class GaiusMarker implements Marker {
        private static final long serialVersionUID = 1L;

        private final String name;

        GaiusMarker(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void add(Marker reference) {
            // No children in the browser marker.
        }

        public boolean remove(Marker reference) {
            return false;
        }

        public boolean hasChildren() {
            return false;
        }

        public boolean hasReferences() {
            return false;
        }

        public Iterator<Marker> iterator() {
            return Collections.emptyIterator();
        }

        public boolean contains(Marker other) {
            return equals(other);
        }

        public boolean contains(String otherName) {
            return name.equals(otherName);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || !(other instanceof Marker)) {
                return false;
            }
            return name.equals(((Marker) other).getName());
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
