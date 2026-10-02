package dev.gaius.tools.runtime;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Role-level TeaVM compiler options for the Gaius build, read inside the patched TeaVM compiler.
 *
 * <p>The patched TeaVM classes (see {@code TeaVMCoreBrowserPatcher}) call into this class, and
 * the patcher copies it into the patched teavm-core jar, so it runs in the TeaVM Maven plugin.
 * It must only depend on the JDK: the patchers are compiled against ASM alone, and every TeaVM
 * model object is handled reflectively.
 *
 * <p>The options come from the {@code <properties>} block of the TeaVM plugin configuration that
 * {@code port/scripts/generate-pom.sh} writes from the role table (client or
 * singleplayer-worker). The patched {@code TeaVM.getPlatformTags} (read when plugins are
 * installed) and {@code Renderer.setProperties} pass them here before dependency analysis,
 * the async analysis and name allocation run. JVM system properties are not
 * read: a leftover {@code -Dgaius.teavm.*} toggle fails the build so that a stale MAVEN_OPTS
 * cannot silently change the output. Without a configuration every option is off, which is
 * the vanilla TeaVM behaviour.
 */
public final class GaiusTeaVMOptions {
    public static final String PREFIX = "gaius.teavm.";
    public static final String ROLE = PREFIX + "role";
    /**
     * Callers of an async class initializer are not made async (see TeaVMCoreBrowserPatcher).
     * Cooperative yields (TModernRuntimeSupport.yieldToEventLoop, TLockSupport) are skipped
     * while a class initializer runs, so on the client the Blocks and Items initializers no
     * longer repaint at their BrowserStartupScheduler checkpoints: the startup progress events
     * are still recorded, but the page stays unpainted for those seconds. A real suspension
     * inside an initializer is counted in globalThis.__gaiusClinitSuspensions (patched
     * thread.js). The barrier and syncMonitors stay consistent with syncClinits off as well,
     * because yields and parks are also skipped inside guarded methods
     * (globalThis.__gaiusNoSuspendDepth), so a role can turn this off on its own.
     */
    public static final String SYNC_CLINITS = PREFIX + "syncClinits";
    /**
     * java.lang.Object monitor primitives are compiled synchronously, with a suspension guard.
     * Synchronized methods that only became async through the monitor primitives (the
     * exception construction in monitorExitSync) are then compiled synchronously too, and
     * TeaVM renders their monitor entry as monitorEnterSync. When such a monitor is contended,
     * monitorEnterSync throws IllegalStateException("Can't enter monitor from another thread
     * synchronously") without reaching TeaVMThread.suspend: it is not counted in
     * globalThis.__gaiusNoSuspendViolations, and the acceptance scripts match the message
     * text instead.
     */
    public static final String SYNC_MONITORS = PREFIX + "syncMonitors";
    /** Comma-separated barrier groups (see {@link #BARRIER_GROUPS}), or "none". */
    public static final String ASYNC_BARRIER = PREFIX + "asyncBarrier";
    /** TeaVM runtime functions ($rt_*, Long_*) always get a top-level binding. */
    public static final String PIN_RUNTIME_NAMES = PREFIX + "pinRuntimeNames";
    /** "false" removes the {@link #TELEMETRY_TAG} platform tag (release builds). */
    public static final String TELEMETRY = PREFIX + "telemetry";
    /** Optional file that receives the methods the async options compiled synchronously. */
    public static final String CUT_REPORT = PREFIX + "cutReport";

    /**
     * Platform tag present unless the role strips telemetry. Code marks a static boolean
     * method with {@code @PlatformMarker("gaius-telemetry")}; TeaVM's PlatformMarkerSupport
     * replaces every call with this compile-time constant, so a stripped release drops the
     * guarded telemetry branches before optimization.
     */
    public static final String TELEMETRY_TAG = "gaius-telemetry";
    /** Platform tag prefix for the role, for example {@code gaius-role-client}. */
    public static final String ROLE_TAG_PREFIX = "gaius-role-";

    /** JS counter of active frames that must not suspend (cut methods). */
    public static final String NO_SUSPEND_COUNTER = "globalThis.__gaiusNoSuspendDepth";
    static final String GUARD_OPEN =
            NO_SUSPEND_COUNTER + "=(" + NO_SUSPEND_COUNTER + "|0)+1;try{";
    static final String GUARD_CLOSE = "}finally{" + NO_SUSPEND_COUNTER + "--;}";

    private static final Set<String> KNOWN_KEYS = Set.of(
            ROLE, SYNC_CLINITS, SYNC_MONITORS, ASYNC_BARRIER, PIN_RUNTIME_NAMES, TELEMETRY,
            CUT_REPORT);
    private static final Set<String> ROLES = Set.of("client", "singleplayer-worker");

    /**
     * Barrier groups: virtual families whose implementations never suspend in practice but
     * make every caller of the family async as soon as one implementation is async (most often
     * through exception construction, Class.getName or StringBuilder). Entries are
     * {root class, method name, descriptor, kind}; kind "family" matches every non-static
     * override declared in a subtype of the root, kind "exact" matches only the root class.
     * A method on this list is compiled synchronously and its callers are not made async;
     * its body is wrapped in a guard that throws if a suspension is reached inside it.
     */
    private static final Map<String, String[][]> BARRIER_GROUPS = barrierGroups();

    private static final Set<String> MONITOR_PRIMITIVES = Set.of(
            "monitorEnter", "monitorExit", "monitorEnterSync", "monitorExitSync");

    private static boolean configured;
    private static String role = "unconfigured";
    private static boolean syncClinits;
    private static boolean syncMonitors;
    private static boolean pinRuntimeNames;
    private static boolean telemetry = true;
    private static String cutReport;
    private static List<String[]> barrierEntries = List.of();
    private static Set<String> barrierNames = Set.of();
    private static Set<String> barrierGroupNames = Set.of();

    private static final Set<Object> cutMethods = new HashSet<>();
    private static final Map<String, Integer> cutCounts = new TreeMap<>();
    private static final List<String> cutDescriptions = new ArrayList<>();
    private static final Map<String, Boolean> subtypeCache = new HashMap<>();
    private static final Map<String, Method> reflectionCache = new HashMap<>();

    private GaiusTeaVMOptions() {
    }

    private static Map<String, String[][]> barrierGroups() {
        Map<String, String[][]> groups = new HashMap<>();
        groups.put("object", new String[][] {
            {"java.lang.Object", "toString", "()Ljava/lang/String;", "family"},
            {"java.lang.Object", "hashCode", "()I", "family"},
            {"java.lang.Object", "equals", "(Ljava/lang/Object;)Z", "family"},
        });
        groups.put("throwable", new String[][] {
            {"java.lang.Throwable", "fillInStackTrace", "()Ljava/lang/Throwable;", "family"},
            {"java.lang.Throwable", "getMessage", "()Ljava/lang/String;", "family"},
            {"java.lang.Throwable", "getLocalizedMessage", "()Ljava/lang/String;", "family"},
        });
        groups.put("map", new String[][] {
            {"java.util.Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", "family"},
            {"java.util.Map", "containsKey", "(Ljava/lang/Object;)Z", "family"},
            {"java.util.Map", "size", "()I", "family"},
            {"java.util.Map", "isEmpty", "()Z", "family"},
        });
        groups.put("collection", new String[][] {
            {"java.util.Collection", "size", "()I", "family"},
            {"java.util.Collection", "isEmpty", "()Z", "family"},
            {"java.util.Collection", "contains", "(Ljava/lang/Object;)Z", "family"},
            {"java.util.Collection", "iterator", "()Ljava/util/Iterator;", "family"},
            {"java.lang.Iterable", "iterator", "()Ljava/util/Iterator;", "family"},
        });
        groups.put("iterator", new String[][] {
            {"java.util.Iterator", "hasNext", "()Z", "family"},
            {"java.util.Iterator", "next", "()Ljava/lang/Object;", "family"},
        });
        groups.put("stringbuilder", new String[][] {
            {"java.lang.StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;",
                "exact"},
            {"java.lang.AbstractStringBuilder", "append",
                "(Ljava/lang/Object;)Ljava/lang/AbstractStringBuilder;", "exact"},
            {"java.lang.String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", "exact"},
        });
        return Collections.unmodifiableMap(groups);
    }

    /** The barrier group names, for the build scripts' validation and documentation. */
    public static Set<String> barrierGroupNames() {
        return BARRIER_GROUPS.keySet();
    }

    /**
     * Reads the role configuration. Called from the patched TeaVM with the compiler properties;
     * a later call with the same properties is a no-op, a call with different Gaius options
     * fails because part of the compilation already used the previous ones.
     */
    public static synchronized void configure(Properties properties) {
        rejectSystemPropertyToggles();
        if (properties == null) {
            return;
        }
        Map<String, String> gaius = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(PREFIX)) {
                if (!KNOWN_KEYS.contains(name)) {
                    throw new IllegalStateException("Unknown Gaius TeaVM option " + name
                            + " (known: " + new TreeSet<>(KNOWN_KEYS) + ")");
                }
                gaius.put(name, properties.getProperty(name).trim());
            }
        }
        if (gaius.isEmpty()) {
            if (configured) {
                throw new IllegalStateException(
                        "The Gaius TeaVM options disappeared from the compiler properties");
            }
            return;
        }
        String newRole = gaius.getOrDefault(ROLE, "");
        if (!ROLES.contains(newRole)) {
            throw new IllegalStateException("Invalid " + ROLE + " '" + newRole
                    + "' (expected one of " + ROLES + ")");
        }
        boolean newSyncClinits = parseBoolean(gaius, SYNC_CLINITS, false);
        boolean newSyncMonitors = parseBoolean(gaius, SYNC_MONITORS, false);
        boolean newPin = parseBoolean(gaius, PIN_RUNTIME_NAMES, false);
        boolean newTelemetry = parseBoolean(gaius, TELEMETRY, true);
        String newReport = gaius.get(CUT_REPORT);
        if (newReport != null && newReport.isEmpty()) {
            newReport = null;
        }
        Set<String> groups = new LinkedHashSet<>();
        String barrier = gaius.getOrDefault(ASYNC_BARRIER, "none");
        if (!barrier.isEmpty() && !barrier.equals("none")) {
            for (String group : barrier.split(",")) {
                String trimmed = group.trim();
                if (!BARRIER_GROUPS.containsKey(trimmed)) {
                    throw new IllegalStateException("Unknown " + ASYNC_BARRIER + " group '"
                            + trimmed + "' (known: " + new TreeMap<>(BARRIER_GROUPS).keySet()
                            + ")");
                }
                groups.add(trimmed);
            }
        }
        if (configured) {
            if (!newRole.equals(role) || newSyncClinits != syncClinits
                    || newSyncMonitors != syncMonitors || newPin != pinRuntimeNames
                    || newTelemetry != telemetry || !groups.equals(barrierGroupNames)) {
                throw new IllegalStateException(
                        "The Gaius TeaVM options changed during one compilation");
            }
            return;
        }
        List<String[]> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String group : groups) {
            for (String[] entry : BARRIER_GROUPS.get(group)) {
                entries.add(entry);
                names.add(entry[1]);
            }
        }
        role = newRole;
        syncClinits = newSyncClinits;
        syncMonitors = newSyncMonitors;
        pinRuntimeNames = newPin;
        telemetry = newTelemetry;
        cutReport = newReport;
        barrierEntries = List.copyOf(entries);
        barrierNames = Set.copyOf(names);
        barrierGroupNames = Collections.unmodifiableSet(groups);
        configured = true;
        System.out.println("[gaius-teavm] role=" + role + " syncClinits=" + syncClinits
                + " syncMonitors=" + syncMonitors + " asyncBarrier="
                + (groups.isEmpty() ? "none" : String.join(",", groups))
                + " pinRuntimeNames=" + pinRuntimeNames + " telemetry=" + telemetry);
    }

    private static boolean parseBoolean(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        throw new IllegalStateException("Invalid " + key + " '" + value
                + "' (expected true or false)");
    }

    private static void rejectSystemPropertyToggles() {
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(PREFIX)) {
                throw new IllegalStateException("JVM system property " + name
                        + " is no longer read: TeaVM options come from the role configuration"
                        + " in port/scripts/generate-pom.sh (POM <properties>). Remove it from"
                        + " MAVEN_OPTS.");
            }
        }
    }

    /**
     * Patched {@code TeaVM.getPlatformTags}: configures the options from the compiler
     * properties and appends the role and telemetry tags. The class library plugin reads the
     * tags when TeaVM installs plugins, after TeaVMTool has set the properties; the TeaVM
     * constructor reads the target's tags directly, before that, and is not affected.
     */
    public static String[] platformTags(String[] base, Properties properties) {
        configure(properties);
        if (!configured) {
            return base;
        }
        List<String> tags = new ArrayList<>(Arrays.asList(base));
        tags.add(ROLE_TAG_PREFIX + role);
        if (telemetry) {
            tags.add(TELEMETRY_TAG);
        }
        return tags.toArray(new String[0]);
    }

    public static boolean syncClinits() {
        return syncClinits;
    }

    public static boolean syncMonitors() {
        return syncMonitors;
    }

    public static boolean pinRuntimeNames() {
        return pinRuntimeNames;
    }

    /**
     * Whether a TeaVM runtime function keeps a top-level binding even after the top-level name
     * budget (maxTopLevelNames) is spent. Runtime functions are few (a few hundred) and every
     * coroutine check goes through some of them ($rt_suspending, $rt_resuming,
     * $rt_nativeThread and the current-thread slot), so a dictionary-mode property load there
     * costs on every async call.
     */
    public static boolean isPinnedRuntimeName(String name) {
        return pinRuntimeNames && name != null
                && (name.startsWith("$rt_") || name.startsWith("Long_"));
    }

    /** Patched {@code AsyncMethodFinder.find} start. */
    public static synchronized void beginAsyncAnalysis() {
        cutMethods.clear();
        cutCounts.clear();
        cutDescriptions.clear();
        subtypeCache.clear();
    }

    /**
     * Patched {@code AsyncMethodFinder.add} start: true when the method must not become async
     * and must not make its callers async. The method is then rendered synchronously with the
     * {@link #guardOpen} wrapper.
     */
    public static synchronized boolean cutAsync(Object classSource, Object methodRef) {
        if (!syncMonitors && barrierEntries.isEmpty()) {
            return false;
        }
        if (cutMethods.contains(methodRef)) {
            return true;
        }
        String name = (String) invoke(methodRef, "getName");
        String className = (String) invoke(methodRef, "getClassName");
        String reason = null;
        if (syncMonitors && className.equals("java.lang.Object")
                && MONITOR_PRIMITIVES.contains(name)) {
            reason = "syncMonitors";
        } else if (barrierNames.contains(name)) {
            String descriptor = String.valueOf(invoke(methodRef, "getDescriptor"));
            String signature = descriptor.substring(name.length());
            for (String[] entry : barrierEntries) {
                if (!entry[1].equals(name) || !entry[2].equals(signature)) {
                    continue;
                }
                if (entry[3].equals("exact") ? className.equals(entry[0])
                        : isFamilyMember(classSource, className, entry[0], methodRef)) {
                    reason = "asyncBarrier:" + entry[0] + "." + entry[1];
                    break;
                }
            }
        }
        if (reason == null) {
            return false;
        }
        Object method = methodReader(classSource, className, methodRef);
        if (method != null && hasAnnotation(method, "org.teavm.interop.Async")) {
            throw new IllegalStateException("Gaius TeaVM option " + reason + " would compile "
                    + methodRef + " synchronously, but it is an @Async method; remove it"
                    + " from the barrier list");
        }
        cutMethods.add(methodRef);
        cutCounts.merge(reason, 1, Integer::sum);
        cutDescriptions.add(reason + "\t" + methodRef);
        return true;
    }

    private static boolean isFamilyMember(
            Object classSource, String className, String root, Object methodRef) {
        Object method = methodReader(classSource, className, methodRef);
        if (method == null || hasModifier(method, "STATIC")) {
            return false;
        }
        Object level = invoke(method, "getLevel");
        if (level instanceof Enum<?> access && access.name().equals("PRIVATE")) {
            return false;
        }
        if (root.equals("java.lang.Object")) {
            return true;
        }
        return isSubtype(classSource, className, root);
    }

    private static boolean isSubtype(Object classSource, String className, String root) {
        String key = className + "|" + root;
        Boolean cached = subtypeCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(className);
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(root)) {
                result = true;
                break;
            }
            Object cls = invoke(classSource, "get", current);
            if (cls == null) {
                continue;
            }
            Object parent = invoke(cls, "getParent");
            if (parent != null) {
                pending.add((String) parent);
            }
            Object interfaces = invoke(cls, "getInterfaces");
            if (interfaces instanceof Collection<?> collection) {
                for (Object item : collection) {
                    pending.add((String) item);
                }
            }
        }
        subtypeCache.put(key, result);
        return result;
    }

    private static Object methodReader(Object classSource, String className, Object methodRef) {
        Object cls = invoke(classSource, "get", className);
        if (cls == null) {
            return null;
        }
        return invoke(cls, "getMethod", invoke(methodRef, "getDescriptor"));
    }

    private static boolean hasModifier(Object element, String modifier) {
        Object modifiers = invoke(element, "readModifiers");
        if (modifiers instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Enum<?> value && value.name().equals(modifier)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAnnotation(Object element, String annotation) {
        Object annotations = invoke(element, "getAnnotations");
        return annotations != null && invoke(annotations, "get", annotation) != null;
    }

    /** Patched {@code AsyncMethodFinder.find} end: logs and optionally writes the cut list. */
    public static synchronized void reportCuts() {
        if (!syncMonitors && barrierEntries.isEmpty()) {
            return;
        }
        System.out.println("[gaius-teavm] methods compiled synchronously by role options: "
                + cutMethods.size() + " " + cutCounts);
        if (syncMonitors && cutCounts.getOrDefault("syncMonitors", 0) == 0) {
            System.out.println("[gaius-teavm] WARNING: syncMonitors is on but no monitor"
                    + " primitive reached the async analysis");
        }
        if (cutReport != null) {
            List<String> lines = new ArrayList<>(cutDescriptions);
            Collections.sort(lines);
            try {
                Path path = Path.of(cutReport);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.write(path, lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Could not write " + cutReport, e);
            }
        }
    }

    /** Patched {@code Renderer.renderRegularBody}: JS written after the method prologue. */
    public static synchronized String guardOpen(Object methodRef) {
        return cutMethods.contains(methodRef) ? GUARD_OPEN : null;
    }

    /** Patched {@code Renderer.renderRegularBody}: JS written after the method body. */
    public static synchronized String guardClose(Object methodRef) {
        return cutMethods.contains(methodRef) ? GUARD_CLOSE : null;
    }

    private static Object invoke(Object target, String name, Object... arguments) {
        Method method = findMethod(target.getClass(), name, arguments);
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call " + name + " on " + target.getClass(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(name + " failed on " + target.getClass(), cause);
        }
    }

    /**
     * Finds a public method through the public interfaces and superclasses of a TeaVM model
     * class (implementation classes are often package-private, so their own Method objects
     * cannot be invoked from here).
     */
    private static Method findMethod(Class<?> type, String name, Object[] arguments) {
        StringBuilder keyBuilder = new StringBuilder(type.getName()).append('#').append(name);
        for (Object argument : arguments) {
            keyBuilder.append('/').append(argument == null ? "null" : argument.getClass().getName());
        }
        String key = keyBuilder.toString();
        Method cached = reflectionCache.get(key);
        if (cached != null) {
            return cached;
        }
        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        pending.add(type);
        Method found = null;
        while (!pending.isEmpty() && found == null) {
            Class<?> current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (Modifier.isPublic(current.getModifiers())) {
                for (Method candidate : current.getMethods()) {
                    if (candidate.getName().equals(name)
                            && Modifier.isPublic(candidate.getDeclaringClass().getModifiers())
                            && accepts(candidate, arguments)) {
                        found = candidate;
                        break;
                    }
                }
            }
            if (current.getSuperclass() != null) {
                pending.add(current.getSuperclass());
            }
            pending.addAll(Arrays.asList(current.getInterfaces()));
        }
        if (found == null) {
            throw new IllegalStateException("TeaVM API changed: no public " + name + " accepting "
                    + key + " on " + type.getName());
        }
        reflectionCache.put(key, found);
        return found;
    }

    private static boolean accepts(Method method, Object[] arguments) {
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != arguments.length) {
            return false;
        }
        for (int index = 0; index < parameters.length; index++) {
            if (parameters[index].isPrimitive()
                    || (arguments[index] != null && !parameters[index].isInstance(arguments[index]))) {
                return false;
            }
        }
        return true;
    }
}
