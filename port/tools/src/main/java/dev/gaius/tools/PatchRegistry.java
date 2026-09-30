package dev.gaius.tools;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Records the outcome of every browser patch call so that no patch can be skipped silently
 * (gate G4 of the 26.3 migration plan).
 *
 * <p>Each patch call ends in exactly one of three states:
 * <ul>
 *   <li><b>applied</b> &mdash; {@link #run} ran the body, or a patcher called {@link #applied};</li>
 *   <li><b>dropped</b> &mdash; {@link #dropped} proved that the patch's original target is absent
 *       from the jar being patched;</li>
 *   <li><b>bring-up skipped</b> &mdash; only in bring-up mode, for ids listed in the profile's
 *       bring-up list;</li>
 * </ul>
 * or it throws, which fails the build.
 *
 * <h2>Inputs (set by the caller)</h2>
 * <ul>
 *   <li><b>Profile id</b>, first match wins:
 *     <ol>
 *       <li>{@link #configureProfile(String)}, called by a patcher {@code main} with its
 *           Minecraft version argument;</li>
 *       <li>system property {@code gaius.profile};</li>
 *       <li>environment variable {@code GAIUS_PROFILE};</li>
 *       <li>environment variable {@code GAIUS_MINECRAFT_VERSION} (exported for every child of
 *           {@code build-overlays.sh} by {@code version-profile.sh}).</li>
 *     </ol>
 *     A {@code configureProfile} value that disagrees with {@code gaius.profile} or
 *     {@code GAIUS_PROFILE} throws. {@code GAIUS_MINECRAFT_VERSION} is only a fallback.</li>
 *   <li><b>Bring-up flag</b>: system property {@code gaius.bringup} or environment variable
 *       {@code GAIUS_BRINGUP}; {@code 1}/{@code true}/{@code yes} enable it.</li>
 *   <li><b>Bring-up list</b>: system property {@code gaius.bringup.list} or environment variable
 *       {@code GAIUS_BRINGUP_LIST}. Without either, {@code port/tools/bringup/<profile>.txt} is
 *       looked up in the working directory and its parents, then in the directories above the
 *       location this class was loaded from.</li>
 * </ul>
 *
 * <p>Bring-up mode is active only when the flag is set <em>and</em> the profile is neither
 * {@code 26.2} nor {@code 1.21.11}. For those two profiles the flag is ignored (with a notice on
 * stderr), so their patch output never depends on it.
 *
 * <h2>Bring-up list format</h2>
 * One entry per line: {@code <patchId> | <owner package> | <reason>}. {@code #} starts a
 * comment that runs to the end of the line, and blank lines are ignored; a reason can therefore
 * contain neither {@code #} nor {@code |}. These are the rules of the other readers of the same
 * file ({@code version-profile.sh}, {@code check-version-profile.mjs} and
 * {@code check-build-log-skips.mjs}). {@code patchId} is the id used in logs, e.g.
 * {@code MinecraftClientPatcher.patchGlx}, {@code Minecraft262BrowserPatcher.patchVulkanBackend},
 * {@code step:LwjglSdlBrowserPatcher} or {@code step:LwjglMemoryPatcher@lwjgl} (shell steps,
 * read by build-overlays.sh). The owner is a work package {@code P1}..{@code P9}, optionally
 * with a letter such as {@code P7a}. Duplicate ids, a bad owner, a missing or extra field and an
 * empty reason throw.
 *
 * <h2>Log lines</h2>
 * {@code BRINGUP_SKIP <id>}, {@code PATCH_DROPPED <id>} and
 * {@code PATCH_SUMMARY applied=N dropped=N bringupSkipped=N} go to stdout. Warnings
 * ({@code BRINGUP_UNUSED <id>}, {@code BRINGUP_IGNORED ...}) and the pending list printed when a
 * patch fails outside bring-up mode ({@code BRINGUP_PENDING <line>}) go to stderr.
 */
public final class PatchRegistry {
    /** A patch body; the same shape as the {@code patchX(jar, output)} methods. */
    @FunctionalInterface
    public interface IORunnable {
        void run() throws IOException;
    }

    /** One line of a bring-up list. */
    public record BringupEntry(String patchId, String owner, String reason, int line) {
    }

    public static final String PROFILE_PROPERTY = "gaius.profile";
    public static final String PROFILE_ENV = "GAIUS_PROFILE";
    public static final String BUILD_PROFILE_ENV = "GAIUS_MINECRAFT_VERSION";
    public static final String BRINGUP_PROPERTY = "gaius.bringup";
    public static final String BRINGUP_ENV = "GAIUS_BRINGUP";
    public static final String BRINGUP_LIST_PROPERTY = "gaius.bringup.list";
    public static final String BRINGUP_LIST_ENV = "GAIUS_BRINGUP_LIST";
    public static final String VERBOSE_PROPERTY = "gaius.patch.verbose";
    public static final String VERBOSE_ENV = "GAIUS_PATCH_VERBOSE";

    private static final Pattern PATCH_ID = Pattern.compile("[A-Za-z0-9_$][A-Za-z0-9_$.:@/-]*");
    private static final Pattern OWNER = Pattern.compile("P[1-9][a-z]?");

    private static String configuredProfile;
    private static boolean resolved;
    private static String profile;
    private static boolean bringupActive;
    private static Path bringupList;
    private static Map<String, BringupEntry> bringupEntries = Map.of();
    private static final Set<String> queried = new LinkedHashSet<>();
    private static final Set<String> scopes = new LinkedHashSet<>();
    private static int applied;
    private static int dropped;
    private static int bringupSkipped;
    private static boolean pendingPrinted;

    private PatchRegistry() {
    }

    /**
     * Sets the profile from a patcher's version argument. Must be called before the first patch
     * call; a later call with a different value throws.
     */
    public static synchronized void configureProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profile id must not be empty");
        }
        if (configuredProfile != null && !configuredProfile.equals(profileId)) {
            throw new IllegalStateException("PatchRegistry profile already configured as "
                    + configuredProfile + ", refusing " + profileId);
        }
        if (resolved && !profileId.equals(profile)) {
            throw new IllegalStateException("PatchRegistry already resolved profile " + profile
                    + " before configureProfile(" + profileId + ")");
        }
        for (String[] source : new String[][] {
                {"system property " + PROFILE_PROPERTY, System.getProperty(PROFILE_PROPERTY)},
                {"environment variable " + PROFILE_ENV, System.getenv(PROFILE_ENV)}}) {
            String value = source[1];
            if (value != null && !value.isBlank() && !value.trim().equals(profileId)) {
                throw new IllegalStateException("Patcher profile " + profileId
                        + " disagrees with " + source[0] + "=" + value.trim());
            }
        }
        configuredProfile = profileId;
    }

    /** The resolved profile id, or {@code null} when no source provides one. */
    public static synchronized String profile() {
        resolve();
        return profile;
    }

    /** Whether bring-up skipping is active for this JVM. */
    public static synchronized boolean bringupActive() {
        resolve();
        return bringupActive;
    }

    /** The bring-up list that was loaded, or {@code null}. */
    public static synchronized Path bringupList() {
        resolve();
        return bringupList;
    }

    /** The loaded bring-up entries in file order (empty unless a list was loaded). */
    public static synchronized List<BringupEntry> bringupEntries() {
        resolve();
        return List.copyOf(bringupEntries.values());
    }

    /**
     * Returns {@code true} only in bring-up mode for ids listed in the bring-up list, and then
     * prints {@code BRINGUP_SKIP <id>} and counts the skip. The caller must not run the patch.
     */
    public static synchronized boolean bringupSkip(String patchId) {
        requirePatchId(patchId);
        resolve();
        queried.add(patchId);
        scopes.add(scopeOf(patchId));
        if (!bringupActive || !bringupEntries.containsKey(patchId)) {
            return false;
        }
        bringupSkipped++;
        System.out.println("BRINGUP_SKIP " + patchId);
        return true;
    }

    /**
     * Registers a patch as dropped for this jar. Every entry must be absent, otherwise this
     * throws. An entry is either a jar entry name ({@code com/x/Y.class}; a class name without
     * the {@code .class} suffix is accepted too) or a member of a class:
     * {@code owner#name} (no method or field with that name) or {@code owner#name(desc)ret}
     * (no method with that exact descriptor). A member is absent when its class is absent.
     */
    public static synchronized void dropped(
            String patchId, String jar, String... entriesThatMustBeAbsent) throws IOException {
        requirePatchId(patchId);
        if (entriesThatMustBeAbsent == null || entriesThatMustBeAbsent.length == 0) {
            throw new IllegalArgumentException(patchId
                    + ": a dropped patch must name at least one target that must be absent");
        }
        resolve();
        queried.add(patchId);
        scopes.add(scopeOf(patchId));
        List<String> present = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (String entry : entriesThatMustBeAbsent) {
                if (entry == null || entry.isBlank()) {
                    throw new IllegalArgumentException(patchId + ": empty dropped target");
                }
                if (targetPresent(zip, entry)) {
                    present.add(entry);
                }
            }
        }
        if (!present.isEmpty()) {
            throw new IllegalStateException("Patch " + patchId
                    + " is registered as dropped, but its target is still present in " + jar
                    + ": " + String.join(", ", present));
        }
        dropped++;
        System.out.println("PATCH_DROPPED " + patchId);
    }

    /** Records that a patch was applied (for patchers that do not use {@link #run}). */
    public static synchronized void applied(String patchId) {
        requirePatchId(patchId);
        resolve();
        queried.add(patchId);
        scopes.add(scopeOf(patchId));
        applied++;
        if (verbose()) {
            System.out.println("PATCH_APPLIED " + patchId);
        }
    }

    /**
     * Runs one patch: skips it when {@link #bringupSkip} says so, otherwise runs the body and
     * records it as applied. Failures propagate unchanged. When a patch fails outside bring-up
     * mode on a profile that has a bring-up list, the list of unfinished patches is printed to
     * stderr first.
     */
    public static void run(String patchId, IORunnable body) throws IOException {
        if (body == null) {
            throw new IllegalArgumentException(patchId + ": null patch body");
        }
        if (bringupSkip(patchId)) {
            return;
        }
        try {
            body.run();
        } catch (IOException | RuntimeException | Error failure) {
            printPendingOnFailure(patchId);
            throw failure;
        }
        applied(patchId);
    }

    /**
     * Prints {@code PATCH_SUMMARY applied=N dropped=N bringupSkipped=N} to stdout and, in
     * bring-up mode, warns on stderr about listed ids of the patchers seen in this JVM that were
     * never reached ({@code BRINGUP_UNUSED <id>}).
     */
    public static synchronized void printSummary() {
        resolve();
        if (bringupActive) {
            for (String id : bringupEntries.keySet()) {
                if (!queried.contains(id) && scopes.contains(scopeOf(id))) {
                    System.err.println("BRINGUP_UNUSED " + id);
                }
            }
        }
        System.out.println("PATCH_SUMMARY applied=" + applied + " dropped=" + dropped
                + " bringupSkipped=" + bringupSkipped);
    }

    /** Parses a bring-up list. Exposed for smokes and for build scripts written in Java. */
    public static Map<String, BringupEntry> parseBringupList(Path file) throws IOException {
        Map<String, BringupEntry> entries = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            String where = file + ":" + (index + 1);
            if (parts.length != 3) {
                throw new IllegalStateException(where
                        + ": expected '<patchId> | <owner> | <reason>', got: " + line);
            }
            String id = parts[0].strip();
            String owner = parts[1].strip();
            String reason = parts[2].strip();
            if (!PATCH_ID.matcher(id).matches()) {
                throw new IllegalStateException(where + ": invalid patch id '" + id + "'");
            }
            if (!OWNER.matcher(owner).matches()) {
                throw new IllegalStateException(where + ": invalid owner package '" + owner
                        + "' (expected P1..P9, optionally with a letter suffix such as P7a)");
            }
            if (reason.isEmpty()) {
                throw new IllegalStateException(where + ": missing reason for " + id);
            }
            BringupEntry previous = entries.putIfAbsent(
                    id, new BringupEntry(id, owner, reason, index + 1));
            if (previous != null) {
                throw new IllegalStateException(where + ": duplicate bring-up entry " + id
                        + " (first on line " + previous.line() + ")");
            }
        }
        return Collections.unmodifiableMap(entries);
    }

    /** Whether the given profile id may ever use bring-up mode. */
    public static boolean bringupEligible(String profileId) {
        return profileId != null && !"26.2".equals(profileId) && !"1.21.11".equals(profileId);
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        profile = firstNonBlank(configuredProfile, System.getProperty(PROFILE_PROPERTY),
                System.getenv(PROFILE_ENV), System.getenv(BUILD_PROFILE_ENV));
        boolean flag = truthy(System.getProperty(BRINGUP_PROPERTY))
                || truthy(System.getenv(BRINGUP_ENV));
        Path explicitList = pathOrNull(firstNonBlank(
                System.getProperty(BRINGUP_LIST_PROPERTY), System.getenv(BRINGUP_LIST_ENV)));
        if (flag && !bringupEligible(profile)) {
            System.err.println("BRINGUP_IGNORED profile=" + profile
                    + " (bring-up mode never applies to 26.2 or 1.21.11 or an unknown profile)");
        }
        bringupActive = flag && bringupEligible(profile);
        Path list = explicitList;
        if (list == null && bringupEligible(profile)) {
            list = locateBringupList(profile);
        }
        try {
            if (bringupActive) {
                if (list == null || !Files.isRegularFile(list)) {
                    throw new IllegalStateException("Bring-up mode for profile " + profile
                            + " needs port/tools/bringup/" + profile + ".txt (or "
                            + BRINGUP_LIST_ENV + "); not found"
                            + (list == null ? "" : ": " + list));
                }
                bringupList = list;
                bringupEntries = parseBringupList(list);
            } else if (list != null && Files.isRegularFile(list) && bringupEligible(profile)) {
                // Loaded only to explain failures; never used to skip anything.
                bringupList = list;
                bringupEntries = parseBringupList(list);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read bring-up list " + list, exception);
        }
        resolved = true;
    }

    private static void printPendingOnFailure(String failedId) {
        String currentProfile;
        Path list;
        Map<String, BringupEntry> entries;
        synchronized (PatchRegistry.class) {
            resolve();
            if (bringupActive) {
                // Listed ids never run, so a failure in bring-up mode is always unlisted.
                System.err.println("Patch " + failedId + " failed in bring-up mode for profile "
                        + profile + " and is not listed in " + bringupList);
                return;
            }
            if (pendingPrinted || bringupEntries.isEmpty()) {
                return;
            }
            pendingPrinted = true;
            currentProfile = profile;
            list = bringupList;
            entries = bringupEntries;
        }
        BringupEntry failed = entries.get(failedId);
        System.err.println("Patch " + failedId + " failed for profile " + currentProfile
                + (failed == null ? " and is not in the bring-up list"
                        : " (bring-up owner " + failed.owner() + ": " + failed.reason() + ")")
                + ". Unfinished patches listed in " + list + " (set " + BRINGUP_ENV
                + "=1 to skip them during bring-up):");
        for (BringupEntry entry : entries.values()) {
            System.err.println("BRINGUP_PENDING " + entry.patchId() + " | " + entry.owner()
                    + " | " + entry.reason());
        }
    }

    private static boolean targetPresent(ZipFile zip, String target) throws IOException {
        int member = target.indexOf('#');
        if (member < 0) {
            if (zip.getEntry(target) != null) {
                return true;
            }
            String last = target.substring(target.lastIndexOf('/') + 1);
            return !last.contains(".") && zip.getEntry(target + ".class") != null;
        }
        String owner = target.substring(0, member);
        String rest = target.substring(member + 1);
        if (owner.endsWith(".class")) {
            owner = owner.substring(0, owner.length() - ".class".length());
        }
        if (owner.isEmpty() || rest.isEmpty()) {
            throw new IllegalArgumentException("Invalid member target " + target);
        }
        var entry = zip.getEntry(owner + ".class");
        if (entry == null) {
            return false;
        }
        ClassNode node = new ClassNode();
        try (var input = zip.getInputStream(entry)) {
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
        }
        int paren = rest.indexOf('(');
        String name = paren < 0 ? rest : rest.substring(0, paren);
        String desc = paren < 0 ? null : rest.substring(paren);
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) {
                return true;
            }
        }
        if (desc == null) {
            for (FieldNode field : node.fields) {
                if (field.name.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Path locateBringupList(String profileId) {
        String relative = "port/tools/bringup/" + profileId + ".txt";
        List<Path> starts = new ArrayList<>();
        starts.add(Path.of("").toAbsolutePath());
        try {
            CodeSource source = PatchRegistry.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                starts.add(Path.of(source.getLocation().toURI()).toAbsolutePath());
            }
        } catch (URISyntaxException | IllegalArgumentException | SecurityException ignored) {
            // The working directory is still searched.
        }
        for (Path start : starts) {
            for (Path directory = start; directory != null; directory = directory.getParent()) {
                Path candidate = directory.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static void requirePatchId(String patchId) {
        if (patchId == null || !PATCH_ID.matcher(patchId).matches()) {
            throw new IllegalArgumentException("Invalid patch id: " + patchId);
        }
    }

    private static String scopeOf(String patchId) {
        int dot = patchId.indexOf('.');
        return dot < 0 ? patchId : patchId.substring(0, dot);
    }

    private static boolean verbose() {
        return truthy(System.getProperty(VERBOSE_PROPERTY)) || truthy(System.getenv(VERBOSE_ENV));
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1") || normalized.equals("true") || normalized.equals("yes");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Path pathOrNull(String value) {
        return value == null ? null : hostPath(value);
    }

    /**
     * Converts a Git Bash style absolute path ({@code /d/dir/file}) to a Windows path
     * ({@code D:/dir/file}) when running on Windows; other paths are returned unchanged.
     */
    static Path hostPath(String value) {
        String path = value.trim();
        if (java.io.File.separatorChar == '\\' && path.length() >= 3 && path.charAt(0) == '/'
                && Character.isLetter(path.charAt(1)) && path.charAt(2) == '/') {
            return Path.of(Character.toUpperCase(path.charAt(1)) + ":" + path.substring(2));
        }
        return Path.of(path);
    }
}
