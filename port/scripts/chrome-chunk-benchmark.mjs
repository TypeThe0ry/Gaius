#!/usr/bin/env node

import {spawn} from "node:child_process";
import {Buffer} from "node:buffer";
import {createHash} from "node:crypto";
import {createReadStream} from "node:fs";
import {mkdir, mkdtemp, readFile, rm, stat, writeFile} from "node:fs/promises";
import {createServer as createHttpServer} from "node:http";
import {createServer as createNetServer} from "node:net";
import {tmpdir} from "node:os";
import {basename, extname, isAbsolute, relative, resolve, sep} from "node:path";
import {fileURLToPath} from "node:url";
import {
  aggregateChromeProcessRss,
  combineMemorySnapshots,
  buildPerformanceEvidence,
  evaluateBrowserMemorySafety,
  evaluatePerformanceGates,
  evaluateGameplayAuthority,
  evaluateUncappedFramePacing,
  evaluateRuntimeInvariants,
  mergeMonotonicSamples,
  nearestRankPercentile,
  parsePsRssOutput,
  parseOptionsText,
  recoverFrameRingDelta,
  summarizeFrameTimes,
  summarizeMemoryTrend,
  summarizeNativeMemoryTrend,
  summarizeChromeProcessRssTrend,
  summarizeQueueTimeline,
  summarizeRuntimeInvariantTelemetry,
  summarizeScalarSamples,
  summarizeVisualOutput,
  summarizeWorldReadiness,
  validateWorkerHeartbeatTelemetry,
} from "./performance-metrics.mjs";

const args = process.argv.slice(2);
const smoke = args.includes("--smoke");
// A distance pin is a test fixture only.  The default benchmark observes the
// product's real Worker launch chain and must never rewrite its messages.
const pinWorkerDistance = args.includes("--pin-worker-distance");
const performanceContractPath = resolve(fileURLToPath(
  new URL("./performance-contract.json", import.meta.url),
));
const performanceContract = JSON.parse(await readFile(performanceContractPath, "utf8"));
const portRoot = resolve(fileURLToPath(new URL("../", import.meta.url)));
const repositoryRoot = resolve(portRoot, "..");
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const pathIsAbsolute = (value) => {
  const text = nativePath(value);
  return isAbsolute(text) || /^[A-Za-z]:[\\/]/.test(String(text || ""));
};
// Build scripts resolve relative paths from the repository root, while version
// profile values are historically relative to port/.  Normalize both forms
// explicitly so a child spawned from port/scripts cannot change the selected
// profile/build/dist/overlay by changing process.cwd().
const resolveRepositoryPortPath = (value) => {
  if (value == null || String(value).trim() === "") return null;
  const text = nativePath(String(value).trim());
  if (pathIsAbsolute(text)) return resolve(text);
  const slash = text.replaceAll("\\", "/").replace(/^\.\//, "");
  return slash === "port" || slash.startsWith("port/")
    ? resolve(repositoryRoot, slash)
    : resolve(portRoot, slash);
};
const normalizedPath = (value) => String(value || "")
  .replaceAll("\\", "/")
  .replace(/^\.\/+/, "");
const pathInside = (candidate, root) => {
  const candidateText = normalizedPath(resolve(candidate));
  const rootText = normalizedPath(resolve(root)).replace(/\/$/, "");
  const comparableCandidate = process.platform === "win32"
    ? candidateText.toLowerCase() : candidateText;
  const comparableRoot = process.platform === "win32" ? rootText.toLowerCase() : rootText;
  return comparableCandidate === comparableRoot
    || comparableCandidate.startsWith(comparableRoot + "/");
};
const relativePortPath = (candidate) => normalizedPath(relative(portRoot, resolve(candidate)));
const portConfig = JSON.parse(await readFile(resolve(portRoot, "config.json"), "utf8"));
const isolatedEnvironment = [
  "GAIUS_VERSION_PROFILE_PATH",
  "GAIUS_BUILD_ROOT",
  "GAIUS_DIST_DIRECTORY",
  "GAIUS_OVERLAY_DIRECTORY",
].some((name) => String(process.env[name] || "").trim() !== "");
const versionProfileRelative = nativePath(
  process.env.GAIUS_VERSION_PROFILE_PATH || String(portConfig.versionProfile || ""),
);
const activeVersionProfilePath = resolveRepositoryPortPath(versionProfileRelative);
if (!activeVersionProfilePath || !pathInside(activeVersionProfilePath, portRoot)
    || resolve(activeVersionProfilePath) === resolve(portRoot)) {
  throw new Error("The active version profile escaped the port directory");
}
const activeVersionProfileBytes = await readFile(activeVersionProfilePath);
const activeVersionProfile = JSON.parse(activeVersionProfileBytes.toString("utf8"));
const activeVersionProfileSha256 = createHash("sha256").update(activeVersionProfileBytes).digest("hex");
const activeProfileId = String(activeVersionProfile.id || "");
const activeProfilePath = relativePortPath(activeVersionProfilePath);
if (!activeProfileId || !/^versions\/[^/]+\.json$/i.test(activeProfilePath)) {
  throw new Error(`The active version profile path is invalid: ${activeProfilePath}`);
}
const activeStorage = activeVersionProfile.storage || {};
const activeStorageConfig = Object.freeze({
  schema: Number(activeStorage.schema),
  databaseName: String(activeStorage.databaseName || ""),
  prefix: String(activeStorage.prefix || ""),
  opfsDirectory: String(activeStorage.opfsDirectory || ""),
});
const activeWorldgenTelemetryMode = String(
  activeVersionProfile.worldgenTelemetryMode || "",
).trim();
if (activeWorldgenTelemetryMode !== "task-pulsed"
    && activeWorldgenTelemetryMode !== "checkpoint-only") {
  throw new Error(
    `The active version profile has an invalid worldgenTelemetryMode: `
      + `${JSON.stringify(activeVersionProfile.worldgenTelemetryMode)}`,
  );
}
const activeStorageProfileId = String(activeVersionProfile.id || "");
const activeStorageWorldVersion = Number(activeVersionProfile.worldVersion);
if (!activeStorageProfileId || !Number.isSafeInteger(activeStorageWorldVersion) ||
    activeStorageWorldVersion <= 0 || activeStorageConfig.schema !== 2 ||
    activeStorageConfig.databaseName !== `gaius-fs-v2-${activeStorageProfileId}` ||
    activeStorageConfig.prefix !== `gaius.fs.v2:${activeStorageProfileId}:` ||
    activeStorageConfig.opfsDirectory !== `regions-v2-${activeStorageProfileId}`) {
  throw new Error(
    `The active version profile has an invalid schema-2 storage namespace: ${
      JSON.stringify(activeVersionProfile.storage)}`,
  );
}
const activeStoragePrefix = activeStorageConfig.prefix;
// The FAST 6/4 release contract is the 26.2 overlay contract. Keep older
// profiles observable, but never turn an accidental 1.21 8/6 natural launch
// into release evidence.
const releaseDistanceProfileCompatible = activeStorageProfileId === "26.2";
const profileName = value("--profile", "traversal-6-4");
const benchmarkProfile = performanceContract.profiles?.[profileName];
if (!benchmarkProfile) {
  throw new Error(
    `Unknown benchmark profile ${JSON.stringify(profileName)}; expected one of `
      + Object.keys(performanceContract.profiles || {}).join(", "),
  );
}
const expectedRenderDistance = Number(benchmarkProfile.renderDistance);
const expectedSimulationDistance = Number(benchmarkProfile.simulationDistance);
const expectedDistanceLabel = `${expectedRenderDistance}:${expectedSimulationDistance}`;
const environmentContract = performanceContract.environment || {};
const measurementContract = performanceContract.measurement || {};
const startupContract = performanceContract.startup || {};
const browserMemoryContract = performanceContract.browserMemory || {};
const heapMemoryContract = performanceContract.heapMemory || {};
const processRssContract = performanceContract.processRss || {};
const visualOutputContract = performanceContract.visualOutput || {};
const heartbeatContract = performanceContract.heartbeat || {};
const runtimeInvariantContract = performanceContract.runtimeInvariants || {};
const runtimeInvariantContractForProfile = {
  ...runtimeInvariantContract,
  worldgen: {
    ...(runtimeInvariantContract.worldgen || {}),
    telemetryMode: activeWorldgenTelemetryMode,
    worldgenTelemetryMode: activeWorldgenTelemetryMode,
  },
};
const gameplayAuthorityContract = performanceContract.gameplayAuthority || {};
const activeProtocolVersion = Number(activeVersionProfile.protocolVersion);
const activeGameplayProtocol = gameplayAuthorityContract.protocols?.[String(activeProtocolVersion)];
if (!Number.isSafeInteger(activeProtocolVersion) || !activeGameplayProtocol?.packetIds) {
  throw new Error(`No gameplay authority protocol table exists for ${activeProtocolVersion}`);
}
const activeGameplayPacketIds = Object.fromEntries(Object.entries(
  activeGameplayProtocol.packetIds,
).map(([name, id]) => {
  const numeric = Number(id);
  if (!Number.isSafeInteger(numeric) || numeric < 0) {
    throw new Error(`Invalid gameplay packet id ${name}=${JSON.stringify(id)}`);
  }
  return [name, numeric];
}));
const activeAirBlockStateId = Number(activeGameplayProtocol.airBlockStateId);
if (!Number.isSafeInteger(activeAirBlockStateId) || activeAirBlockStateId < 0) {
  throw new Error(`Invalid air block state id ${activeGameplayProtocol.airBlockStateId}`);
}
const resolvedGameplayAuthorityContract = {
  ...gameplayAuthorityContract,
  protocolVersion: activeProtocolVersion,
  packetIds: activeGameplayPacketIds,
  airBlockStateId: activeAirBlockStateId,
};
const frameSampleCapacity = Math.max(
  1024,
  Math.min(65536, Number(measurementContract.frameSampleCapacity || 65536)),
);
const heartbeatIntervalMillis = Math.max(
  50,
  Number(measurementContract.heartbeatIntervalMs || 50),
);
const postGcFinalWindows = Math.max(
  2,
  Number(browserMemoryContract.postGcFinalWindows || 4),
);
const longTaskCapacity = Math.max(
  64,
  Math.min(16384, Number(measurementContract.longTaskCapacity || 4096)),
);
const runtimeInvariantSampleCapacity = Math.max(
  64,
  Math.min(16384, Number(measurementContract.runtimeInvariantSampleCapacity || 4096)),
);
const defaultChromeBinary = process.platform === "win32"
  ? "C:/Program Files/Google/Chrome/Application/chrome.exe"
  : process.platform === "darwin"
    ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    : "google-chrome";
const chromeBinary = nativePath(value("--chrome", process.env.GAIUS_CHROME_BIN || defaultChromeBinary));
if (args.includes("--allow-attached-input")) {
  throw new Error(
    "--allow-attached-input has been removed; this benchmark never injects input into attached Chrome",
  );
}
const attachPortRequested = args.includes("--attach-port");
const attachPort = integer("--attach-port", 0);
if (attachPortRequested || attachPort > 0) {
  throw new Error(
    "--attach-port is disabled: this benchmark requires synthetic input and can only launch its own isolated Chrome",
  );
}
const headless = args.includes("--headless");
const keepChrome = args.includes("--keep-chrome");
const warmupMillis = duration(
  "--warmup-seconds",
  smoke ? 5 : Number(benchmarkProfile.warmupMs || measurementContract.warmupMs) / 1000,
);
const performanceMillis = duration(
  "--performance-minutes",
  smoke ? 10 / 60 : Number(
    benchmarkProfile.fpsWindowMs
      || benchmarkProfile.durationMs
      || measurementContract.sampleMs,
  ) / 60_000,
  60_000,
);
const heapMillis = duration(
  "--heap-minutes",
  smoke || !benchmarkProfile.gates?.memory ? 0 : Number(
    benchmarkProfile.soakMs || measurementContract.soakMs,
  ) / 60_000,
  60_000,
);
const heapIntervalMillis = duration(
  "--heap-interval-minutes",
  smoke ? 5 / 60 : Number(measurementContract.postGcIntervalMs || 300_000) / 60_000,
  60_000,
);
const heapSampleMillis = Math.max(
  1000,
  integer("--heap-sample-millis", measurementContract.heapIntervalMs || 5000),
);
const processRssSampleMillis = Math.max(
  1000,
  Number(processRssContract.sampleIntervalMs || 15_000),
);
const cleanupMillis = duration(
  "--cleanup-seconds",
  smoke ? 5 : Number(measurementContract.cleanupMs || 30_000) / 1000,
);
const sampleMillis = Math.max(
  250,
  integer("--sample-millis", measurementContract.sampleIntervalMs || 500),
);
const frameMeasurementMillis = Math.max(performanceMillis, heapMillis);
const cdpCommandTimeoutMillis = Math.max(
  1000,
  Number(measurementContract.cdpCommandTimeoutMs || 15_000),
);
const continuousVisualIntervalMillis = Math.max(
  1000,
  Number(measurementContract.continuousVisualIntervalMs || 10_000),
);
const startupTimeoutMillis = duration(
  "--startup-minutes",
  Number(startupContract.timeoutMs || 900_000) / 60_000,
  60_000,
);
const playerName = value("--player", "GaiusBench");
const configuredBuildRoot = process.env.GAIUS_BUILD_ROOT
  ? resolveRepositoryPortPath(process.env.GAIUS_BUILD_ROOT)
  : (isolatedEnvironment ? resolve(portRoot, "target", activeProfileId) : resolve(portRoot, "target"));
const configuredOverlayDirectory = process.env.GAIUS_OVERLAY_DIRECTORY
  ? resolveRepositoryPortPath(process.env.GAIUS_OVERLAY_DIRECTORY)
  : (isolatedEnvironment
    ? resolve(portRoot, "work", "overlays", activeProfileId)
    : resolve(portRoot, "work", "overlays"));
const isolatedOutputRoot = isolatedEnvironment
  ? configuredBuildRoot
  : resolve(portRoot, "target");
const outputPath = resolveRepositoryPortPath(value(
  "--output",
  resolve(isolatedOutputRoot, "chrome-chunk-benchmark.json"),
));
const visualOutputDirectory = outputPath + ".frames";
const visualCapturesPerPhase = Math.max(
  1,
  Math.min(8, Number(visualOutputContract.capturesPerPhase || 3)),
);
const visualCaptureIntervalMillis = Math.max(
  50,
  Number(visualOutputContract.captureIntervalMs || 200),
);
const visualSampleWidth = Math.max(
  16,
  Math.min(256, Number(visualOutputContract.sampleWidth || 80)),
);
const visualSampleHeight = Math.max(
  9,
  Math.min(144, Number(visualOutputContract.sampleHeight || 45)),
);
const startupMaximumFrameGapMillis = Math.max(
  16,
  Number(startupContract.maximumTerrainFrameGapMs || 100),
);
const startupMinimumVisualSamples = Math.max(
  1,
  Number(startupContract.minimumVisualSamples || 3),
);
// The visual readiness window must cover the whole interactive startup budget:
// terrain can legitimately appear up to newWorldInteractiveMsMax after world
// entry (worldgen + first chunk meshes), so keep capturing until three valid
// terrain samples arrive or roughly three interactive budgets have elapsed.
// This replaces a fixed ~5s capture burst that could close before the first
// terrain meshes were presented and then never resume.
const startupVisualCaptureAttempts = Math.max(
  24,
  Math.ceil(
    Number(startupContract.newWorldInteractiveMsMax || 15_000) * 3
      / Math.max(100, visualCaptureIntervalMillis),
  ),
);
const startupTerrainPollMillis = Math.max(
  16,
  Number(startupContract.terrainFrameIntervalMs || 50),
);
const explicitUrl = value("--url", "");
const configuredDistRoot = process.env.GAIUS_DIST_DIRECTORY
  ? resolveRepositoryPortPath(process.env.GAIUS_DIST_DIRECTORY)
  : (isolatedEnvironment
    ? resolve(portRoot, "web", "dist", activeProfileId)
    : fileURLToPath(new URL("../web/dist/", import.meta.url)));
const distRoot = resolve(configuredDistRoot);
if (isolatedEnvironment && basename(distRoot) !== activeProfileId) {
  throw new Error(
    `An isolated benchmark must use a profile-scoped dist basename ${JSON.stringify(activeProfileId)}; got ${JSON.stringify(basename(distRoot))}`,
  );
}
const releaseManifestPath = resolve(distRoot, "Gaius.manifest.json");

async function readBenchmarkBuildIdentity() {
  const bytes = await readFile(releaseManifestPath);
  const manifest = JSON.parse(bytes.toString("utf8"));
  const nestedProfile = manifest.buildIdentity?.profile;
  const expectedProtocolVersion = Number(activeVersionProfile.protocolVersion);
  const expectedWorldVersion = Number(activeVersionProfile.worldVersion);
  const manifestProfilePath = normalizedPath(manifest.profilePath);
  const nestedProfilePath = normalizedPath(nestedProfile?.path);
  const profileFailures = [];
  if (String(manifest.profile || "") !== activeProfileId) {
    profileFailures.push(
      `manifest.profile=${JSON.stringify(manifest.profile)} expected ${JSON.stringify(activeProfileId)}`,
    );
  }
  if (manifestProfilePath !== activeProfilePath) {
    profileFailures.push(
      `manifest.profilePath=${JSON.stringify(manifest.profilePath)} expected ${JSON.stringify(activeProfilePath)}`,
    );
  }
  if (!nestedProfile || typeof nestedProfile !== "object") {
    profileFailures.push("manifest.buildIdentity.profile is missing");
  } else {
    if (String(nestedProfile.id || "") !== activeProfileId) {
      profileFailures.push(
        `manifest.buildIdentity.profile.id=${JSON.stringify(nestedProfile.id)} expected ${JSON.stringify(activeProfileId)}`,
      );
    }
    if (nestedProfilePath !== activeProfilePath) {
      profileFailures.push(
        `manifest.buildIdentity.profile.path=${JSON.stringify(nestedProfile.path)} expected ${JSON.stringify(activeProfilePath)}`,
      );
    }
    if (Number(nestedProfile.protocolVersion) !== expectedProtocolVersion) {
      profileFailures.push(
        `manifest.buildIdentity.profile.protocolVersion=${JSON.stringify(nestedProfile.protocolVersion)} expected ${expectedProtocolVersion}`,
      );
    }
    if (Number(nestedProfile.worldVersion) !== expectedWorldVersion) {
      profileFailures.push(
        `manifest.buildIdentity.profile.worldVersion=${JSON.stringify(nestedProfile.worldVersion)} expected ${expectedWorldVersion}`,
      );
    }
    if (String(nestedProfile.sha256 || "").toLowerCase() !== activeVersionProfileSha256) {
      profileFailures.push(
        `manifest.buildIdentity.profile.sha256=${JSON.stringify(nestedProfile.sha256)} expected ${activeVersionProfileSha256}`,
      );
    }
  }
  if (profileFailures.length > 0) {
    throw new Error(
      `Gaius.manifest.json profile identity does not match the active profile: ${profileFailures.join("; ")}`,
    );
  }
  const compatibilitySha256 = String(manifest.buildIdentity?.compatibilitySha256 || "");
  const artifactCompatibilities = [
    manifest.classesJs?.build?.compatibilitySha256,
    manifest.singleplayerServerJs?.build?.compatibilitySha256,
    manifest.singleplayerWorkerBootstrap?.build?.compatibilitySha256,
    manifest.wasmHotpath?.build?.compatibilitySha256,
  ].filter(Boolean).map(String);
  return {
    manifestPath: releaseManifestPath,
    manifestSha256: createHash("sha256").update(bytes).digest("hex"),
    compatibilitySha256,
    artifactCompatibilities,
    coherent: /^[a-f0-9]{64}$/i.test(compatibilitySha256)
      && artifactCompatibilities.length === 4
      && artifactCompatibilities.every((entry) => entry === compatibilitySha256),
    profile: manifest.profile || null,
    profilePath: manifest.profilePath || null,
    nestedProfile: nestedProfile || null,
    distRoot,
    overlayDirectory: configuredOverlayDirectory,
  };
}

const benchmarkBuildIdentity = await readBenchmarkBuildIdentity();
if (!benchmarkBuildIdentity.coherent) {
  throw new Error("Gaius.manifest.json does not describe one coherent benchmark build");
}
const benchmarkOptionsText = [
  `version:${Number(activeVersionProfile.worldVersion)}`,
  "autoJump:false",
  "operatorItemsTab:true",
  // Apply the graphics preset before the distance overrides: 26.2's preset
  // bundles its own render/simulation distance, so applying it after those
  // lines overwrote the seeded 6/4 with the preset's 8/6.
  `graphicsPreset:${JSON.stringify(String(environmentContract.graphicsPreset || "fast"))}`,
  `renderDistance:${expectedRenderDistance}`,
  `simulationDistance:${expectedSimulationDistance}`,
  "entityDistanceScaling:0.5",
  `maxFps:${Number(environmentContract.maxFps || 260)}`,
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
  "onboardAccessibility:false",
].join("\n") + "\n";
const benchmarkOptionsBase64 = Buffer.from(benchmarkOptionsText, "utf8").toString("base64");

if (args.includes("--help")) {
  console.log([
    "Usage: node port/scripts/chrome-chunk-benchmark.mjs [options]",
    "",
    "Default acceptance run:",
    "  Chrome, uncapped, traversal-6-4 profile",
    "  30 second warmup and 5 minute new-chunk traversal",
    "",
    "Options:",
    "  --url URL                     Use an existing Gaius URL instead of serving port/web/dist",
    "  --profile NAME                Contract profile (default traversal-6-4)",
    "  --attach-port PORT            Rejected: this benchmark requires an isolated Chrome it launches itself",
    "  --headless                    Launch Chrome in headless mode",
    "  --warmup-seconds N            Override the 30 second warmup",
    "  --performance-minutes N       Override the 5 minute performance phase",
    "  --heap-minutes N              Override concurrent memory-observation duration",
    "  --heap-interval-minutes N     Override the 5 minute forced-GC interval",
    "  --heap-sample-millis N        Override the 5 second heap/resource sample interval",
    "  --cleanup-seconds N           Post-exit cleanup/GC observation (default 30)",
    "  --sample-millis N             Override the 500 ms performance sample interval",
    "  --output PATH                 JSON report path",
    "  --smoke                       Short plumbing check without hard performance thresholds",
    "  --pin-worker-distance         Diagnostic-only Worker distance harness override (never release evidence)",
    "  --print-config                Print resolved benchmark configuration and exit",
  ].join("\n"));
  process.exit(0);
}
if (args.includes("--print-config")) {
  console.log(JSON.stringify({
    mode: pinWorkerDistance
      ? (smoke ? "smoke-pin-non-gating" : "diagnostic-pin-non-gating")
      : (smoke
        ? "smoke-non-gating"
        : (benchmarkProfile.releaseEvidence === true && releaseDistanceProfileCompatible
          ? "release-gating" : "diagnostic-stress")),
    gating: !smoke && !pinWorkerDistance && releaseDistanceProfileCompatible
      && benchmarkProfile.releaseEvidence === true,
    releaseEvidence: !pinWorkerDistance && releaseDistanceProfileCompatible
      && benchmarkProfile.releaseEvidence === true,
    workerDistanceMode: pinWorkerDistance ? "harness-pin-diagnostic" : "natural-observation",
    workerDistancePin: pinWorkerDistance,
    chromeBinary,
    attachPort,
    attachPortRequested,
    attachMode: "disabled-input-required",
    isolatedEnvironment,
    buildRoot: configuredBuildRoot,
    distRoot,
    overlayDirectory: configuredOverlayDirectory,
    headless,
    warmupMillis,
    performanceMillis,
    frameMeasurementMillis,
    heapMillis,
    heapIntervalMillis,
    heapSampleMillis,
    processRssSampleMillis,
    processRssContract,
    heapMemoryContract,
    visualOutputContract,
    startupContract,
    activeVersionProfile: {
      id: activeVersionProfile.id,
      protocolVersion: activeProtocolVersion,
      worldVersion: activeVersionProfile.worldVersion,
      worldgenTelemetryMode: activeWorldgenTelemetryMode,
      storage: activeStorageConfig,
      storageSchema: activeStorageConfig.schema,
      storageDatabaseName: activeStorageConfig.databaseName,
      storagePrefix: activeStorageConfig.prefix,
      storageOpfsDirectory: activeStorageConfig.opfsDirectory,
      gameplayPacketIds: activeGameplayPacketIds,
      airBlockStateId: activeAirBlockStateId,
    },
    visualOutputDirectory,
    cleanupMillis,
    sampleMillis,
    startupTimeoutMillis,
    playerName,
    outputPath,
    explicitUrl: explicitUrl || null,
    contractPath: performanceContractPath,
    profileName,
    profile: benchmarkProfile,
    expectedRenderDistance,
    expectedSimulationDistance,
    workerDistanceContract: {
      expectedStartDistance: expectedDistanceLabel,
      effectiveDistanceModel: "min(client-options-preference,worker-server-distance)",
      proof: pinWorkerDistance
        ? "diagnostic harness override plus separately captured raw start message"
        : "profile-gate observation wrapper plus captured raw start message",
      mode: pinWorkerDistance ? "harness-pin-diagnostic" : "natural-observation",
      releaseEligible: !pinWorkerDistance && releaseDistanceProfileCompatible,
      releaseTargetProfile: "26.2",
      activeProfileId: activeStorageProfileId,
      storage: activeStorageConfig,
    },
    requestedFrameLimit: environmentContract.maxFpsLabel || "Unlimited",
    verifiedUncapped: null,
    uncappedEvidence: environmentContract.uncappedEvidence || null,
    frameSampleCapacity,
    heartbeatIntervalMillis,
    postGcFinalWindows,
    longTaskCapacity,
    runtimeInvariantSampleCapacity,
    worldgenTelemetryMode: activeWorldgenTelemetryMode,
    buildIdentity: benchmarkBuildIdentity,
  }, null, 2));
  process.exit(0);
}

if (benchmarkProfile.route !== "singleplayer") {
  const reason = `Profile ${profileName} uses unsupported route ${benchmarkProfile.route}; `
    + "no multiplayer/RelayNode benchmark driver is implemented";
  const unsupported = {
    schemaVersion: performanceContract.schemaVersion,
    generatedAt: new Date().toISOString(),
    passed: false,
    verdict: "inconclusive",
    mode: smoke
      ? "smoke-non-gating"
      : (pinWorkerDistance
        ? "diagnostic-pin-non-gating"
        : (benchmarkProfile.releaseEvidence === true && releaseDistanceProfileCompatible
          ? "release-gating" : "diagnostic-stress")),
    gating: false,
    releaseEvidence: false,
    profileName,
    route: benchmarkProfile.route,
    routeSupported: false,
    buildIdentity: benchmarkBuildIdentity,
    reason,
  };
  await mkdir(resolve(outputPath, ".."), {recursive: true});
  await writeFile(outputPath, JSON.stringify(unsupported, null, 2) + "\n");
  console.error(reason);
  process.exit(1);
}

function value(name, fallback) {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] != null ? String(args[index + 1]) : fallback;
}

function number(name, fallback) {
  const parsed = Number(value(name, fallback));
  return Number.isFinite(parsed) ? parsed : Number(fallback);
}

function integer(name, fallback) {
  return Math.floor(number(name, fallback));
}

function duration(name, fallback, scale = 1000) {
  return Math.max(0, number(name, fallback) * scale);
}

function sleep(millis) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, millis));
}

async function runExternalSmokeSuite() {
  const required = [...new Set([
    ...Object.values(runtimeInvariantContract)
      .flatMap((component) => component?.externalSmokeRequired || []),
    ...(startupContract.externalSmokeRequired || []),
    ...(processRssContract.externalSmokeRequired || []),
    ...(visualOutputContract.externalSmokeRequired || []),
  ])];
  if (smoke || required.length === 0) {
    return {
      verdict: "not-required",
      required,
      results: [],
      note: "External invariant smokes are mandatory only for strict release evidence.",
    };
  }
  const scriptsRoot = resolve(performanceContractPath, "..");
  const results = [];
  for (const name of required) {
    const scriptPath = resolve(scriptsRoot, String(name));
    if (!scriptPath.startsWith(scriptsRoot + sep)) {
      results.push({name, verdict: "fail", reason: "smoke path escaped port/scripts"});
      continue;
    }
    let source;
    try {
      source = await readFile(scriptPath);
    } catch (error) {
      results.push({
        name,
        verdict: "fail",
        reason: String(error && (error.message || error) || error),
      });
      continue;
    }
    const sourceSha256 = createHash("sha256").update(source).digest("hex");
    const command = String(name).endsWith(".sh") ? "bash" : process.execPath;
    const commandArgs = [scriptPath];
    const startedAt = Date.now();
    const execution = await new Promise((resolveExecution) => {
      const child = spawn(command, commandArgs, {
        cwd: resolve(scriptsRoot, "../.."),
        stdio: ["ignore", "pipe", "pipe"],
      });
      let output = "";
      let settled = false;
      let timedOut = false;
      const append = (chunk) => {
        output = (output + String(chunk)).slice(-16_000);
      };
      child.stdout.on("data", append);
      child.stderr.on("data", append);
      const finish = (exitCode, signal, error = null) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        resolveExecution({exitCode, signal, timedOut, output, error});
      };
      const timeout = setTimeout(() => {
        timedOut = true;
        child.kill("SIGTERM");
        setTimeout(() => child.kill("SIGKILL"), 1000).unref();
      }, 120_000);
      child.on("error", (error) => finish(null, null, String(error && error.message || error)));
      child.on("close", (exitCode, signal) => finish(exitCode, signal));
    });
    results.push({
      name,
      sourceSha256,
      durationMs: Date.now() - startedAt,
      exitCode: execution.exitCode,
      signal: execution.signal,
      timedOut: execution.timedOut,
      output: execution.output,
      error: execution.error,
      verdict: execution.exitCode === 0 && !execution.timedOut ? "pass" : "fail",
    });
  }
  return {
    verdict: results.length === required.length
        && results.every((entry) => entry.verdict === "pass")
      ? "pass" : "fail",
    required,
    results,
  };
}

function combineBrowserMemory(...snapshots) {
  const fields = [
    ...(browserMemoryContract.liveFields || []),
    ...(browserMemoryContract.counterFields || []),
  ];
  return combineMemorySnapshots(snapshots, fields);
}

function combineBrowserMemorySafety(page, worker) {
  if (!page || !worker) return null;
  const aggregateLimits = browserMemoryContract.aggregateLimits || {};
  const maxLiveBytes = Number(aggregateLimits.maxLiveBytes);
  const maxTemporaryBytes = Number(aggregateLimits.maxTemporaryBytes);
  if (!Number.isFinite(maxLiveBytes) || maxLiveBytes <= 0
      || !Number.isFinite(maxTemporaryBytes) || maxTemporaryBytes <= 0) {
    return null;
  }
  const combined = combineBrowserMemory(page, worker);
  return combined
    ? {...combined, maxLiveBytes, maxTemporaryBytes}
    : null;
}

async function withTimeout(promise, millis, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(label + " timed out after " + millis + " ms")), millis);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function readProcessRssByPid(pids) {
  const requestedPids = [...new Set((Array.isArray(pids) ? pids : [])
    .map(Number)
    .filter((pid) => Number.isSafeInteger(pid) && pid > 0))];
  if (requestedPids.length === 0) {
    throw new Error("no Chrome process IDs were available for RSS query");
  }
  const isWindows = process.platform === "win32";
  const command = isWindows ? "powershell.exe" : "ps";
  const commandArgs = isWindows
    ? [
      "-NoLogo",
      "-NoProfile",
      "-NonInteractive",
      "-ExecutionPolicy", "Bypass",
      "-Command",
      `$ids = @(${requestedPids.join(",")}); `
        + "Get-Process -Id $ids -ErrorAction SilentlyContinue "
        + "| ForEach-Object { '{0} {1}' -f $_.Id, "
        + "[math]::Floor($_.WorkingSet64 / 1KB) }",
    ]
    : ["-o", "pid=,rss=", "-p", requestedPids.join(",")];
  const queryName = isWindows ? "PowerShell WorkingSet64" : "ps RSS";
  const output = await new Promise((resolveRead, rejectRead) => {
    const child = spawn(command, commandArgs, {stdio: ["ignore", "pipe", "pipe"]});
    let stdout = "";
    let stderr = "";
    let settled = false;
    const finish = (error, value = null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (error) rejectRead(error);
      else resolveRead(value);
    };
    const append = (current, chunk) => (current + String(chunk)).slice(-1024 * 1024);
    child.stdout.on("data", (chunk) => {
      stdout = append(stdout, chunk);
    });
    child.stderr.on("data", (chunk) => {
      stderr = append(stderr, chunk);
    });
    child.on("error", (error) => finish(error));
    child.on("close", (code, signal) => {
      if (code === 0) {
        finish(null, stdout);
      } else {
        finish(new Error(
          `${queryName} query exited with code ${code} signal ${signal || "none"}: ${stderr.trim()}`,
        ));
      }
    });
    const timeout = setTimeout(() => {
      if (isWindows) child.kill();
      else child.kill("SIGTERM");
      finish(new Error(`${queryName} query timed out after 5000 ms`));
    }, 5000);
  });
  return parsePsRssOutput(output);
}

async function collectChromeProcessRss(browserSession, unavailableReason = null) {
  if (!browserSession) {
    return {
      available: false,
      error: unavailableReason || "browser CDP session is unavailable",
      totalRssBytes: null,
      processCount: null,
      missingPids: [],
      byType: {},
      processes: [],
    };
  }
  let lastUnavailable = null;
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      const response = await withTimeout(
        browserSession.send("SystemInfo.getProcessInfo"),
        5000,
        "SystemInfo.getProcessInfo",
      );
      const processInfo = Array.isArray(response.processInfo) ? response.processInfo : [];
      const pids = processInfo.map((process) => Number(process?.id));
      const rssByPid = await readProcessRssByPid(pids);
      const measurement = aggregateChromeProcessRss(processInfo, rssByPid);
      if (measurement.available) return {...measurement, attempts: attempt};
      lastUnavailable = {...measurement, attempts: attempt};
    } catch (error) {
      lastUnavailable = {
        available: false,
        error: String(error && (error.message || error) || error),
        totalRssBytes: null,
        processCount: null,
        missingPids: [],
        byType: {},
        processes: [],
        attempts: attempt,
      };
    }
    if (attempt === 1) await sleep(50);
  }
  return lastUnavailable;
}

async function collectChromeProcessRssTimeline(
  browserSession,
  durationMillis,
  sampleIntervalMillis,
  phase,
  unavailableReason = null,
) {
  const samples = [];
  const startedAt = Date.now();
  const deadline = startedAt + Math.max(0, durationMillis);
  let nextSampleAt = startedAt;
  while (true) {
    const at = Date.now();
    const measurement = await collectChromeProcessRss(browserSession, unavailableReason);
    samples.push({
      phase,
      at,
      atMillis: at - startedAt,
      durationMillis: Date.now() - at,
      ...measurement,
    });
    if (Date.now() >= deadline) break;
    nextSampleAt += sampleIntervalMillis;
    await sleep(Math.max(0, Math.min(nextSampleAt, deadline) - Date.now()));
  }
  return samples;
}

async function freePort() {
  const server = createNetServer();
  await new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  const port = server.address().port;
  await new Promise((resolveClose) => server.close(resolveClose));
  return port;
}

async function waitForJson(url, timeoutMillis) {
  const deadline = Date.now() + timeoutMillis;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return await response.json();
      lastError = new Error(response.status + " " + response.statusText);
    } catch (error) {
      lastError = error;
    }
    await sleep(100);
  }
  throw new Error("Timed out waiting for " + url + ": " + String(lastError || "no response"));
}

class CdpSession {
  constructor(url) {
    this.socket = new WebSocket(url);
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Map();
  }

  async open() {
    if (this.socket.readyState !== WebSocket.OPEN) {
      await new Promise((resolveOpen, rejectOpen) => {
        const timeout = setTimeout(() => {
          cleanup();
          rejectOpen(new Error(
            `Chrome DevTools WebSocket open timed out after ${cdpCommandTimeoutMillis} ms`,
          ));
        }, cdpCommandTimeoutMillis);
        const onOpen = () => {
          cleanup();
          resolveOpen();
        };
        const onError = (event) => {
          cleanup();
          rejectOpen(event.error || new Error("Chrome DevTools WebSocket failed"));
        };
        const cleanup = () => {
          clearTimeout(timeout);
          this.socket.removeEventListener("open", onOpen);
          this.socket.removeEventListener("error", onError);
        };
        this.socket.addEventListener("open", onOpen);
        this.socket.addEventListener("error", onError);
      });
    }
    this.socket.addEventListener("message", (event) => this.handleMessage(event.data));
    this.socket.addEventListener("close", () => {
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timeout);
        pending.reject(new Error("Chrome DevTools WebSocket closed"));
      }
      this.pending.clear();
    });
  }

  handleMessage(raw) {
    const message = JSON.parse(String(raw));
    if (message.id != null) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      clearTimeout(pending.timeout);
      if (message.error) {
        pending.reject(new Error(pending.method + ": " + message.error.message));
      } else {
        pending.resolve(message.result || {});
      }
      return;
    }
    for (const listener of this.listeners.get(message.method) || []) {
      listener(message.params || {});
    }
  }

  send(method, params = {}, sessionId = null) {
    const id = this.nextId++;
    return new Promise((resolveSend, rejectSend) => {
      const timeout = setTimeout(() => {
        if (!this.pending.delete(id)) return;
        rejectSend(new Error(
          `${method} timed out after ${cdpCommandTimeoutMillis} ms`,
        ));
      }, cdpCommandTimeoutMillis);
      this.pending.set(id, {method, resolve: resolveSend, reject: rejectSend, timeout});
      const message = {id, method, params};
      if (sessionId) message.sessionId = sessionId;
      try {
        this.socket.send(JSON.stringify(message));
      } catch (error) {
        this.pending.delete(id);
        clearTimeout(timeout);
        rejectSend(error);
      }
    });
  }

  on(method, listener) {
    const listeners = this.listeners.get(method) || [];
    listeners.push(listener);
    this.listeners.set(method, listeners);
  }

  close() {
    try {
      this.socket.close();
    } catch (ignored) {
    }
  }
}

async function evaluate(session, expression, timeoutMillis = 10_000) {
  const result = await withTimeout(session.send("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
  }), timeoutMillis, "Runtime.evaluate");
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description
      || result.exceptionDetails.text
      || "Runtime.evaluate failed");
  }
  return result.result?.value;
}

async function waitFor(session, expression, timeoutMillis, label) {
  const deadline = Date.now() + timeoutMillis;
  let lastError;
  while (Date.now() < deadline) {
    try {
      if (await evaluate(session, expression, 25000)) return;
    } catch (error) {
      lastError = error;
    }
    const fatal = stickyFatalEvents[0];
    if (fatal) {
      throw new Error(
        "Fatal browser event while waiting for " + label + ": "
          + String(fatal.text || fatal.source || "unknown browser failure"),
      );
    }
    await sleep(250);
  }
  throw new Error("Timed out waiting for " + label + ": " + String(lastError || "condition false"));
}

function mimeType(path) {
  const extension = extname(path.endsWith(".gz") ? path.slice(0, -3) : path);
  return {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".wasm": "application/wasm",
    ".pack": "application/octet-stream",
    ".map": "application/json; charset=utf-8",
  }[extension] || "application/octet-stream";
}

function configureBenchmarkUrl(rawUrl) {
  const configured = new URL(rawUrl);
  configured.searchParams.set("targetFps", String(Number(environmentContract.maxFps || 260)));
  configured.searchParams.set("autoDpr", environmentContract.autoDpr === false ? "0" : "1");
  configured.searchParams.set("perfHud", "0");
  configured.searchParams.set("glStats", "1");
  // The launcher defaults the mesh buffer-shadow budget to 1 GiB (single 256 MiB),
  // which lets the WebGL shadow grow past the contract's 64 MiB budget and stall a
  // frame on one large shadow copy. Pin the runtime budget to the contract so the
  // runtime-invariants webglMemory gate and the longest-frame gate see the intended
  // configuration instead of the launcher's generous defaults.
  const webglMemory = performanceContract.runtimeInvariants?.webglMemory || {};
  const shadowBudgetBytes = Number(webglMemory.bufferShadowBudgetBytes) || 64 * 1024 * 1024;
  configured.searchParams.set("totalShadowMB", String(Math.max(1, Math.round(shadowBudgetBytes / (1024 * 1024)))));
  configured.searchParams.set("singleShadowMB", "16");
  return configured.href;
}

async function startStaticServer(root) {
  const normalizedRoot = resolve(root);
  const server = createHttpServer(async (request, response) => {
    try {
      const requestUrl = new URL(request.url || "/", "http://127.0.0.1");
      const relative = decodeURIComponent(requestUrl.pathname === "/"
        ? "/index.html"
        : requestUrl.pathname);
      const path = resolve(normalizedRoot, "." + relative);
      if (path !== normalizedRoot && !path.startsWith(normalizedRoot + sep)) {
        response.writeHead(403);
        response.end("Forbidden");
        return;
      }
      const metadata = await stat(path);
      if (!metadata.isFile()) throw new Error("not a file");
      const headers = {
        "Cache-Control": "no-store",
        "Content-Type": mimeType(path),
        "Cross-Origin-Embedder-Policy": "require-corp",
        "Cross-Origin-Opener-Policy": "same-origin",
        "Cross-Origin-Resource-Policy": "same-origin",
      };
      // The Gaius launcher and its singleplayer worker fetch .gz assets and
      // decompress them themselves (DecompressionStream). serve-dist.py also
      // serves .gz raw; sending Content-Encoding: gzip here would make the
      // browser transparently decompress the payload and the worker would
      // decompress it a second time (bootstrap-crash: Failed to fetch).
      response.writeHead(200, headers);
      createReadStream(path).pipe(response);
    } catch (error) {
      response.writeHead(404, {"Content-Type": "text/plain; charset=utf-8"});
      response.end("Not found");
    }
  });
  await new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  return {
    server,
    url: configureBenchmarkUrl(
      "http://127.0.0.1:" + server.address().port + "/index.html",
    ),
  };
}

function keyDefinition(code) {
  return {
    KeyW: {key: "w", virtualKey: 87},
    KeyA: {key: "a", virtualKey: 65},
    KeyS: {key: "s", virtualKey: 83},
    KeyD: {key: "d", virtualKey: 68},
    Space: {key: " ", virtualKey: 32},
    ControlLeft: {key: "Control", virtualKey: 17},
    ControlRight: {key: "Control", virtualKey: 17},
    ShiftLeft: {key: "Shift", virtualKey: 16},
    ShiftRight: {key: "Shift", virtualKey: 16},
    AltLeft: {key: "Alt", virtualKey: 18},
    AltRight: {key: "Alt", virtualKey: 18},
    Escape: {key: "Escape", virtualKey: 27},
    Tab: {key: "Tab", virtualKey: 9},
    Enter: {key: "Enter", virtualKey: 13},
    Backspace: {key: "Backspace", virtualKey: 8},
    Digit1: {key: "1", virtualKey: 49},
    Digit2: {key: "2", virtualKey: 50},
    Digit3: {key: "3", virtualKey: 51},
    Digit4: {key: "4", virtualKey: 52},
    Digit5: {key: "5", virtualKey: 53},
    Digit6: {key: "6", virtualKey: 54},
    Digit7: {key: "7", virtualKey: 55},
    Digit8: {key: "8", virtualKey: 56},
    Digit9: {key: "9", virtualKey: 57},
  }[code];
}

// Keep this superset in one place.  The cleanup path deliberately sends keyUp
// for every key a benchmark phase can ever press, even when the phase failed
// before its matching keyUp or the CDP command was delayed.
const benchmarkKeyCodes = [
  "KeyW", "KeyA", "KeyS", "KeyD", "Space",
  "ControlLeft", "ControlRight", "ShiftLeft", "ShiftRight",
  "AltLeft", "AltRight", "Escape", "Tab", "Enter", "Backspace",
  "Digit1", "Digit2", "Digit3", "Digit4", "Digit5",
  "Digit6", "Digit7", "Digit8", "Digit9",
];

async function dispatchKey(session, code, type) {
  const definition = keyDefinition(code);
  if (!definition) throw new Error("Unsupported benchmark key " + code);
  await session.send("Input.dispatchKeyEvent", {
    type,
    code,
    key: definition.key,
    windowsVirtualKeyCode: definition.virtualKey,
    nativeVirtualKeyCode: definition.virtualKey,
  });
}

// CDP input can outlive a failed benchmark/leave-world transition.  Always
// release every mouse button and pointer lock before closing the session so a
// non-headless run cannot leave the operator's desktop in a drag/selection
// state (or keep Minecraft's captured cursor stuck in a corner).
async function releaseInputCapture(session) {
  if (!session) return;
  for (const code of benchmarkKeyCodes) {
    try {
      await dispatchKey(session, code, "keyUp");
    } catch (ignored) {
    }
  }
  for (const button of ["left", "middle", "right", "back", "forward"]) {
    try {
      await session.send("Input.dispatchMouseEvent", {
        type: "mouseReleased",
        x: 0,
        y: 0,
        button,
        buttons: 0,
        clickCount: 1,
      });
    } catch (ignored) {
    }
  }
  try {
    await evaluate(session, "(() => {"
      + "if (document.exitPointerLock) document.exitPointerLock();"
      + "const selection=window.getSelection&&window.getSelection();"
      + "if(selection) selection.removeAllRanges();"
      + "return true;"
    + "})()");
  } catch (ignored) {
  }
}

async function click(session, x, y, count = 1) {
  await clickMouseButton(session, x, y, "left", count);
}

async function clickMouseButton(session, x, y, button, count = 1) {
  const buttons = button === "right" ? 2 : 1;
  await session.send("Input.dispatchMouseEvent", {
    type: "mouseMoved",
    x,
    y,
    button: "none",
  });
  await session.send("Input.dispatchMouseEvent", {
    type: "mousePressed",
    x,
    y,
    button,
    buttons,
    clickCount: count,
  });
  await session.send("Input.dispatchMouseEvent", {
    type: "mouseReleased",
    x,
    y,
    button,
    buttons: 0,
    clickCount: count,
  });
}

async function findButton(session, label, timeoutMillis = 45_000) {
  const deadline = Date.now() + timeoutMillis;
  const needle = JSON.stringify(String(label).trim().toLowerCase());
  while (Date.now() < deadline) {
    const result = await evaluate(session, "(() => {"
      + "const state=window.__gaiusMinecraftState||{};"
      + "const widgets=Array.isArray(state.screenWidgets)?state.screenWidgets:[];"
      + "const canvas=document.querySelector('canvas');"
      + "const rect=canvas?canvas.getBoundingClientRect():null;"
      + "const size=state.screenSize||null;"
      + "const scaleX=rect&&size&&size.width?rect.width/Number(size.width):1;"
      + "const scaleY=rect&&size&&size.height?rect.height/Number(size.height):1;"
      + "const normalize=value=>String(value||'').trim().toLowerCase();"
      + "const candidates=widgets.filter(widget=>widget&&widget.visible!==false"
      + "&&widget.active!==false&&Number.isFinite(Number(widget.x))"
      + "&&Number.isFinite(Number(widget.y))&&Number.isFinite(Number(widget.width))"
      + "&&Number.isFinite(Number(widget.height)));"
      + "const found=candidates.find(widget=>normalize(widget.text)===" + needle + ")"
      + "||candidates.find(widget=>normalize(widget.text).includes(" + needle + "));"
      + "if(!found||!rect)return null;"
      + "return {text:String(found.text||''),"
      + "x:Math.round(rect.left+(Number(found.x)+Number(found.width)/2)*scaleX),"
      + "y:Math.round(rect.top+(Number(found.y)+Number(found.height)/2)*scaleY),"
      + "screen:state.screen||null};"
      + "})()");
    if (result) return result;
    await sleep(250);
  }
  return null;
}

async function clickButton(session, label, timeoutMillis = 45_000) {
  const button = await findButton(session, label, timeoutMillis);
  if (!button) return false;
  await click(session, button.x, button.y);
  return true;
}

async function clickFirstWorld(session) {
  // The world list populates asynchronously after the selection screen opens; poll
  // briefly before giving up so the create-new-world fallback is not chosen while
  // an existing save is still appearing.
  let entry = null;
  const listDeadline = Date.now() + 15_000;
  while (Date.now() < listDeadline) {
    entry = await evaluate(session, "(() => {"
      + "const state=window.__gaiusMinecraftState||{};"
      + "const first=state.worldSelection&&state.worldSelection.first;"
      + "const canvas=document.querySelector('canvas');"
      + "const rect=canvas?canvas.getBoundingClientRect():null;"
      + "const size=state.screenSize||null;"
      + "if(!first||!rect||!size)return null;"
      + "return {"
      + "x:Math.round(rect.left+(Number(first.x)+Number(first.width)/2)*rect.width/Number(size.width)),"
      + "y:Math.round(rect.top+(Number(first.y)+Number(first.height)/2)*rect.height/Number(size.height))"
      + "};"
      + "})()");
    if (entry) break;
    await sleep(250);
  }
  if (!entry) return false;
  await click(session, entry.x, entry.y, 1);
  await sleep(100);
  await click(session, entry.x, entry.y, 2);
  return true;
}

async function passProfileGate(session) {
  await waitFor(
    session,
    "document.querySelector('#profile-gate')?.hidden===false"
      + "||!!window.__gaiusMinecraftState",
    60_000,
    "the player-name gate or Minecraft startup",
  );
  await waitFor(
    session,
    "document.querySelector('#profile-gate')?.hidden!==false"
      + "||typeof window.__gaiusFsReady!=='undefined'",
    60_000,
    "the browser persistence bootstrap",
  );
  return evaluate(session, "(async() => {"
    + "const gate=document.querySelector('#profile-gate');"
    + "if(!gate||gate.hidden!==false)throw new Error('Profile gate was not visible; refusing to reuse an existing Worker');"
    + "const path='/gaius/options.txt';"
    + "const encoded=" + JSON.stringify(benchmarkOptionsBase64) + ";"
    + "const storageReady=typeof globalThis.__gaiusFsReady!=='undefined';"
    + "if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady;"
    + "const files=globalThis.__gaiusPersistentFiles||"
    + "(globalThis.__gaiusPersistentFiles=Object.create(null));"
    + "files[path]=encoded;"
    + "const benchmarkStoragePrefix=" + JSON.stringify(activeStoragePrefix) + ";"
    + "let fsPut=false;let flushed=false;"
    + "try{if(typeof globalThis.__gaiusFsPut==='function'){"
    + "fsPut=globalThis.__gaiusFsPut(path,encoded)!==false;}}catch(ignored){}"
    + "try{if(typeof globalThis.__gaiusFsFlush==='function'){"
    + "await globalThis.__gaiusFsFlush();flushed=true;}}catch(ignored){}"
    + "let localStoragePut=false;"
    + "try{if(globalThis.localStorage){"
    + "localStorage.setItem(benchmarkStoragePrefix+path,encoded);localStoragePut=true;"
    + "}}catch(ignored){}"
    + "let persisted=false;try{persisted=localStorage.getItem(benchmarkStoragePrefix+path)===encoded;}"
    + "catch(ignored){}"
     + "globalThis.__gaiusBenchmarkProfile=" + JSON.stringify({
       name: profileName,
       renderDistance: expectedRenderDistance,
       simulationDistance: expectedSimulationDistance,
       workload: benchmarkProfile.workload,
     }) + ";"
    + "const workerRegistry=globalThis.__gaiusSingleplayerWorkers;"
    + "const workerRegistryPresent=workerRegistry!==null&&workerRegistry!==undefined;"
    + "const workerRegistrySize=workerRegistryPresent?workerRegistry.size:0;"
    + "if(workerRegistryPresent&&(typeof workerRegistry.size!=='number'"
    + "||!Number.isInteger(workerRegistrySize)||workerRegistrySize!==0))"
    + "throw new Error('Profile gate requires zero pre-existing singleplayer Workers');"
    // Install the observer before submit so the first product-owned Worker
    // launch is visible.  The default path is deliberately natural: it only
    // snapshots the original message and forwards that same object untouched.
    // A pin is an explicit diagnostic fixture and is never release evidence.
    + "let workerDistanceObserverInstalled=false;"
    + "const pinWorkerDistance=" + JSON.stringify(pinWorkerDistance) + ";"
    + "const expectedStorage=" + JSON.stringify({
      profileId: activeStorageProfileId,
      worldVersion: activeStorageWorldVersion,
      storageSchema: activeStorageConfig.schema,
      storageDatabaseName: activeStorageConfig.databaseName,
      storagePrefix: activeStorageConfig.prefix,
      storageOpfsDirectory: activeStorageConfig.opfsDirectory,
    }) + ";"
    + "const expectedDistances={renderDistance:" + JSON.stringify(expectedRenderDistance)
      + ",simulationDistance:" + JSON.stringify(expectedSimulationDistance) + "};"
    + "const naturalWorkerLog=Array.isArray(globalThis.__gaiusBenchmarkNaturalWorkerMessages)"
    + "?globalThis.__gaiusBenchmarkNaturalWorkerMessages"
    + ":(globalThis.__gaiusBenchmarkNaturalWorkerMessages=[]);"
    + "const workerOverrideLog=Array.isArray(globalThis.__gaiusBenchmarkWorkerOverrides)"
    + "?globalThis.__gaiusBenchmarkWorkerOverrides"
    + ":(globalThis.__gaiusBenchmarkWorkerOverrides=[]);"
    + "const naturalWorkerLogState=globalThis.__gaiusBenchmarkNaturalWorkerEvidenceState||"
    + "(globalThis.__gaiusBenchmarkNaturalWorkerEvidenceState={truncatedCount:0});"
    + "const harnessOverrideLogState=globalThis.__gaiusBenchmarkHarnessOverrideEvidenceState||"
    + "(globalThis.__gaiusBenchmarkHarnessOverrideEvidenceState={truncatedCount:0});"
    + "const appendBounded=(log,state,entry)=>{"
    + "if(log.length>=64){log.splice(0,log.length-63);state.truncatedCount++;}"
    + "log.push(entry);};"
    + "const snapshotWorkerMessage=message=>({"
    + "type:String(message.type),"
    + "renderDistance:message.renderDistance==null?null:message.renderDistance,"
    + "simulationDistance:message.simulationDistance==null?null:message.simulationDistance,"
    + "profileId:message.profileId==null?null:message.profileId,"
    + "worldVersion:message.worldVersion==null?null:message.worldVersion,"
    + "storageSchema:message.storageSchema==null?null:message.storageSchema,"
    + "storageDatabaseName:message.storageDatabaseName==null?null:message.storageDatabaseName,"
    + "storagePrefix:message.storagePrefix==null?null:message.storagePrefix,"
    + "storageOpfsDirectory:message.storageOpfsDirectory==null?null:message.storageOpfsDirectory,"
    + "storage:{profileId:message.profileId==null?null:message.profileId,"
    + "worldVersion:message.worldVersion==null?null:message.worldVersion,"
    + "storageSchema:message.storageSchema==null?null:message.storageSchema,"
    + "storageDatabaseName:message.storageDatabaseName==null?null:message.storageDatabaseName,"
    + "storagePrefix:message.storagePrefix==null?null:message.storagePrefix,"
    + "storageOpfsDirectory:message.storageOpfsDirectory==null?null:message.storageOpfsDirectory}"
    + "});"
    + "const NativeWorker=globalThis.Worker;"
    + "if(typeof NativeWorker!=='function')throw new Error('Worker constructor unavailable');"
    + "if(globalThis.__gaiusBenchmarkWorkerObserverInstalled)"
    + "throw new Error('Worker observation wrapper was already installed');"
    + "{"
     + "const WorkerProxy=new Proxy(NativeWorker,{construct(target,args){"
     + "const worker=Reflect.construct(target,args,target);"
    + "const workerSequence=Number(globalThis.__gaiusBenchmarkWorkerSequence||0)+1;"
    + "globalThis.__gaiusBenchmarkWorkerSequence=workerSequence;"
    + "worker.__gaiusBenchmarkWorkerSequence=workerSequence;"
     + "const originalPostMessage=worker.postMessage.bind(worker);"
     + "worker.postMessage=function(message,transfer){"
     + "if(message&&(message.type==='start'||message.type==='distances')){"
    + "const original=snapshotWorkerMessage(message);"
    + "appendBounded(naturalWorkerLog,naturalWorkerLogState,{at:Date.now(),workerSequence,rawMessage:original,...original});"
    + "if(pinWorkerDistance){"
    + "const pinned={...message,...expectedDistances,...expectedStorage};"
    + "appendBounded(workerOverrideLog,harnessOverrideLogState,{at:Date.now(),workerSequence,"
    + "type:original.type,original,renderDistance:pinned.renderDistance,"
    + "simulationDistance:pinned.simulationDistance,profileId:pinned.profileId,"
    + "worldVersion:pinned.worldVersion,storageSchema:pinned.storageSchema,"
    + "storageDatabaseName:pinned.storageDatabaseName,storagePrefix:pinned.storagePrefix,"
    + "storageOpfsDirectory:pinned.storageOpfsDirectory,storage:{profileId:pinned.profileId,"
    + "worldVersion:pinned.worldVersion,storageSchema:pinned.storageSchema,"
    + "storageDatabaseName:pinned.storageDatabaseName,storagePrefix:pinned.storagePrefix,"
    + "storageOpfsDirectory:pinned.storageOpfsDirectory}});"
    + "message=pinned;worker.__gaiusDistances=expectedDistances.renderDistance+':'+"
    + "expectedDistances.simulationDistance;}"
      + "}"
      + "return transfer===undefined?originalPostMessage(message):originalPostMessage(message,transfer);};"
      + "return worker;}});"
      + "globalThis.Worker=WorkerProxy;"
      + "globalThis.__gaiusBenchmarkWorkerObserverInstalled=true;"
      + "if(pinWorkerDistance)globalThis.__gaiusBenchmarkWorkerDistanceOverride=true;"
    + "workerDistanceObserverInstalled=true;}"
    + "const registryBeforeSubmit=globalThis.__gaiusSingleplayerWorkers;"
    + "const registryBeforeSubmitPresent=registryBeforeSubmit!==null&&registryBeforeSubmit!==undefined;"
    + "const registryBeforeSubmitSize=registryBeforeSubmitPresent?registryBeforeSubmit.size:0;"
    + "if(registryBeforeSubmitPresent&&(typeof registryBeforeSubmit.size!=='number'"
    + "||!Number.isInteger(registryBeforeSubmitSize)||registryBeforeSubmitSize!==0))"
    + "throw new Error('Profile gate observed a pre-existing singleplayer Worker before submit');"
      + "const input=document.querySelector('#profile-name');"
      + "const submit=document.querySelector('#profile-submit');"
      + "if(!input||!submit)throw new Error('Profile gate controls were not exposed');"
    + "input.value=" + JSON.stringify(playerName) + ";"
    + "input.dispatchEvent(new Event('input',{bubbles:true}));"
    + "submit.click();"
    + "return {submitted:true,seeded:persisted||fsPut||localStoragePut,"
    + "persisted,fsPut,flushed,localStoragePut,storageReady,workerDistanceObserverInstalled,"
    + "workerDistanceMode:pinWorkerDistance?'harness-pin-diagnostic':'natural-observation',"
    + "workerRegistryPresent,workerRegistrySize,naturalWorkerCount:naturalWorkerLog.length,"
    + "workerOverrideCount:workerOverrideLog.length};"
    + "})()");
}

async function enterWorld(session) {
  const startedAt = Date.now();
  if (await evaluate(session, "!!window.__gaiusMinecraftState?.level")) {
    return {
      startedAt,
      titleReadyAt: startedAt,
      titleReadyMillis: 0,
      worldLoadStartedAt: startedAt,
      activeAt: startedAt,
      worldInteractiveMillis: 0,
      totalMillis: 0,
      createdNewWorld: false,
      alreadyActive: true,
    };
  }
  await waitFor(
    session,
    "String(window.__gaiusMinecraftState?.screen||'').endsWith('TitleScreen')",
    startupTimeoutMillis,
    "the title screen",
  );
  const titleReadyAt = Date.now();
  if (!await clickButton(session, "Singleplayer")) {
    throw new Error("Singleplayer button was not exposed by UI telemetry");
  }
  await waitFor(
    session,
    "(() => {const screen=String(window.__gaiusMinecraftState?.screen||'');"
      + "return screen.includes('SelectWorldScreen')"
      + "||screen.includes('CreateWorldScreen');})()",
    60_000,
    "world selection or the create-world screen",
  );
  await sleep(500);
  let worldLoadStartedAt;
  let createdNewWorld = false;
  worldLoadStartedAt = Date.now();
  const selectionScreen = String(await evaluate(
    session,
    "window.__gaiusMinecraftState?.screen||''",
  ) || "");
  if (selectionScreen.includes("SelectWorldScreen")) {
    if (await clickFirstWorld(session)) {
      await sleep(1000);
      if (!await evaluate(session, "!!window.__gaiusMinecraftState?.level")) {
        await clickButton(session, "Play Selected World", 5000);
      }
    } else {
      createdNewWorld = true;
      if (!await clickButton(session, "Create New World")) {
        throw new Error("Create New World button was not exposed by UI telemetry");
      }
      await waitFor(
        session,
        "String(window.__gaiusMinecraftState?.screen||'').includes('CreateWorldScreen')",
        60_000,
        "the create-world screen",
      );
    }
  } else if (selectionScreen.includes("CreateWorldScreen")) {
    createdNewWorld = true;
  } else {
    throw new Error("Unexpected singleplayer screen: " + selectionScreen);
  }
  if (createdNewWorld) {
    await sleep(500);
    await clickButton(session, "Game Mode", 3000);
    await sleep(150);
    await clickButton(session, "Game Mode", 3000);
    worldLoadStartedAt = Date.now();
    if (!await clickButton(session, "Create New World")) {
      throw new Error("Create-world confirmation button was not exposed by UI telemetry");
    }
  }
  await waitFor(
    session,
    "!!window.__gaiusMinecraftState?.level&&!window.__gaiusMinecraftState?.screen",
    startupTimeoutMillis,
    "an active singleplayer world",
  );
  const activeAt = Date.now();
  return {
    startedAt,
    titleReadyAt,
    titleReadyMillis: titleReadyAt - startedAt,
    worldLoadStartedAt,
    activeAt,
    worldInteractiveMillis: activeAt - worldLoadStartedAt,
    totalMillis: activeAt - startedAt,
    createdNewWorld,
    alreadyActive: false,
  };
}

async function focusGame(session) {
  const center = await evaluate(session, "(() => {"
    + "const canvas=document.querySelector('canvas');"
    + "if(!canvas)return {x:640,y:360};"
    + "const rect=canvas.getBoundingClientRect();"
    + "return {x:Math.round(rect.left+rect.width/2),y:Math.round(rect.top+rect.height/2)};"
    + "})()");
  await click(session, center.x, center.y);
  return center;
}

async function analyzeCompositorScreenshot(session, pngBase64) {
  const dataUrl = "data:image/png;base64," + pngBase64;
  return evaluate(session, `(async() => {
    const image = new Image();
    image.src = ${JSON.stringify(dataUrl)};
    await image.decode();
    const sampleWidth = ${JSON.stringify(visualSampleWidth)};
    const sampleHeight = ${JSON.stringify(visualSampleHeight)};
    const canvas = document.createElement('canvas');
    canvas.width = sampleWidth;
    canvas.height = sampleHeight;
    const context = canvas.getContext('2d', {alpha: true, willReadFrequently: true});
    if (!context) throw new Error('2D screenshot analysis context is unavailable');
    context.imageSmoothingEnabled = true;
    context.drawImage(image, 0, 0, sampleWidth, sampleHeight);
    const pixels = context.getImageData(0, 0, sampleWidth, sampleHeight).data;
    const buckets = new Uint32Array(4096);
    const centralBuckets = new Uint32Array(4096);
    const centralLeft = Math.floor(sampleWidth * 0.08);
    const centralRight = Math.ceil(sampleWidth * 0.92);
    const centralTop = Math.floor(sampleHeight * 0.08);
    const centralBottom = Math.ceil(sampleHeight * 0.80);
    const tileColumns = 4;
    const tileRows = 3;
    const tilePixels = new Uint32Array(tileColumns * tileRows);
    const tileNonBlack = new Uint32Array(tileColumns * tileRows);
    const fingerprintWidth = 16;
    const fingerprintHeight = 9;
    const fingerprintSums = new Float64Array(fingerprintWidth * fingerprintHeight);
    const fingerprintCounts = new Uint32Array(fingerprintWidth * fingerprintHeight);
    let nonBlack = 0;
    let nonTransparent = 0;
    let luminanceSum = 0;
    let luminanceSquaredSum = 0;
    let dominantColorCount = 0;
    let colorBuckets = 0;
    let centralNonBlack = 0;
    let centralNonTransparent = 0;
    let centralLuminanceSum = 0;
    let centralLuminanceSquaredSum = 0;
    let centralDominantColorCount = 0;
    let centralColorBuckets = 0;
    let centralPixelCount = 0;
    for (let offset = 0; offset < pixels.length; offset += 4) {
      const pixelIndex = offset >>> 2;
      const x = pixelIndex % sampleWidth;
      const y = Math.floor(pixelIndex / sampleWidth);
      const red = pixels[offset];
      const green = pixels[offset + 1];
      const blue = pixels[offset + 2];
      const alpha = pixels[offset + 3];
      const luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
      if (alpha > 0) nonTransparent++;
      if (alpha > 0 && luminance > 4) nonBlack++;
      luminanceSum += luminance;
      luminanceSquaredSum += luminance * luminance;
      const bucket = ((red >>> 4) << 8) | ((green >>> 4) << 4) | (blue >>> 4);
      const count = ++buckets[bucket];
      if (count === 1) colorBuckets++;
      if (count > dominantColorCount) dominantColorCount = count;
      if (x >= centralLeft && x < centralRight && y >= centralTop && y < centralBottom) {
        centralPixelCount++;
        if (alpha > 0) centralNonTransparent++;
        if (alpha > 0 && luminance > 4) centralNonBlack++;
        centralLuminanceSum += luminance;
        centralLuminanceSquaredSum += luminance * luminance;
        const centralCount = ++centralBuckets[bucket];
        if (centralCount === 1) centralColorBuckets++;
        if (centralCount > centralDominantColorCount) {
          centralDominantColorCount = centralCount;
        }
        const fingerprintX = Math.min(
          fingerprintWidth - 1,
          Math.floor((x - centralLeft) * fingerprintWidth / (centralRight - centralLeft)),
        );
        const fingerprintY = Math.min(
          fingerprintHeight - 1,
          Math.floor((y - centralTop) * fingerprintHeight / (centralBottom - centralTop)),
        );
        const fingerprintIndex = fingerprintY * fingerprintWidth + fingerprintX;
        fingerprintSums[fingerprintIndex] += luminance;
        fingerprintCounts[fingerprintIndex]++;
        const tileX = Math.min(
          tileColumns - 1,
          Math.floor((x - centralLeft) * tileColumns / (centralRight - centralLeft)),
        );
        const tileY = Math.min(
          tileRows - 1,
          Math.floor((y - centralTop) * tileRows / (centralBottom - centralTop)),
        );
        const tileIndex = tileY * tileColumns + tileX;
        tilePixels[tileIndex]++;
        if (alpha > 0 && luminance > 4) tileNonBlack[tileIndex]++;
      }
    }
    const pixelCount = sampleWidth * sampleHeight;
    const luminanceMean = pixelCount ? luminanceSum / pixelCount : 0;
    const variance = pixelCount
      ? Math.max(0, luminanceSquaredSum / pixelCount - luminanceMean * luminanceMean)
      : 0;
    const centralLuminanceMean = centralPixelCount
      ? centralLuminanceSum / centralPixelCount : 0;
    const centralVariance = centralPixelCount
      ? Math.max(
        0,
        centralLuminanceSquaredSum / centralPixelCount
          - centralLuminanceMean * centralLuminanceMean,
      )
      : 0;
    const terrainFingerprint = Array.from(fingerprintSums, (sum, index) => {
      const count = fingerprintCounts[index];
      return count ? Math.round(sum / count * 1000) / 1000 : 0;
    });
    const tileNonBlackRatios = Array.from(tilePixels, (count, index) =>
      count ? tileNonBlack[index] / count : 0);
    const activeTileCount = tileNonBlackRatios.filter((ratio) => ratio >= 0.05).length;
    return {
      width: image.naturalWidth,
      height: image.naturalHeight,
      sampleWidth,
      sampleHeight,
      pixelCount,
      nonBlackPixelCount: nonBlack,
      nonBlackRatio: pixelCount ? nonBlack / pixelCount : 0,
      nonTransparentPixelCount: nonTransparent,
      nonTransparentRatio: pixelCount ? nonTransparent / pixelCount : 0,
      luminanceMean,
      luminanceStdDev: Math.sqrt(variance),
      colorBuckets,
      dominantColorRatio: pixelCount ? dominantColorCount / pixelCount : 1,
      centralPixelCount,
      centralNonBlackPixelCount: centralNonBlack,
      centralNonBlackRatio: centralPixelCount ? centralNonBlack / centralPixelCount : 0,
      centralNonTransparentPixelCount: centralNonTransparent,
      centralNonTransparentRatio: centralPixelCount
        ? centralNonTransparent / centralPixelCount : 0,
      centralLuminanceMean,
      centralLuminanceStdDev: Math.sqrt(centralVariance),
      centralColorBuckets,
      centralDominantColorRatio: centralPixelCount
        ? centralDominantColorCount / centralPixelCount : 1,
      tileColumns,
      tileRows,
      tileCount: tileColumns * tileRows,
      activeTileCount,
      tileNonBlackRatios,
      terrainFingerprint,
    };
  })()`, 15_000);
}

async function getCanvasScreenshotClip(session) {
  return evaluate(session, `(() => {
    const canvas = document.querySelector('#mc-canvas');
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    const left = Math.max(0, rect.left);
    const top = Math.max(0, rect.top);
    const right = Math.min(window.innerWidth, rect.right);
    const bottom = Math.min(window.innerHeight, rect.bottom);
    const width = right - left;
    const height = bottom - top;
    if (!(width > 0 && height > 0)) return null;
    return {
      x: window.scrollX + left,
      y: window.scrollY + top,
      width,
      height,
      scale: 1,
      cssWidth: rect.width,
      cssHeight: rect.height,
      visibleWidth: width,
      visibleHeight: height,
    };
  })()`);
}

async function captureVisualOutputSample(session, phase, index, persist = true) {
  await mkdir(visualOutputDirectory, {recursive: true});
  await session.send("Page.bringToFront");
  const at = Date.now();
  try {
    const canvasClip = await getCanvasScreenshotClip(session);
    if (!canvasClip) throw new Error("Minecraft canvas screenshot clip is unavailable");
    const screenshot = await withTimeout(session.send("Page.captureScreenshot", {
      format: "png",
      fromSurface: true,
      captureBeyondViewport: false,
      clip: {
        x: canvasClip.x,
        y: canvasClip.y,
        width: canvasClip.width,
        height: canvasClip.height,
        scale: canvasClip.scale,
      },
    }), 15_000, "Page.captureScreenshot");
    if (!screenshot.data) throw new Error("Chrome returned an empty compositor screenshot");
    const path = persist
      ? resolve(visualOutputDirectory, `${phase}-${index}.png`)
      : null;
    if (path) await writeFile(path, Buffer.from(screenshot.data, "base64"));
    const statistics = await analyzeCompositorScreenshot(session, screenshot.data);
    return {
      available: true,
      phase,
      index,
      at,
      path,
      encodedBytes: Buffer.byteLength(screenshot.data, "base64"),
      canvasClip,
      ...statistics,
    };
  } catch (error) {
    return {
      available: false,
      phase,
      index,
      at,
      error: String(error && (error.stack || error.message) || error),
    };
  }
}

async function collectVisualOutput(session, phase) {
  const samples = [];
  for (let index = 0; index < visualCapturesPerPhase; index++) {
    samples.push(await captureVisualOutputSample(session, phase, index + 1));
    if (index + 1 < visualCapturesPerPhase) await sleep(visualCaptureIntervalMillis);
  }
  return samples;
}

async function collectContinuousVisualOutput(session, durationMillis) {
  const samples = [];
  const startedAt = Date.now();
  const deadline = startedAt + Math.max(0, durationMillis);
  let nextCaptureAt = startedAt + continuousVisualIntervalMillis;
  let index = 0;
  while (nextCaptureAt <= deadline) {
    await sleep(Math.max(0, nextCaptureAt - Date.now()));
    if (Date.now() > deadline) break;
    samples.push(await captureVisualOutputSample(
      session,
      "continuous-measurement",
      ++index,
      false,
    ));
    nextCaptureAt += continuousVisualIntervalMillis;
  }
  return samples;
}

function visualSampleHasTerrain(sample) {
  return summarizeVisualOutput([sample], {
    ...visualOutputContract,
    requiredSamples: 1,
    requiredPhases: ["startup-ready"],
    minimumSamplesPerPhase: 1,
    requireSceneChange: false,
  }).verdict === "pass";
}

async function observeWorldReadiness(session) {
  return evaluate(session, `(() => {
    const state = globalThis.__gaiusMinecraftState || {};
    const frame = globalThis.__gaiusFrameTelemetry || {};
    const player = state.player || null;
    const hit = state.hit || null;
    const now = performance.now();
    const lastFrameAt = Number(frame.lastFrameAt);
    const blockState = String(hit && hit.blockState || '');
    const hitIsSolidBlock = String(hit && hit.type || '').toUpperCase() === 'BLOCK'
      && !!(hit && hit.blockPos)
      && blockState.length > 0
      && !/minecraft:(?:air|cave_air|void_air)\\b/i.test(blockState);
    const finitePlayer = !!player
      && [player.x, player.y, player.z, player.yaw, player.pitch]
        .every(value => Number.isFinite(Number(value)));
    const level = String(state.level || '');
    const baseReady = level === 'net.minecraft.client.multiplayer.ClientLevel'
      && !state.screen
      && !state.overlay
      && state.noRender === false
      && state.running === true
      && state.pause === false
      && finitePlayer
      && player.collisionFree === true
      && Number(state.loadedChunkCount) >= ${JSON.stringify(
        Math.max(1, Number(startupContract.minimumLoadedChunks || 1)),
      )}
      && document.visibilityState === 'visible';
    return {
      at: Date.now(),
      stateAt: Number(state.at) || 0,
      baseReady,
      level: state.level || null,
      screen: state.screen || null,
      overlay: state.overlay || null,
      noRender: state.noRender,
      running: state.running,
      pause: state.pause,
      loadedChunkCount: Number.isFinite(Number(state.loadedChunkCount))
        ? Number(state.loadedChunkCount) : null,
      player,
      hit,
      hitIsSolidBlock,
      visibleFrameCount: Number(frame.visibleFrameCount) || 0,
      frameAgeMillis: Number.isFinite(lastFrameAt) ? Math.max(0, now - lastFrameAt) : null,
      documentVisibility: document.visibilityState,
    };
  })()`);
}

async function aimTowardTerrain(session, initialPoint) {
  let point = {...initialPoint};
  for (let attempt = 0; attempt < 5; attempt++) {
    const observed = await observeWorldReadiness(session);
    if (observed.hitIsSolidBlock) break;
    const pitch = Number(observed.player?.pitch);
    if (Number.isFinite(pitch) && pitch >= 55) break;
    point = {...point, y: Math.min(point.y + 48, Number(environmentContract.viewport?.height || 720) - 2)};
    await session.send("Input.dispatchMouseEvent", {
      type: "mouseMoved",
      x: point.x,
      y: point.y,
      button: "none",
    });
    await sleep(100);
  }
  return point;
}

async function waitForStrictWorldReadiness(session, deadlineAt) {
  const observations = [];
  const visualSamples = [];
  let visualAttempt = 0;
  let lastVisualAttemptAt = 0;
  let summary = summarizeWorldReadiness([], startupContract);
  while (Date.now() < deadlineAt) {
    const observed = await observeWorldReadiness(session);
    const previous = observations.at(-1);
    const frameAdvanced = !previous
      || observed.visibleFrameCount > Number(previous.visibleFrameCount || 0);
    const frameContinuous = observed.frameAgeMillis != null
      && observed.frameAgeMillis <= startupMaximumFrameGapMillis;
    if (observed.baseReady && frameContinuous && frameAdvanced
        && summary.validVisualSamples < startupMinimumVisualSamples
        && visualAttempt < startupVisualCaptureAttempts
        && observed.at - lastVisualAttemptAt >= visualCaptureIntervalMillis) {
      visualAttempt++;
      lastVisualAttemptAt = observed.at;
      const sample = await captureVisualOutputSample(
        session,
        "startup-ready",
        visualAttempt,
      );
      visualSamples.push(sample);
      observed.visualPass = visualSampleHasTerrain(sample);
    }
    observations.push(observed);
    if (observations.length > 128) observations.shift();
    summary = summarizeWorldReadiness(observations, startupContract);
    if (summary.verdict === "pass") {
      return {
        ...summary,
        interactiveAt: Date.now(),
        visualSamples,
        observations,
      };
    }
    await sleep(startupTerrainPollMillis);
  }
  return {
    ...summary,
    verdict: "fail",
    reason: "strict world-readiness deadline expired",
    visualSamples,
    observations,
  };
}

async function installGameplayAuthorityProbe(session) {
  return evaluate(session, `(() => {
    const bridge = globalThis.__gaiusNettyBridge;
    const workers = globalThis.__gaiusSingleplayerWorkers;
    const channels = bridge && bridge.channels && typeof bridge.channels.entries === 'function'
      ? Array.from(bridge.channels.entries())
      : [];
    const localChannels = channels.filter(([, entry]) => entry
      && String(entry.host || '').endsWith('.gaius-local')
      && entry.localPort);
    if (!bridge || typeof bridge.send !== 'function'
        || typeof bridge.deliverInbound !== 'function'
        || localChannels.length === 0
        || !workers || typeof workers.entries !== 'function') {
      return {
        installed: false,
        source: 'browser-transport-and-client-state',
        route: 'singleplayer-worker',
        reason: 'the live singleplayer Worker MessagePort transport was not observable',
      };
    }
    const existing = globalThis.__gaiusGameplayAuthorityProbe;
    if (existing && existing.installed === true) {
      return existing.snapshot();
    }

    const packetIds = ${JSON.stringify(activeGameplayPacketIds)};
    const airBlockStateId = ${JSON.stringify(activeAirBlockStateId)};
    const maxActions = 64;
    const maxStreamBytes = 4 * 1024 * 1024;
    const localChannelIds = localChannels.map(([id]) => Number(id));
    const workerSessionIds = Array.from(workers.entries())
      .filter(([, worker]) => worker && !worker.__gaiusTerminal)
      .map(([id]) => String(id));
    const state = {
      installed: true,
      source: 'browser-transport-and-client-state',
      route: 'singleplayer-worker',
      protocolVersion: ${JSON.stringify(activeProtocolVersion)},
      packetIds,
      channelIds: localChannelIds,
      workerSessionIds,
      actions: [],
      outboundTransportCalls: 0,
      inboundTransportCalls: 0,
      outboundTransportBytes: 0,
      inboundTransportBytes: 0,
      parsedOutboundPackets: 0,
      parsedInboundPackets: 0,
      compressedFrames: 0,
      parserFailures: 0,
      streams: {outbound: new Uint8Array(0), inbound: new Uint8Array(0)},
      measurementStartedAt: null,
      measurementEndedAt: null,
      rollbacks: 0,
    };

    const now = () => performance.now();
    const isLocalEntry = (entry) => entry && (
      localChannelIds.includes(Number(entry.id))
      || String(entry.host || '').endsWith('.gaius-local')
      || !!entry.localPort
    );
    const asBytes = (value) => {
      if (value instanceof ArrayBuffer) return new Uint8Array(value);
      if (ArrayBuffer.isView(value)) {
        return new Uint8Array(value.buffer, value.byteOffset || 0, value.byteLength || 0);
      }
      return null;
    };
    const readVarInt = (bytes, offset) => {
      let value = 0;
      for (let index = 0; index < 5; index++) {
        const at = offset + index;
        if (at >= bytes.length) return null;
        const part = bytes[at];
        value |= (part & 0x7f) << (index * 7);
        if ((part & 0x80) === 0) return {value: value >>> 0, next: at + 1};
      }
      return null;
    };
    const readBlockPos = (bytes, offset) => {
      if (offset < 0 || offset + 8 > bytes.length) return null;
      let value = 0n;
      for (let index = 0; index < 8; index++) {
        value = (value << 8n) | BigInt(bytes[offset + index]);
      }
      const decode = (shift, bits) => {
        const mask = (1n << BigInt(bits)) - 1n;
        let result = Number((value >> BigInt(shift)) & mask);
        const sign = 1 << (bits - 1);
        if (result >= sign) result -= 1 << bits;
        return result;
      };
      return {
        x: decode(38, 26),
        y: decode(0, 12),
        z: decode(12, 26),
        next: offset + 8,
      };
    };
    const samePos = (left, right) => !!left && !!right
      && left.x === right.x && left.y === right.y && left.z === right.z;
    const addPos = (pos, delta) => ({x: pos.x + delta.x, y: pos.y + delta.y, z: pos.z + delta.z});
    const positionFromText = (value) => {
      const match = String(value || '').match(/x=(-?\\d+),\\s*y=(-?\\d+),\\s*z=(-?\\d+)/i);
      return match ? {x: Number(match[1]), y: Number(match[2]), z: Number(match[3])} : null;
    };
    const directionDelta = (value) => {
      const name = String(value || '').toUpperCase();
      if (name.includes('UP')) return {x: 0, y: 1, z: 0};
      if (name.includes('DOWN')) return {x: 0, y: -1, z: 0};
      if (name.includes('NORTH')) return {x: 0, y: 0, z: -1};
      if (name.includes('SOUTH')) return {x: 0, y: 0, z: 1};
      if (name.includes('WEST')) return {x: -1, y: 0, z: 0};
      if (name.includes('EAST')) return {x: 1, y: 0, z: 0};
      return null;
    };
    const stateSnapshot = () => {
      const hit = globalThis.__gaiusMinecraftState?.hit || null;
      const pos = positionFromText(hit?.blockPos);
      const blockState = hit?.blockState == null ? null : String(hit.blockState);
      return {
        pos,
        type: hit?.type == null ? null : String(hit.type),
        blockState,
        key: JSON.stringify({pos, type: hit?.type || null, blockState}),
      };
    };
    const isAirState = (value) => {
      const text = String(value || '').toLowerCase();
      return text.includes('air') || text === 'minecraft:air';
    };
    const pendingAction = (type, position) => {
      for (let index = state.actions.length - 1; index >= 0; index--) {
        const action = state.actions[index];
        if (action.type !== type || action.emittedAt != null) continue;
        const expected = type === 'place' ? action.clicked : action.target;
        if (samePos(expected, position)) return action;
      }
      return null;
    };
    const actionParse = (body) => {
      const action = readVarInt(body, 0);
      if (!action) return null;
      const position = readBlockPos(body, action.next);
      if (!position) return null;
      const direction = readVarInt(body, position.next);
      if (!direction) return null;
      const sequence = readVarInt(body, direction.next);
      if (!sequence || sequence.next !== body.length) return null;
      return {action: action.value, position, direction: direction.value, sequence: sequence.value};
    };
    const useItemOnParse = (body) => {
      const hand = readVarInt(body, 0);
      if (!hand || hand.value > 1) return null;
      const clicked = readBlockPos(body, hand.next);
      if (!clicked) return null;
      const direction = readVarInt(body, clicked.next);
      if (!direction || direction.value > 5) return null;
      const floatAt = direction.next;
      if (floatAt + 13 > body.length) return null;
      const view = new DataView(body.buffer, body.byteOffset + floatAt, body.byteLength - floatAt);
      const cursor = [view.getFloat32(0, false), view.getFloat32(4, false), view.getFloat32(8, false)];
      if (!cursor.every(Number.isFinite) || cursor.some((value) => value < -0.01 || value > 1.01)) return null;
      const insideBlock = body[floatAt + 12];
      if (insideBlock !== 0 && insideBlock !== 1) return null;
      const sequence = readVarInt(body, floatAt + 13);
      if (!sequence || sequence.next !== body.length) return null;
      return {hand: hand.value, clicked, direction: direction.value, sequence: sequence.value};
    };
    const blockUpdateParse = (body) => {
      const position = readBlockPos(body, 0);
      if (!position) return null;
      const stateId = readVarInt(body, position.next);
      if (!stateId || stateId.next !== body.length) return null;
      return {position, stateId: stateId.value};
    };
    const appendStream = (direction, value) => {
      const incoming = asBytes(value);
      if (!incoming || incoming.length === 0) return;
      const old = state.streams[direction];
      if (old.length + incoming.length > maxStreamBytes) {
        state.streams[direction] = new Uint8Array(0);
        state.parserFailures++;
        return;
      }
      const combined = new Uint8Array(old.length + incoming.length);
      combined.set(old);
      combined.set(incoming, old.length);
      state.streams[direction] = combined;
      parseFrames(direction);
    };
    const confirmTransport = (action, at, kind) => {
      if (!action || action.transportConfirmationAt != null) return;
      action.transportConfirmationAt = at;
      action.confirmationKind = kind;
    };
    const handleOutbound = (packetId, body, at) => {
      if (packetId === packetIds.outboundPlayerAction) {
        const packet = actionParse(body);
        if (!packet) return;
        state.parsedOutboundPackets++;
        if (packet.action !== 2) return;
        const action = pendingAction('break', packet.position);
        if (!action) return;
        action.emittedAt = at;
        action.emittedPacketId = packetId;
        action.emittedSequence = packet.sequence;
        action.emittedAction = packet.action;
        action.workloadActiveAtEmission = globalThis.__gaiusBenchmarkWorkloadActive === true;
      } else if (packetId === packetIds.outboundUseItemOn) {
        const packet = useItemOnParse(body);
        if (!packet) return;
        state.parsedOutboundPackets++;
        const action = pendingAction('place', packet.clicked);
        if (!action) return;
        action.emittedAt = at;
        action.emittedPacketId = packetId;
        action.emittedSequence = packet.sequence;
        action.emittedAction = 'use-item-on';
        action.workloadActiveAtEmission = globalThis.__gaiusBenchmarkWorkloadActive === true;
      }
    };
    const handleInbound = (packetId, body, at) => {
      if (packetId === packetIds.inboundBlockChangedAck) {
        const sequence = readVarInt(body, 0);
        if (!sequence || sequence.next !== body.length) return;
        state.parsedInboundPackets++;
        for (const action of state.actions) {
          if (action.type === 'break' && action.emittedSequence === sequence.value) {
            confirmTransport(action, at, 'clientbound-block-changed-ack');
          }
        }
      } else if (packetId === packetIds.inboundBlockUpdate) {
        const packet = blockUpdateParse(body);
        if (!packet) return;
        state.parsedInboundPackets++;
        for (const action of state.actions) {
          const expected = action.target;
          if (!action.emittedAt || !samePos(expected, packet.position)) continue;
          const authoritativeState = action.type === 'break'
            ? packet.stateId === airBlockStateId
            : packet.stateId !== airBlockStateId;
          if (authoritativeState && action.authoritativeStateAt == null) {
            action.authoritativeStateAt = at;
          }
          if (action.transportConfirmationAt == null) {
            confirmTransport(action, at, 'clientbound-block-update');
          } else if (action.stateTransitionAt != null) {
            const rollback = action.type === 'break'
              ? packet.stateId !== 0
              : packet.stateId === 0;
            if (rollback && action.rollbackAt == null) {
              action.rollbackAt = at;
              state.rollbacks++;
            }
          }
          action.lastInboundStateId = packet.stateId;
        }
      }
    };
    function parseFrames(direction) {
      let bytes = state.streams[direction];
      while (bytes.length > 0) {
        const length = readVarInt(bytes, 0);
        if (!length) break;
        if (length.value < 1 || length.value > maxStreamBytes) {
          state.parserFailures++;
          state.streams[direction] = bytes.slice(1);
          bytes = state.streams[direction];
          continue;
        }
        const end = length.next + length.value;
        if (end > bytes.length) break;
        const payload = bytes.subarray(length.next, end);
        const first = readVarInt(payload, 0);
        if (!first) {
          state.parserFailures++;
          bytes = bytes.slice(end);
          continue;
        }
        let packetId = first.value;
        let bodyAt = first.next;
        const expected = direction === 'outbound'
          ? [packetIds.outboundPlayerAction, packetIds.outboundUseItemOn]
          : [packetIds.inboundBlockChangedAck, packetIds.inboundBlockUpdate];
        if (!expected.includes(packetId)) {
          if (first.value === 0) {
            const uncompressedPacket = readVarInt(payload, first.next);
            if (uncompressedPacket && expected.includes(uncompressedPacket.value)) {
              packetId = uncompressedPacket.value;
              bodyAt = uncompressedPacket.next;
            } else if (first.value > 0) {
              state.compressedFrames++;
            }
          } else if (first.value > 0) {
            state.compressedFrames++;
          }
        }
        if (expected.includes(packetId)) {
          const body = payload.subarray(bodyAt);
          const at = now();
          if (direction === 'outbound') handleOutbound(packetId, body, at);
          else handleInbound(packetId, body, at);
        }
        bytes = bytes.slice(end);
        state.streams[direction] = bytes;
      }
    }
    const observeAction = (action) => {
      if (!action || action.emittedAt == null || action.transportConfirmationAt == null) return;
      const current = stateSnapshot();
      if (action.stateTransitionAt == null) {
        const changed = action.type === 'break'
          ? action.authoritativeStateAt != null
            && samePos(action.before.pos, action.target)
            && current.key !== action.before.key
          : samePos(current.pos, action.target)
            && current.key !== action.before.key
            && !isAirState(current.blockState);
        if (changed) {
          action.stateTransitionAt = now();
          action.confirmedAt = Math.max(action.transportConfirmationAt, action.stateTransitionAt);
        }
      }
    };
    const serializeAction = (action) => ({
      id: action.id,
      type: action.type,
      clicked: action.clicked,
      target: action.target,
      before: action.before,
      armedAt: action.armedAt,
      workloadActiveAtArm: action.workloadActiveAtArm === true,
      emittedAt: action.emittedAt ?? null,
      emittedPacketId: action.emittedPacketId ?? null,
      emittedSequence: action.emittedSequence ?? null,
      workloadActiveAtEmission: action.workloadActiveAtEmission === true,
      authoritativeStateAt: action.authoritativeStateAt ?? null,
      transportConfirmationAt: action.transportConfirmationAt ?? null,
      confirmationKind: action.confirmationKind ?? null,
      stateTransitionAt: action.stateTransitionAt ?? null,
      confirmedAt: action.confirmedAt ?? null,
      rollbackAt: action.rollbackAt ?? null,
      lastInboundStateId: action.lastInboundStateId ?? null,
    });
    const snapshot = () => {
      for (const action of state.actions) observeAction(action);
      const breaks = state.actions.filter((action) => action.type === 'break');
      const places = state.actions.filter((action) => action.type === 'place');
      const latency = (action) => action.confirmedAt != null && action.emittedAt != null
        ? action.confirmedAt - action.emittedAt : null;
      const transportLatency = (action) => action.transportConfirmationAt != null
          && action.emittedAt != null
        ? action.transportConfirmationAt - action.emittedAt : null;
      return {
        installed: state.installed,
        source: state.source,
        route: state.route,
        protocolVersion: state.protocolVersion,
        packetIds: state.packetIds,
        channelIds: state.channelIds,
        workerSessionIds: state.workerSessionIds,
        measurementStartedAt: state.measurementStartedAt,
        measurementEndedAt: state.measurementEndedAt,
        transportObserved: state.outboundTransportCalls > 0 && state.inboundTransportCalls > 0,
        outboundTransportCalls: state.outboundTransportCalls,
        inboundTransportCalls: state.inboundTransportCalls,
        outboundTransportBytes: state.outboundTransportBytes,
        inboundTransportBytes: state.inboundTransportBytes,
        parsedOutboundPackets: state.parsedOutboundPackets,
        parsedInboundPackets: state.parsedInboundPackets,
        compressedFrames: state.compressedFrames,
        parserFailures: state.parserFailures,
        breakEmittedCount: breaks.filter((action) => action.emittedAt != null).length,
        placeEmittedCount: places.filter((action) => action.emittedAt != null).length,
        breakConfirmationCount: breaks.filter((action) => action.confirmedAt != null).length,
        placeConfirmationCount: places.filter((action) => action.confirmedAt != null).length,
        breakTransportConfirmationCount: breaks.filter((action) => action.transportConfirmationAt != null).length,
        placeTransportConfirmationCount: places.filter((action) => action.transportConfirmationAt != null).length,
        breakStateTransitionCount: breaks.filter((action) => action.stateTransitionAt != null).length,
        placeStateTransitionCount: places.filter((action) => action.stateTransitionAt != null).length,
        breakAcknowledgementMillis: breaks.map(latency).filter((value) => value != null),
        placeAcknowledgementMillis: places.map(latency).filter((value) => value != null),
        breakTransportMillis: breaks.map(transportLatency).filter((value) => value != null),
        placeTransportMillis: places.map(transportLatency).filter((value) => value != null),
        rollbacks: state.rollbacks,
        actions: state.actions.map(serializeAction),
      };
    };
    const reset = () => {
      state.actions = [];
      state.outboundTransportCalls = 0;
      state.inboundTransportCalls = 0;
      state.outboundTransportBytes = 0;
      state.inboundTransportBytes = 0;
      state.parsedOutboundPackets = 0;
      state.parsedInboundPackets = 0;
      state.compressedFrames = 0;
      state.parserFailures = 0;
      state.streams = {outbound: new Uint8Array(0), inbound: new Uint8Array(0)};
      state.measurementStartedAt = now();
      state.measurementEndedAt = null;
      state.rollbacks = 0;
    };
    const arm = (type) => {
      if (type !== 'break' && type !== 'place') return null;
      if (state.actions.length >= maxActions) return null;
      const hit = globalThis.__gaiusMinecraftState?.hit;
      if (!hit || String(hit.type || '').toUpperCase() !== 'BLOCK') return null;
      const clicked = positionFromText(hit.blockPos);
      const delta = directionDelta(hit.direction);
      if (!clicked || !delta || !hit.blockState) return null;
      const before = stateSnapshot();
      const target = type === 'break' ? clicked : addPos(clicked, delta);
      const action = {
        id: state.actions.length + 1,
        type,
        clicked,
        target,
        before,
        armedAt: now(),
        workloadActiveAtArm: globalThis.__gaiusBenchmarkWorkloadActive === true,
        emittedAt: null,
        emittedPacketId: null,
        emittedSequence: null,
        workloadActiveAtEmission: false,
        authoritativeStateAt: null,
        transportConfirmationAt: null,
        stateTransitionAt: null,
        confirmedAt: null,
      };
      state.actions.push(action);
      return {id: action.id, type, clicked, target, before};
    };
    const observe = (id) => {
      const action = state.actions.find((item) => item.id === Number(id));
      observeAction(action);
      return action ? serializeAction(action) : null;
    };
    const finish = () => {
      if (state.measurementEndedAt == null) state.measurementEndedAt = now();
      return snapshot();
    };

    const originalSend = bridge.send;
    bridge.send = function gameplayAuthoritySend(id, data) {
      if (localChannelIds.includes(Number(id))) {
        const bytes = asBytes(data);
        state.outboundTransportCalls++;
        state.outboundTransportBytes += bytes?.byteLength || 0;
        appendStream('outbound', bytes);
      }
      return originalSend.apply(this, arguments);
    };
    const originalDeliverInbound = bridge.deliverInbound;
    bridge.deliverInbound = function gameplayAuthorityDeliverInbound(entry, data) {
      if (isLocalEntry(entry)) {
        const bytes = asBytes(data);
        state.inboundTransportCalls++;
        state.inboundTransportBytes += bytes?.byteLength || 0;
        appendStream('inbound', bytes);
      }
      return originalDeliverInbound.apply(this, arguments);
    };
    state.reset = reset;
    state.arm = arm;
    state.observe = observe;
    state.snapshot = snapshot;
    state.finish = finish;
    globalThis.__gaiusGameplayAuthorityProbe = state;
    reset();
    return snapshot();
  })()`);
}

async function waitForGameplayAction(session, id, timeoutMillis = 5_000) {
  const deadline = Date.now() + timeoutMillis;
  let last = null;
  while (Date.now() < deadline) {
    last = await evaluate(session,
      "globalThis.__gaiusGameplayAuthorityProbe?.observe(" + JSON.stringify(id) + ") || null",
      5_000,
    );
    if (last?.confirmedAt != null) return last;
    await sleep(25);
  }
  return last;
}

async function exerciseGameplayAuthority(session, center, travelController = null) {
  const requestedSamples = Math.max(
    1,
    Math.min(8, Number(resolvedGameplayAuthorityContract.minimumSamplesPerOperation || 1)),
  );
  const installed = await evaluate(session,
    "globalThis.__gaiusGameplayAuthorityProbe?.installed === true",
  );
  if (!installed) return {verdict: "inconclusive", reason: "probe-not-installed"};

  const results = [];
  if (typeof travelController?.pause === "function") {
    await travelController.pause();
  }
  try {
    for (let index = 0; index < requestedSamples; index++) {
    const place = await evaluate(session,
      "globalThis.__gaiusGameplayAuthorityProbe?.arm('place') || null",
    );
    if (place?.id != null) {
      await clickMouseButton(session, center.x, center.y, "right");
      results.push({type: "place", action: await waitForGameplayAction(session, place.id)});
      await sleep(100);
    }
    const broken = await evaluate(session,
      "globalThis.__gaiusGameplayAuthorityProbe?.arm('break') || null",
    );
    if (broken?.id != null) {
      await session.send("Input.dispatchMouseEvent", {
        type: "mouseMoved", x: center.x, y: center.y, button: "none",
      });
      await session.send("Input.dispatchMouseEvent", {
        type: "mousePressed", x: center.x, y: center.y, button: "left", buttons: 1,
        clickCount: 1,
      });
      await sleep(120);
      await session.send("Input.dispatchMouseEvent", {
        type: "mouseReleased", x: center.x, y: center.y, button: "left", buttons: 0,
        clickCount: 1,
      });
      results.push({type: "break", action: await waitForGameplayAction(session, broken.id)});
      await sleep(100);
    }
    }
  } finally {
    if (typeof travelController?.resume === "function") {
      await travelController.resume();
    }
  }
  await sleep(250);
  const evidence = await evaluate(session,
    "globalThis.__gaiusGameplayAuthorityProbe?.snapshot() || null",
  );
  return {evidence, results};
}

async function startTravel(session, center) {
  await evaluate(session, "globalThis.__gaiusBenchmarkWorkloadActive=true;true");
  await dispatchKey(session, "Space", "keyDown");
  await dispatchKey(session, "Space", "keyUp");
  await sleep(120);
  await dispatchKey(session, "Space", "keyDown");
  await dispatchKey(session, "Space", "keyUp");
  await dispatchKey(session, "ControlLeft", "keyDown");
  await dispatchKey(session, "Space", "keyDown");
  await dispatchKey(session, "KeyW", "keyDown");
  let steering = 0;
  let paused = false;
  let stallTicks = 0;
  let lastX = null;
  let lastZ = null;
  const timer = setInterval(async () => {
    if (paused) return;
    steering++;
    const x = center.x + Math.min(160, steering * 2);
    session.send("Input.dispatchMouseEvent", {
      type: "mouseMoved",
      x,
      y: center.y,
      button: "none",
    }).catch(() => {});
    if (steering >= 80) steering = 0;

    // Stall detection: some spawns put a hill/cliff directly ahead and the
    // player walks into it forever. If it has not advanced for a few seconds,
    // swing the camera hard and re-tap jump to escape instead of stalling the
    // traversal (which needs to cross 16 chunks / 256 blocks).
    stallTicks++;
    if (stallTicks >= 4) {
      stallTicks = 0;
      try {
        const pos = await evaluate(session,
          "(() => { const s = window.__gaiusMinecraftState || {}; const p = s.player; return p ? { x: p.x, z: p.z } : null; })()");
        if (pos) {
          if (lastX != null && lastZ != null
              && Math.abs(pos.x - lastX) + Math.abs(pos.z - lastZ) < 1.0) {
            steering = (steering + 40) % 80;
            await session.send("Input.dispatchMouseEvent", {
              type: "mouseMoved",
              x: Math.min(center.x + 420, 1260),
              y: center.y,
              button: "none",
            }).catch(() => {});
            await dispatchKey(session, "Space", "keyUp").catch(() => {});
            await dispatchKey(session, "Space", "keyDown").catch(() => {});
          }
          lastX = pos.x;
          lastZ = pos.z;
        }
      } catch (ignored) {}
    }
  }, 1000);
  const stop = async () => {
    clearInterval(timer);
    for (const code of ["KeyW", "Space", "ControlLeft"]) {
      try {
        await dispatchKey(session, code, "keyUp");
      } catch (ignored) {
      }
    }
    try {
      await evaluate(session, "globalThis.__gaiusBenchmarkWorkloadActive=false;true");
    } catch (ignored) {
    }
  };
  stop.pause = async () => {
    if (paused) return;
    paused = true;
    await dispatchKey(session, "KeyW", "keyUp");
    await dispatchKey(session, "Space", "keyUp");
    await evaluate(session, "globalThis.__gaiusBenchmarkWorkloadActive=false;true");
  };
  stop.resume = async () => {
    if (!paused) return;
    await dispatchKey(session, "Space", "keyDown");
    await dispatchKey(session, "KeyW", "keyDown");
    await evaluate(session, "globalThis.__gaiusBenchmarkWorkloadActive=true;true");
    paused = false;
  };
  return stop;
}

async function leaveWorld(session) {
  await dispatchKey(session, "Escape", "keyDown");
  await dispatchKey(session, "Escape", "keyUp");
  await waitFor(
    session,
    "String(window.__gaiusMinecraftState?.screen||'').includes('PauseScreen')",
    10_000,
    "the pause screen",
  );
  const clicked = await clickButton(session, "Save and Quit to Title", 10_000)
    || await clickButton(session, "Disconnect", 2_000);
  if (!clicked) throw new Error("The leave-world button was not exposed by UI telemetry");
  await waitFor(
    session,
    "String(window.__gaiusMinecraftState?.screen||'').endsWith('TitleScreen')"
      + "&&!window.__gaiusMinecraftState?.level",
    60_000,
    "the title screen after leaving the world",
  );
}

async function resetMeasurement(session) {
  return evaluate(session, "(() => {"
    + "globalThis.__gaiusBenchmarkEnabled=true;"
    + "const measurementId=globalThis.crypto&&typeof globalThis.crypto.randomUUID==='function'"
    + "?globalThis.crypto.randomUUID():"
    + "String(Date.now())+'-'+Math.random().toString(16).slice(2);"
    + "globalThis.__gaiusBenchmarkMeasurementId=measurementId;"
    + "globalThis.__gaiusWorkerMessageTelemetry=null;"
    + "const gameplayAuthority=globalThis.__gaiusGameplayAuthorityProbe;"
    + "if(gameplayAuthority&&typeof gameplayAuthority.reset==='function')gameplayAuthority.reset();"
    + "globalThis.__gaiusFrameTelemetry={enabled:true,lastFrameAt:performance.now(),"
    + "startedAt:performance.now(),frameCount:0,totalFrameMillis:0,longestFrameMillis:0,"
    + "freezeCount:0,hiddenFrameCount:0,visibleFrameCount:0,swapInterval:null,"
    + "uncappedYieldCount:0,vsyncYieldCount:0,presentToRafCount:0,fairYieldCount:0,"
    + "messageChannelCreateFailureCount:0,messageChannelPostFailureCount:0,"
    + "messageChannelRebuildCount:0,cancelledMessageTaskCount:0,watchdogYieldCount:0,"
    + "yieldRequestCount:0,"
    + "yieldCompletionCount:0,pendingYieldCount:0,maxPendingYieldCount:0,"
    + "duplicateYieldCallbackCount:0,sampleCapacity:"
    + JSON.stringify(frameSampleCapacity) + ","
    + "sampleWriteIndex:0,sampleCount:0,frameTimes:new Float32Array("
    + JSON.stringify(frameSampleCapacity) + "),"
    + "histogram:new Uint32Array(4001)};"
    + "globalThis.__gaiusBenchmarkFrameDrainCount=0;"
    + "globalThis.__gaiusTargetingTelemetry={updates:0,skips:0,lastAt:0};"
    + "const pipeline=globalThis.__gaiusChunkPipelineTelemetry;"
    + "if(pipeline){"
    + "if(pipeline.taskHistogram)pipeline.taskHistogram.fill(0);"
    + "if(pipeline.uploadPassHistogram)pipeline.uploadPassHistogram.fill(0);"
    + "pipeline.taskHistogramCount=Number(pipeline.completedTasks)||0;"
    + "pipeline.uploadPassHistogramCount=Number(pipeline.uploadPasses)||0;"
    + "}"
    + "const workers=globalThis.__gaiusSingleplayerWorkers;"
    + "if(workers&&typeof workers.forEach==='function')workers.forEach(worker=>{"
    + "if(worker&&typeof worker.__gaiusResetTelemetry==='function')"
    + "worker.__gaiusResetTelemetry(measurementId);"
    + "if(worker&&typeof worker.__gaiusSetTelemetryInterval==='function')"
    + "worker.__gaiusSetTelemetryInterval(" + JSON.stringify(heartbeatIntervalMillis) + ");"
    + "});"
    + "if(globalThis.__gaiusBenchmarkLongTaskObserver){"
    + "try{globalThis.__gaiusBenchmarkLongTaskObserver.disconnect();}catch(ignored){}"
    + "}"
    + "const longTaskCapacity=" + JSON.stringify(longTaskCapacity) + ";"
    + "globalThis.__gaiusBenchmarkLongTasks={capacity:longTaskCapacity,"
    + "entries:new Array(longTaskCapacity),writeIndex:0,count:0,totalCount:0,"
    + "longestMillis:0,overFreezeCount:0};"
    + "try{"
    + "const longTaskObserver=new PerformanceObserver(list=>{"
    + "for(const entry of list.getEntries()){"
    + "const state=globalThis.__gaiusBenchmarkLongTasks;if(!state)continue;"
    + "const duration=Number(entry.duration)||0;const index=state.writeIndex%state.capacity;"
    + "state.entries[index]={startTime:Number(entry.startTime)||0,duration};"
    + "state.writeIndex=(index+1)%state.capacity;state.count=Math.min(state.capacity,state.count+1);"
    + "state.totalCount++;state.longestMillis=Math.max(state.longestMillis,duration);"
    + "if(duration>=500)state.overFreezeCount++;"
    + "}"
    + "});longTaskObserver.observe({type:'longtask',buffered:false});"
    + "globalThis.__gaiusBenchmarkLongTaskObserver=longTaskObserver;"
    + "}catch(ignored){}"
    + "const minecraftEvents=globalThis.__gaiusMinecraftEvents||[];"
    + "const seenMinecraftEvents=new WeakSet();for(const item of minecraftEvents){"
    + "if(item&&typeof item==='object')seenMinecraftEvents.add(item);}"
    + "globalThis.__gaiusBenchmarkMinecraftEventSeen=seenMinecraftEvents;"
    + "globalThis.__gaiusBenchmarkEventStartAt=Date.now();"
    + "const oldProbe=globalThis.__gaiusBenchmarkProbe;"
    + "if(oldProbe&&oldProbe.heartbeatTimer)clearInterval(oldProbe.heartbeatTimer);"
    + "const probe=globalThis.__gaiusBenchmarkProbe={"
    + "generation:Number(oldProbe&&oldProbe.generation||0)+1,"
    + "startedAt:performance.now(),heartbeatLastAt:performance.now(),heartbeatGaps:[],"
    + "heartbeatFreezeCount:0,rafLastAt:0,rafIntervals:new Float32Array(4096),"
    + "rafWriteIndex:0,rafCount:0,contextLosses:[]};"
    + "probe.heartbeatTimer=setInterval(()=>{"
    + "const now=performance.now();const gap=Math.max(0,now-probe.heartbeatLastAt);"
    + "if(gap>=500){probe.heartbeatFreezeCount++;probe.heartbeatGaps.push(gap);"
    + "if(probe.heartbeatGaps.length>4096)probe.heartbeatGaps.splice(0,probe.heartbeatGaps.length-4096);}"
    + "probe.heartbeatLastAt=now;}," + JSON.stringify(heartbeatIntervalMillis) + ");"
    + "requestAnimationFrame(function benchmarkRaf(now){"
    + "if(globalThis.__gaiusBenchmarkProbe!==probe)return;"
    + "if(probe.rafLastAt>0){const delta=Math.max(0,now-probe.rafLastAt);"
    + "const index=probe.rafWriteIndex%probe.rafIntervals.length;"
    + "probe.rafIntervals[index]=delta;probe.rafWriteIndex=(index+1)%probe.rafIntervals.length;"
    + "probe.rafCount=Math.min(probe.rafIntervals.length,probe.rafCount+1);}"
    + "probe.rafLastAt=now;requestAnimationFrame(benchmarkRaf);});"
    + "if(!globalThis.__gaiusBenchmarkContextLossInstalled){"
    + "globalThis.__gaiusBenchmarkContextLossInstalled=true;"
    + "document.addEventListener('webglcontextlost',event=>{"
    + "const active=globalThis.__gaiusBenchmarkProbe;if(active)active.contextLosses.push({"
    + "at:Date.now(),statusMessage:String(event.statusMessage||'')});"
    + "if(active&&active.contextLosses.length>64)active.contextLosses.splice(0,"
    + "active.contextLosses.length-64);},true);}"
    + "return {measurementId,distances:workers&&typeof workers.values==='function'"
    + "?Array.from(workers.values()).filter(worker=>worker&&!worker.__gaiusTerminal)"
    + ".map(worker=>String(worker.__gaiusDistances||'')):[]};"
    + "})()");
}

async function collectWorkerDistanceEvidence(session) {
  return evaluate(session, "(() => {"
    + "const naturalRaw=Array.isArray(globalThis.__gaiusBenchmarkNaturalWorkerMessages)"
    + "?globalThis.__gaiusBenchmarkNaturalWorkerMessages.slice(-64):[];"
    + "const harnessRaw=Array.isArray(globalThis.__gaiusBenchmarkWorkerOverrides)"
    + "?globalThis.__gaiusBenchmarkWorkerOverrides.slice(-64):[];"
    + "const naturalState=globalThis.__gaiusBenchmarkNaturalWorkerEvidenceState||{};"
    + "const harnessState=globalThis.__gaiusBenchmarkHarnessOverrideEvidenceState||{};"
    + "const workers=globalThis.__gaiusSingleplayerWorkers;"
    + "const workerStates=workers&&typeof workers.entries==='function'"
    + "?Array.from(workers.entries()).map(([id,item])=>({sessionId:String(id),"
    + "terminal:!!item?.__gaiusTerminal,distances:String(item?.__gaiusDistances||''),"
    + "workerSequence:Number(item?.__gaiusBenchmarkWorkerSequence)||null})) : [];"
    + "return {"
    + "naturalWorkerEvidence:{mode:'natural-observation',messages:naturalRaw,"
    + "rawMessages:naturalRaw,"
    + "truncatedCount:Number(naturalState.truncatedCount)||0,complete:Number(naturalState.truncatedCount||0)===0},"
    + "harnessOverrideEvidence:{enabled:globalThis.__gaiusBenchmarkWorkerDistanceOverride===true,"
    + "mode:globalThis.__gaiusBenchmarkWorkerDistanceOverride===true?'harness-pin-diagnostic':'disabled',"
    + "messages:harnessRaw,overrides:harnessRaw,truncatedCount:Number(harnessState.truncatedCount)||0,"
    + "releaseEligible:false},"
    + "workerStates,workerRegistrySize:workers&&typeof workers.size==='number'?workers.size:0};"
    + "})()");
}

async function samplePage(session, drainFrames = true) {
  const startedAt = Date.now();
  try {
    const value = await evaluate(session, "(() => {"
      + "const state=globalThis.__gaiusMinecraftState||{};"
      + "const player=state.player||null;"
      + "const pipeline=globalThis.__gaiusChunkPipelineTelemetry||{};"
      + "const worker=globalThis.__gaiusWorkerMessageTelemetry||{};"
      + "const network=globalThis.__gaiusNetworkStats||{};"
      + "const frame=globalThis.__gaiusFrameTelemetry||{};"
      + "const ring=frame.frameTimes;"
      + "const capacity=ring&&ring.length?ring.length:0;"
      + "const totalFrameCount=Number(frame.frameCount)||0;"
      + "const shouldDrain=" + JSON.stringify(Boolean(drainFrames)) + ";"
      + "const previousDrainCount=Number(globalThis.__gaiusBenchmarkFrameDrainCount)||0;"
      + "const delta=Math.max(0,totalFrameCount-previousDrainCount);"
      + "const available=shouldDrain?Math.min(delta,Number(frame.sampleCount)||0,capacity):0;"
      + "const writeIndex=capacity?((Number(frame.sampleWriteIndex)||0)%capacity):0;"
      + "const startIndex=capacity?(writeIndex-available+capacity)%capacity:0;"
      + "const frameTimes=[];"
      + "for(let index=0;index<available;index++)"
      + "frameTimes.push(Number(ring[(startIndex+index)%capacity])||0);"
      + "if(shouldDrain)globalThis.__gaiusBenchmarkFrameDrainCount=totalFrameCount;"
      + "const scalarSnapshot=value=>{const result={};"
      + "if(!value||typeof value!=='object')return result;"
      + "for(const [key,item] of Object.entries(value)){"
      + "if(typeof item==='number'&&Number.isFinite(item))result[key]=item;"
      + "else if(typeof item==='boolean'||typeof item==='string'||item===null)result[key]=item;}"
      + "return result;};"
      + "const targeting=globalThis.__gaiusTargetingTelemetry||{};"
      + "const targetingSnapshot=scalarSnapshot(targeting);"
      + "const observedFrame=Number(targeting.lastObservationFrame);"
      + "const visibleFrame=Number(frame.visibleFrameCount);"
      + "if(Number.isFinite(observedFrame)&&Number.isFinite(visibleFrame))"
      + "targetingSnapshot.maxObservationLagFrames=Math.max("
      + "Number(targetingSnapshot.maxObservationLagFrames)||0,"
      + "Math.max(0,visibleFrame-observedFrame));"
      + "const targetingRing=targeting.ring;"
      + "if(targetingRing&&targetingRing.blockX instanceof Int32Array){"
      + "targetingSnapshot.ringCapacity=targetingRing.blockX.length;"
      + "targetingSnapshot.ringCount=Math.min(targetingRing.blockX.length,"
      + "Math.max(0,Number(targetingRing.count)||0));}"
      + "const glStats=scalarSnapshot(globalThis.__gaiusGLStats||{});"
      + "const framePacing=scalarSnapshot(frame);"
      + "const longTasks=globalThis.__gaiusBenchmarkLongTasks||{};"
      + "const workers=globalThis.__gaiusSingleplayerWorkers;"
      + "const workerEntries=workers&&typeof workers.entries==='function'?Array.from(workers.entries()):[];"
      + "const workerStates=workerEntries.map(([id,item])=>({sessionId:String(id),"
      + "terminal:!!item?.__gaiusTerminal,runtimeReady:!!item?.__gaiusRuntimeReady,"
      + "serverReady:!!item?.__gaiusServerReady,stopped:!!item?.__gaiusStopped,"
      + "distances:String(item?.__gaiusDistances||'')}));"
      + "const eventRing=globalThis.__gaiusMinecraftEvents||[];"
      + "const eventSeen=globalThis.__gaiusBenchmarkMinecraftEventSeen||new WeakSet();"
      + "globalThis.__gaiusBenchmarkMinecraftEventSeen=eventSeen;"
      + "const unseenEvents=eventRing.filter(item=>!item||typeof item!=='object'||!eventSeen.has(item));"
      + "for(const item of unseenEvents){if(item&&typeof item==='object')eventSeen.add(item);}"
      + "const minecraftEvents=unseenEvents.map(item=>item&&typeof item==='object'"
      + "?Object.assign({},item):{event:'unknown',detail:String(item),at:Date.now()});"
      + "const loadedChunkCandidates=[state.loadedChunkCount,state.chunkCount,pipeline.loadedChunks,"
      + "worker.chunkPriority?.loadedChunkCount,worker.chunkPriority?.loadedChunks];"
      + "const loadedChunkCount=loadedChunkCandidates.map(Number).find(Number.isFinite);"
      + "const gl=globalThis.__gaiusGL||{};const audio=globalThis.__gaiusOpenAL||{};"
      + "const gameplayAuthority=globalThis.__gaiusGameplayAuthorityProbe;"
      + "const resourceEntries=performance.getEntriesByType('resource');"
      + "return {"
      + "now:performance.now(),"
      + "visibilityState:document.visibilityState,hasFocus:document.hasFocus(),"
      + "workloadActive:globalThis.__gaiusBenchmarkWorkloadActive===true,"
      + "devicePixelRatio:Number(devicePixelRatio)||1,"
      + "viewport:{width:innerWidth,height:innerHeight},"
      + "visualViewport:globalThis.visualViewport?{width:Number(visualViewport.width)||0,"
      + "height:Number(visualViewport.height)||0,scale:Number(visualViewport.scale)||0,"
      + "offsetLeft:Number(visualViewport.offsetLeft)||0,"
      + "offsetTop:Number(visualViewport.offsetTop)||0}:null,"
      + "canvas:(()=>{const value=document.querySelector('canvas');return value?"
      + "{width:value.width,height:value.height,clientWidth:value.clientWidth,"
      + "clientHeight:value.clientHeight}:null;})(),"
      + "stateAt:Number.isFinite(Number(state.at))?Number(state.at):null,"
      + "screen:Object.hasOwn(state,'screen')?(state.screen||null):null,"
      + "overlay:Object.hasOwn(state,'overlay')?(state.overlay||null):null,"
      + "level:Object.hasOwn(state,'level')?(state.level||null):null,"
      + "noRender:Object.hasOwn(state,'noRender')?!!state.noRender:null,"
      + "running:Object.hasOwn(state,'running')?!!state.running:null,"
      + "pause:Object.hasOwn(state,'pause')?!!state.pause:null,player,"
      + "loadedChunkCount:Number.isFinite(loadedChunkCount)?loadedChunkCount:null,"
      + "chunk:player&&Number.isFinite(player.x)&&Number.isFinite(player.z)"
      + "?{x:Math.floor(player.x/16),z:Math.floor(player.z/16)}:null,"
      + "frame:{frameCount:Number(frame.frameCount)||0,totalFrameMillis:Number(frame.totalFrameMillis)||0,"
      + "longestFrameMillis:Number(frame.longestFrameMillis)||0,"
      + "freezeCount:Number(frame.freezeCount)||0,hiddenFrameCount:Number(frame.hiddenFrameCount)||0,"
      + "visibleFrameCount:Number(frame.visibleFrameCount)||0,frameTimes,"
      + "swapInterval:typeof frame.swapInterval==='number'&&Number.isFinite(frame.swapInterval)?frame.swapInterval:null,"
      + "uncappedYieldCount:typeof frame.uncappedYieldCount==='number'&&Number.isFinite(frame.uncappedYieldCount)?frame.uncappedYieldCount:null,"
      + "vsyncYieldCount:typeof frame.vsyncYieldCount==='number'&&Number.isFinite(frame.vsyncYieldCount)?frame.vsyncYieldCount:null,"
      + "presentToRafCount:typeof frame.presentToRafCount==='number'&&Number.isFinite(frame.presentToRafCount)?frame.presentToRafCount:null,"
      + "fairYieldCount:typeof frame.fairYieldCount==='number'&&Number.isFinite(frame.fairYieldCount)?frame.fairYieldCount:null,"
      + "messageChannelCreateFailureCount:typeof frame.messageChannelCreateFailureCount==='number'&&Number.isFinite(frame.messageChannelCreateFailureCount)?frame.messageChannelCreateFailureCount:null,"
      + "messageChannelPostFailureCount:typeof frame.messageChannelPostFailureCount==='number'&&Number.isFinite(frame.messageChannelPostFailureCount)?frame.messageChannelPostFailureCount:null,"
      + "messageChannelRebuildCount:typeof frame.messageChannelRebuildCount==='number'&&Number.isFinite(frame.messageChannelRebuildCount)?frame.messageChannelRebuildCount:null,"
      + "cancelledMessageTaskCount:typeof frame.cancelledMessageTaskCount==='number'&&Number.isFinite(frame.cancelledMessageTaskCount)?frame.cancelledMessageTaskCount:null,"
      + "watchdogYieldCount:typeof frame.watchdogYieldCount==='number'&&Number.isFinite(frame.watchdogYieldCount)?frame.watchdogYieldCount:null,"
      + "lostFrameTimes:shouldDrain?Math.max(0,delta-available):0},"
      + "pipeline:{pendingTasks:Number(pipeline.pendingTasks)||0,"
      + "queueCapacity:Number(pipeline.queueCapacity)||0,"
      + "highWaterActive:!!pipeline.highWaterActive,"
      + "currentHighWaterMillis:Number(pipeline.currentHighWaterMillis)||0,"
      + "totalHighWaterMillis:Number(pipeline.totalHighWaterMillis)||0,"
      + "longestHighWaterMillis:Number(pipeline.longestHighWaterMillis)||0,"
      + "compileBacklog:Number(pipeline.compileBacklog)||0,"
      + "uploadBacklog:Number(pipeline.uploadBacklog)||0,"
      + "droppedTasks:Number(pipeline.droppedTasks)||0,"
      + "completedTasks:Number(pipeline.completedTasks)||0,"
      + "backpressureEvents:Number(pipeline.backpressureEvents)||0,"
      + "overBudgetTasks:Number(pipeline.overBudgetTasks)||0,"
      + "lastTaskMillis:Number(pipeline.lastTaskMillis)||0,"
      + "lastUploadPassMillis:Number(pipeline.lastUploadPassMillis)||0},"
      + "worker:Object.assign({},worker),"
      + "workerLifecycle:{count:workerStates.length,"
      + "activeCount:workerStates.filter(item=>!item.terminal).length,"
      + "terminalCount:workerStates.filter(item=>item.terminal).length,states:workerStates},"
      + "network:{queuedBytes:Number(network.queuedBytes)||0,"
      + "inboundQueuedBytes:Number(network.inboundQueuedBytes)||0,"
      + "peakInboundQueuedBytes:Number(network.peakInboundQueuedBytes)||0,"
      + "decodedPacketQueue:Number(network.decodedPacketQueue)||0,"
      + "maxDecodedPacketQueue:Number(network.maxDecodedPacketQueue)||0,"
      + "activeHighWatermarkMillis:Number(network.activeHighWatermarkMillis)||0,"
      + "longestHighWatermarkMillis:Number(network.longestHighWatermarkMillis)||0,"
      + "pumpCalls:Number(network.pumpCalls)||0,"
      + "peakPumpMillis:Number(network.peakPumpMillis)||0,"
      + "errors:Number(network.errors)||0},"
      + "targeting:targetingSnapshot,"
      + "runtimeInvariants:{glStats,targeting:targetingSnapshot,"
      + "worldgen:scalarSnapshot(worker.worldgen||{}),"
      + "workerQueue:scalarSnapshot(worker.chunkPriority||{}),"
      + "renderPipeline:scalarSnapshot(pipeline),network:scalarSnapshot(network),framePacing},"
      + "resources:{entries:resourceEntries.length,"
      + "transferBytes:resourceEntries.reduce((sum,item)=>sum+(Number(item.transferSize)||0),0),"
      + "decodedBytes:resourceEntries.reduce((sum,item)=>sum+(Number(item.decodedBodySize)||0),0),"
      + "webgl:{buffers:gl.buffers&&Number(gl.buffers.size)||0,"
      + "textures:gl.textures&&Number(gl.textures.size)||0,"
      + "framebuffers:gl.framebuffers&&Number(gl.framebuffers.size)||0,"
      + "programs:gl.programs&&Number(gl.programs.size)||0,"
      + "vertexArrays:gl.vaos&&Number(gl.vaos.size)||0},"
      + "audio:{buffers:audio.buffers&&Number(audio.buffers.size)||0,"
      + "sources:audio.sources&&Number(audio.sources.size)||0,"
      + "contextState:audio.context&&String(audio.context.state||'unknown')||null},"
      + "browserMemory:globalThis.__gaiusMemoryTelemetry?.browserMemory"
      + "?Object.assign({},globalThis.__gaiusMemoryTelemetry.browserMemory):null},"
      + "longTasks:{capacity:Number(longTasks.capacity)||0,count:Number(longTasks.count)||0,"
      + "totalCount:Number(longTasks.totalCount)||0,longestMillis:Number(longTasks.longestMillis)||0,"
      + "overFreezeCount:Number(longTasks.overFreezeCount)||0},"
      + "minecraftEvents,"
      + "gameplayAuthority:gameplayAuthority&&typeof gameplayAuthority.snapshot==='function'"
      + "?gameplayAuthority.snapshot():null,"
      + "distances:workers&&typeof workers.values==='function'"
      + "?Array.from(workers.values()).filter(item=>item&&!item.__gaiusTerminal)"
      + ".map(item=>String(item.__gaiusDistances||'')):[]"
      + "};"
      + "})()", 5000);
    return {
      at: Date.now(),
      evaluationLatencyMillis: Date.now() - startedAt,
      ...value,
    };
  } catch (error) {
    return {
      at: Date.now(),
      evaluationLatencyMillis: Date.now() - startedAt,
      error: String(error && (error.stack || error.message) || error),
    };
  }
}

async function sampleFor(
  session,
  durationMillis,
  samples,
  intervalMillis = sampleMillis,
  drainFrames = true,
) {
  const deadline = Date.now() + durationMillis;
  while (Date.now() < deadline) {
    const sampleStartedAt = Date.now();
    samples.push(await samplePage(session, drainFrames));
    const remaining = deadline - Date.now();
    if (remaining <= 0) break;
    await sleep(Math.min(remaining, Math.max(0, intervalMillis - (Date.now() - sampleStartedAt))));
  }
}

async function finalTelemetry(session) {
  const telemetry = await evaluate(session, "(() => {"
    + "const frame=globalThis.__gaiusFrameTelemetry||{};"
    + "const pipeline=globalThis.__gaiusChunkPipelineTelemetry||{};"
    + "const probe=globalThis.__gaiusBenchmarkProbe||{};"
    + "const worker=globalThis.__gaiusWorkerMessageTelemetry||{};"
    + "const workers=globalThis.__gaiusSingleplayerWorkers;"
    + "const workerEntries=workers&&typeof workers.entries==='function'?Array.from(workers.entries()):[];"
    + "const workerStates=workerEntries.map(([id,item])=>({sessionId:String(id),"
    + "terminal:!!item?.__gaiusTerminal,runtimeReady:!!item?.__gaiusRuntimeReady,"
    + "serverReady:!!item?.__gaiusServerReady,stopped:!!item?.__gaiusStopped,"
    + "distances:String(item?.__gaiusDistances||'')}));"
    + "const scalarSnapshot=value=>{const result={};"
    + "if(!value||typeof value!=='object')return result;"
    + "for(const [key,item] of Object.entries(value)){"
    + "if(typeof item==='number'&&Number.isFinite(item))result[key]=item;"
    + "else if(typeof item==='boolean'||typeof item==='string'||item===null)result[key]=item;}"
    + "return result;};"
    + "const targeting=globalThis.__gaiusTargetingTelemetry||{};"
    + "const targetingSnapshot=scalarSnapshot(targeting);"
    + "const observedFrame=Number(targeting.lastObservationFrame);"
    + "const visibleFrame=Number(frame.visibleFrameCount);"
    + "if(Number.isFinite(observedFrame)&&Number.isFinite(visibleFrame))"
    + "targetingSnapshot.maxObservationLagFrames=Math.max("
    + "Number(targetingSnapshot.maxObservationLagFrames)||0,"
    + "Math.max(0,visibleFrame-observedFrame));"
    + "const targetingRing=targeting.ring;"
    + "if(targetingRing&&targetingRing.blockX instanceof Int32Array){"
    + "targetingSnapshot.ringCapacity=targetingRing.blockX.length;"
    + "targetingSnapshot.ringCount=Math.min(targetingRing.blockX.length,"
    + "Math.max(0,Number(targetingRing.count)||0));}"
    + "const glStats=scalarSnapshot(globalThis.__gaiusGLStats||{});"
    + "const framePacing=scalarSnapshot(frame);"
    + "const longTasks=globalThis.__gaiusBenchmarkLongTasks||{};"
    + "const orderedLongTasks=(()=>{const entries=longTasks.entries||[];"
    + "const count=Math.min(Number(longTasks.count)||0,entries.length);"
    + "const end=entries.length?((Number(longTasks.writeIndex)||0)%entries.length+entries.length)%entries.length:0;"
    + "const start=entries.length?(end-count+entries.length)%entries.length:0;const result=[];"
    + "for(let index=0;index<count;index++){const item=entries[(start+index)%entries.length];"
    + "if(item)result.push(Object.assign({},item));}return result;})();"
    + "const eventRing=globalThis.__gaiusMinecraftEvents||[];"
    + "const eventSeen=globalThis.__gaiusBenchmarkMinecraftEventSeen||new WeakSet();"
    + "globalThis.__gaiusBenchmarkMinecraftEventSeen=eventSeen;"
    + "const unseenEvents=eventRing.filter(item=>!item||typeof item!=='object'||!eventSeen.has(item));"
    + "for(const item of unseenEvents){if(item&&typeof item==='object')eventSeen.add(item);}"
    + "const minecraftEvents=unseenEvents.map(item=>item&&typeof item==='object'"
    + "?Object.assign({},item):{event:'unknown',detail:String(item),at:Date.now()});"
    + "const orderedRing=(values,count,writeIndex)=>{"
    + "if(!(values instanceof Float32Array)||values.length===0)return [];"
    + "const capacity=values.length;const available=Math.min(capacity,Math.max(0,Number(count)||0));"
    + "const end=((Number(writeIndex)||0)%capacity+capacity)%capacity;"
    + "const start=(end-available+capacity)%capacity;const result=[];"
    + "for(let index=0;index<available;index++)result.push(Number(values[(start+index)%capacity])||0);"
    + "return result;};"
    + "const drained=Math.max(0,Number(globalThis.__gaiusBenchmarkFrameDrainCount)||0);"
    + "const optionsText=(()=>{try{const files=globalThis.__gaiusPersistentFiles||{};"
    + "let value=Object.prototype.hasOwnProperty.call(files,'/gaius/options.txt')"
    + "?files['/gaius/options.txt']:localStorage.getItem(" +
      JSON.stringify(activeStoragePrefix) + "+'/gaius/options.txt');"
    + "if(value==null)return null;if(value instanceof ArrayBuffer)value=new Uint8Array(value);"
    + "if(ArrayBuffer.isView(value))return new TextDecoder().decode(value);"
    + "if(typeof value==='string'){try{const binary=atob(value);const bytes=new Uint8Array(binary.length);"
    + "for(let index=0;index<binary.length;index++)bytes[index]=binary.charCodeAt(index);"
    + "return new TextDecoder().decode(bytes);}catch(ignored){return value;}}return null;"
    + "}catch(ignored){return null;}})();"
    + "const minecraftState=globalThis.__gaiusMinecraftState||{};"
    + "const gameplayAuthority=globalThis.__gaiusGameplayAuthorityProbe;"
    + "const loadedChunkCandidates=[minecraftState.loadedChunkCount,minecraftState.chunkCount,"
    + "pipeline.loadedChunks,worker.chunkPriority?.loadedChunkCount,"
    + "worker.chunkPriority?.loadedChunks];"
    + "const loadedChunkCount=loadedChunkCandidates.map(Number).find(Number.isFinite);"
    + "return {capturedAt:Date.now(),"
    + "loadedChunkCount:Number.isFinite(loadedChunkCount)?loadedChunkCount:null,"
    + "frame:{frameCount:Number(frame.frameCount)||0,"
    + "startedAt:Number(frame.startedAt)||0,endedAt:performance.now(),"
    + "totalFrameMillis:Number(frame.totalFrameMillis)||0,"
    + "longestFrameMillis:Number(frame.longestFrameMillis)||0,"
    + "freezeCount:Number(frame.freezeCount)||0,hiddenFrameCount:Number(frame.hiddenFrameCount)||0,"
    + "visibleFrameCount:Number(frame.visibleFrameCount)||0,"
    + "swapInterval:typeof frame.swapInterval==='number'&&Number.isFinite(frame.swapInterval)?frame.swapInterval:null,"
    + "uncappedYieldCount:typeof frame.uncappedYieldCount==='number'&&Number.isFinite(frame.uncappedYieldCount)?frame.uncappedYieldCount:null,"
    + "vsyncYieldCount:typeof frame.vsyncYieldCount==='number'&&Number.isFinite(frame.vsyncYieldCount)?frame.vsyncYieldCount:null,"
    + "presentToRafCount:typeof frame.presentToRafCount==='number'&&Number.isFinite(frame.presentToRafCount)?frame.presentToRafCount:null,"
    + "fairYieldCount:typeof frame.fairYieldCount==='number'&&Number.isFinite(frame.fairYieldCount)?frame.fairYieldCount:null,"
    + "messageChannelCreateFailureCount:typeof frame.messageChannelCreateFailureCount==='number'&&Number.isFinite(frame.messageChannelCreateFailureCount)?frame.messageChannelCreateFailureCount:null,"
    + "messageChannelPostFailureCount:typeof frame.messageChannelPostFailureCount==='number'&&Number.isFinite(frame.messageChannelPostFailureCount)?frame.messageChannelPostFailureCount:null,"
    + "messageChannelRebuildCount:typeof frame.messageChannelRebuildCount==='number'&&Number.isFinite(frame.messageChannelRebuildCount)?frame.messageChannelRebuildCount:null,"
    + "cancelledMessageTaskCount:typeof frame.cancelledMessageTaskCount==='number'&&Number.isFinite(frame.cancelledMessageTaskCount)?frame.cancelledMessageTaskCount:null,"
    + "watchdogYieldCount:typeof frame.watchdogYieldCount==='number'&&Number.isFinite(frame.watchdogYieldCount)?frame.watchdogYieldCount:null,"
    + "sampleCapacity:Number(frame.sampleCapacity)||0,sampleWriteIndex:Number(frame.sampleWriteIndex)||0,"
    + "sampleCount:Number(frame.sampleCount)||0,drainedFrameCount:drained,"
    + "frameTimes:frame.frameTimes?Array.from(frame.frameTimes):[],"
    + "histogram:frame.histogram?Array.from(frame.histogram):[]},"
    + "probe:{heartbeatGaps:Array.from(probe.heartbeatGaps||[]),"
    + "heartbeatFreezeCount:Number(probe.heartbeatFreezeCount)||0,"
    + "rafIntervals:orderedRing(probe.rafIntervals,probe.rafCount,probe.rafWriteIndex),"
    + "contextLosses:Array.from(probe.contextLosses||[])},"
    + "environment:{visibilityState:document.visibilityState,hasFocus:document.hasFocus(),"
    + "userAgent:navigator.userAgent,platform:navigator.userAgentData?.platform||navigator.platform||null,"
    + "hardwareConcurrency:Number(navigator.hardwareConcurrency)||null,"
    + "deviceMemory:Number(navigator.deviceMemory)||null,devicePixelRatio:Number(devicePixelRatio)||1,"
    + "viewport:{width:innerWidth,height:innerHeight},"
    + "visualViewport:globalThis.visualViewport?{width:Number(visualViewport.width)||0,"
    + "height:Number(visualViewport.height)||0,scale:Number(visualViewport.scale)||0,"
    + "offsetLeft:Number(visualViewport.offsetLeft)||0,"
    + "offsetTop:Number(visualViewport.offsetTop)||0}:null,"
    + "screen:{width:screen.width,height:screen.height,availWidth:screen.availWidth,"
    + "availHeight:screen.availHeight},"
    + "canvas:(()=>{const value=document.querySelector('canvas');return value?"
    + "{width:value.width,height:value.height,clientWidth:value.clientWidth,"
    + "clientHeight:value.clientHeight}:null;})(),"
    + "display:Object.assign({},globalThis.__gaiusDisplay||{}),"
    + "fps:Object.assign({},globalThis.__gaiusFps||{}),optionsText},"
    + "pipeline:Object.assign({},pipeline,{"
    + "taskHistogram:pipeline.taskHistogram?Array.from(pipeline.taskHistogram):[],"
    + "uploadPassHistogram:pipeline.uploadPassHistogram"
    + "?Array.from(pipeline.uploadPassHistogram):[]}),"
    + "worker:Object.assign({},worker),"
    + "workerLifecycle:{count:workerStates.length,"
    + "activeCount:workerStates.filter(item=>!item.terminal).length,"
    + "terminalCount:workerStates.filter(item=>item.terminal).length,states:workerStates},"
    + "network:Object.assign({},globalThis.__gaiusNetworkStats||{}),"
    + "targeting:targetingSnapshot,"
    + "runtimeInvariants:{glStats,targeting:targetingSnapshot,"
    + "worldgen:scalarSnapshot(worker.worldgen||{}),"
    + "workerQueue:scalarSnapshot(worker.chunkPriority||{}),"
    + "renderPipeline:scalarSnapshot(pipeline),network:scalarSnapshot(network),framePacing},"
    + "memory:{browserMemory:globalThis.__gaiusMemoryTelemetry?.browserMemory"
    + "?Object.assign({},globalThis.__gaiusMemoryTelemetry.browserMemory):null},"
    + "longTasks:{capacity:Number(longTasks.capacity)||0,count:Number(longTasks.count)||0,"
    + "totalCount:Number(longTasks.totalCount)||0,longestMillis:Number(longTasks.longestMillis)||0,"
    + "overFreezeCount:Number(longTasks.overFreezeCount)||0,recent:orderedLongTasks},"
    + "minecraftEvents,"
    + "gameplayAuthority:gameplayAuthority&&typeof gameplayAuthority.finish==='function'"
    + "?gameplayAuthority.finish():null,"
    + "state:minecraftState"
    + "};"
    + "})()", 15_000);
  const frame = telemetry.frame || {};
  const previousFrameCount = Math.max(
    0,
    Number(frame.frameCount || 0) - Number(frame.sampleCount || 0),
  );
  const recovered = recoverFrameRingDelta(frame, previousFrameCount);
  const undrainedCount = Math.min(
    recovered.samples.length,
    Math.max(0, Number(frame.frameCount || 0) - Number(frame.drainedFrameCount || 0)),
  );
  frame.frameTimes = recovered.samples;
  frame.undrainedFrameTimes = undrainedCount > 0
    ? recovered.samples.slice(recovered.samples.length - undrainedCount)
    : [];
  frame.ringRecovery = {
    wrapped: recovered.wrapped,
    lostSamples: recovered.lostSamples,
  };
  return telemetry;
}

async function collectFailureDiagnostics(session) {
  const startedAt = Date.now();
  const unavailable = (reason) => ({
    verdict: "unavailable",
    capturedAt: new Date().toISOString(),
    durationMillis: Date.now() - startedAt,
    error: String(reason && (reason.stack || reason.message) || reason),
  });
  if (!session) return unavailable("page CDP session is unavailable");

  // Keep failure collection independent from the benchmark's larger telemetry path. A
  // bounded, defensive snapshot is useful even when the page is already stalled.
  const expression = "(() => {"
    + "const limits={depth:5,nodes:2500,array:256,keys:128,string:8192};"
    + "let nodes=0;const seen=new WeakMap();let nextId=1;"
    + "const text=value=>{const result=String(value??'');return result.length>limits.string"
    + "?result.slice(0,limits.string)+'...[truncated]':result;};"
    + "const serialize=(value,depth=0)=>{"
    + "if(value===null||typeof value==='undefined'||typeof value==='boolean')return value;"
    + "if(typeof value==='string')return text(value);"
    + "if(typeof value==='number')return Number.isFinite(value)?value:String(value);"
    + "if(typeof value==='bigint')return String(value)+'n';"
    + "if(typeof value==='function')return '[Function '+text(value.name||'anonymous')+']';"
    + "if(typeof value!=='object')return text(value);"
    + "if(depth>=limits.depth)return '[DepthLimit]';"
    + "if(nodes++>=limits.nodes)return '[NodeLimit]';"
    + "if(seen.has(value))return '[Circular#'+seen.get(value)+']';"
    + "const id=nextId++;seen.set(value,id);"
    + "try{"
    + "if(value instanceof Error)return {name:text(value.name),message:text(value.message),"
    + "stack:text(value.stack)};"
    + "if(value instanceof ArrayBuffer)return {type:'ArrayBuffer',byteLength:value.byteLength};"
    + "if(ArrayBuffer.isView(value))return {type:text(value.constructor?.name||'View'),"
    + "length:Number(value.length)||0,byteLength:Number(value.byteLength)||0};"
    + "if(value instanceof Map){const result={type:'Map',size:value.size,entries:[]};"
    + "let count=0;for(const [key,item] of value){if(count++>=limits.array)break;"
    + "result.entries.push([serialize(key,depth+1),serialize(item,depth+1)]);}return result;}"
    + "if(value instanceof Set){const result={type:'Set',size:value.size,values:[]};"
    + "let count=0;for(const item of value){if(count++>=limits.array)break;"
    + "result.values.push(serialize(item,depth+1));}return result;}"
    + "if(Array.isArray(value)){const result=[];const start=Math.max(0,value.length-limits.array);"
    + "for(let index=start;index<value.length;index++)result.push(serialize(value[index],depth+1));"
    + "if(start>0)result.unshift('[...'+start+' earlier items omitted]');return result;}"
    + "const result={};let count=0;for(const key of Object.keys(value)){"
    + "if(count++>=limits.keys){result.__truncatedKeys=true;break;}"
    + "try{result[key]=serialize(value[key],depth+1);}catch(error){result[key]='[GetterError '+text(error)+']';}}"
    + "return result;"
    + "}catch(error){return '[SerializeError '+text(error)+']';}};"
    + "const workers=globalThis.__gaiusSingleplayerWorkers;const workerStates=[];"
    + "if(workers&&typeof workers.entries==='function'){"
    + "let count=0;for(const [id,item] of workers.entries()){if(count++>=limits.array)break;"
    + "workerStates.push({sessionId:text(id),terminal:!!item?.__gaiusTerminal,"
    + "runtimeReady:!!item?.__gaiusRuntimeReady,serverReady:!!item?.__gaiusServerReady,"
    + "stopped:!!item?.__gaiusStopped,"
    + "distances:text(item?.__gaiusDistances||''),state:serialize(item)});}}"
    + "const events=globalThis.__gaiusMinecraftEvents;"
    + "const eventSnapshot=Array.isArray(events)?events.slice(-limits.array):events;"
    + "const snapshot=value=>{const result=serialize(value);return typeof result==='undefined'?null:result;};"
    + "const url=(()=>{try{return text(globalThis.location?.href||'');}catch(error){return '[ReadError '+text(error)+']';}})();"
    + "const title=(()=>{try{return text(globalThis.document?.title||'');}catch(error){return '[ReadError '+text(error)+']';}})();"
    + "const bodyText=(()=>{try{return text(globalThis.document?.body?.innerText||'');}catch(error){return '[ReadError '+text(error)+']';}})();"
    + "const minecraftState=snapshot(globalThis.__gaiusMinecraftState);"
    + "const minecraftEvents=snapshot(eventSnapshot);"
    + "const workerMessageTelemetry=snapshot(globalThis.__gaiusWorkerMessageTelemetry);"
    + "const futurePumpTelemetry=snapshot(globalThis.__gaiusFuturePumpTelemetry);"
    + "return {capturedAt:Date.now(),url,title,bodyText,page:{url,title,bodyText},"
    + "minecraftState,minecraftEvents,workerMessageTelemetry,futurePumpTelemetry,"
    + "singleplayerWorkers:workerStates};"
    + "})()";
  try {
    const value = await withTimeout(
      evaluate(session, expression, Math.min(1500, cdpCommandTimeoutMillis)),
      2_000,
      "failure diagnostics",
    );
    return {
      verdict: "captured",
      durationMillis: Date.now() - startedAt,
      ...(value && typeof value === "object" ? value : {error: "page returned no diagnostic object"}),
    };
  } catch (error) {
    return unavailable(error);
  }
}

async function collectHeap(session, targetSessionId = null, forceGc = false) {
  await session.send("HeapProfiler.enable", {}, targetSessionId);
  let gcSupported = null;
  let gcError = null;
  if (forceGc) {
    try {
      await session.send("HeapProfiler.collectGarbage", {}, targetSessionId);
      gcSupported = true;
      await sleep(100);
    } catch (error) {
      gcSupported = false;
      gcError = String(error && (error.stack || error.message) || error);
    }
  }
  const heap = await session.send("Runtime.getHeapUsage", {}, targetSessionId);
  return {
    usedSize: Number(heap.usedSize) || 0,
    totalSize: Number(heap.totalSize) || 0,
    embedderHeapUsedSize: Number(heap.embedderHeapUsedSize) || 0,
    backingStorageSize: Number(heap.backingStorageSize) || 0,
    postGc: forceGc,
    gcSupported,
    gcError,
  };
}

async function collectBrowserMemory(session, targetSessionId = null) {
  const result = await session.send("Runtime.evaluate", {
    expression: "(() => {const value=globalThis.__gaiusMemoryTelemetry?.browserMemory;"
      + "return value?Object.assign({},value):null;})()",
    returnByValue: true,
  }, targetSessionId);
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description
      || result.exceptionDetails.text
      || "BrowserMemory telemetry evaluation failed");
  }
  return result.result?.value || null;
}

async function collectWorkerHeap(session, forceGc = false) {
  const targets = await session.send("Target.getTargets");
  const workers = (targets.targetInfos || []).filter((target) => target.type === "worker");
  workers.sort((left, right) => {
    const exact = (target) => /singleplayer-server-worker|Gaius Integrated Server/i.test(
      `${String(target.title || "")} ${String(target.url || "")}`,
    ) ? 1 : 0;
    return exact(right) - exact(left);
  });
  for (const workerTarget of workers) {
    if (!workerTarget?.targetId) continue;
    const attached = await session.send("Target.attachToTarget", {
      targetId: workerTarget.targetId,
      flatten: true,
    });
    try {
      await session.send("Runtime.enable", {}, attached.sessionId);
      const marker = await session.send("Runtime.evaluate", {
        expression: "(() => ({isIntegratedServer:"
          + "typeof globalThis.__gaiusServerSessionId==='string'"
          + "&&globalThis.__gaiusServerSessionId.length>0"
          + "&&typeof globalThis.__gaiusServerWorldId==='string',"
          + "sessionId:String(globalThis.__gaiusServerSessionId||''),"
          + "worldId:String(globalThis.__gaiusServerWorldId||'')}))()",
        returnByValue: true,
      }, attached.sessionId);
      if (marker.result?.value?.isIntegratedServer !== true) continue;
      const heap = await collectHeap(session, attached.sessionId, forceGc);
      heap.browserMemory = await collectBrowserMemory(session, attached.sessionId);
      heap.workerIdentity = {
        targetId: workerTarget.targetId,
        title: workerTarget.title || null,
        url: workerTarget.url || null,
        sessionId: marker.result.value.sessionId,
        worldId: marker.result.value.worldId,
      };
      return heap;
    } finally {
      await session.send("Target.detachFromTarget", {sessionId: attached.sessionId}).catch(() => {});
    }
  }
  return null;
}

async function collectCombinedBrowserMemory(session) {
  let page = null;
  let worker = null;
  const errors = [];
  try {
    page = await collectBrowserMemory(session);
  } catch (error) {
    errors.push(`page: ${String(error && (error.message || error) || error)}`);
  }
  try {
    worker = (await collectWorkerHeap(session, false))?.browserMemory || null;
  } catch (error) {
    errors.push(`worker: ${String(error && (error.message || error) || error)}`);
  }
  return {
    combined: combineBrowserMemory(page, worker),
    page,
    worker,
    errors,
  };
}

async function collectHeapTrend(
  session,
  durationMillis,
  sampleIntervalMillis,
  postGcIntervalMillis,
  phase = "soak",
) {
  const samples = [];
  const startedAt = Date.now();
  const deadline = Date.now() + durationMillis;
  let nextPostGcAt = startedAt;
  while (true) {
    const at = Date.now();
    const forceGc = at >= nextPostGcAt;
    if (forceGc) {
      nextPostGcAt += Math.max(1000, postGcIntervalMillis);
    }
    const sample = {phase, at, atMillis: at - startedAt, postGc: forceGc};
    try {
      sample.page = await collectHeap(session, null, forceGc);
      sample.page.browserMemory = await collectBrowserMemory(session);
    } catch (error) {
      sample.pageError = String(error && (error.stack || error.message) || error);
    }
    try {
      sample.worker = await collectWorkerHeap(session, forceGc);
    } catch (error) {
      sample.workerError = String(error && (error.stack || error.message) || error);
    }
    sample.durationMillis = Date.now() - at;
    samples.push(sample);
    if (Date.now() >= deadline) break;
    await sleep(Math.min(sampleIntervalMillis, Math.max(0, deadline - Date.now())));
  }
  return samples;
}

function frameMetrics(frameTimes, elapsedMillis) {
  const summary = summarizeFrameTimes(frameTimes, elapsedMillis, 500);
  return {
    ...summary,
    p50FrameMillis: summary.p50FrameMs,
    p95FrameMillis: summary.p95FrameMs,
    p99FrameMillis: summary.p99FrameMs,
    longestFrameMillis: summary.longestFrameMs,
    longFrames500: summary.longFrames.atLeast500Ms,
  };
}

function durationHistogramMetrics(histogram) {
  const values = Array.isArray(histogram) ? histogram : [];
  const count = values.reduce((sum, current) => sum + (Number(current) || 0), 0);
  const percentileTarget = Math.max(1, Math.ceil(count * 0.99));
  let cumulative = 0;
  let percentileBucket = 0;
  let longestBucket = 0;
  for (let index = 0; index < values.length; index++) {
    const bucketCount = Number(values[index]) || 0;
    if (bucketCount > 0) longestBucket = index;
    if (cumulative < percentileTarget && cumulative + bucketCount >= percentileTarget) {
      percentileBucket = index;
    }
    cumulative += bucketCount;
  }
  return {
    sampleCount: count,
    p99Millis: percentileBucket / 4,
    longestMillis: longestBucket / 4,
    over500Millis: values.slice(2000).reduce(
      (sum, current) => sum + (Number(current) || 0),
      0,
    ),
  };
}

function slope(samples, selector) {
  const points = samples
    .filter((sample) => !sample.error)
    .map((sample) => ({x: sample.at / 1000, y: Number(selector(sample))}))
    .filter((point) => Number.isFinite(point.y));
  if (points.length < 2) return 0;
  const origin = points[0].x;
  const meanX = points.reduce((sum, point) => sum + point.x - origin, 0) / points.length;
  const meanY = points.reduce((sum, point) => sum + point.y, 0) / points.length;
  let covariance = 0;
  let variance = 0;
  for (const point of points) {
    const x = point.x - origin;
    covariance += (x - meanX) * (point.y - meanY);
    variance += (x - meanX) * (x - meanX);
  }
  return variance > 0 ? covariance / variance : 0;
}

function heapSummary(samples, key) {
  const points = samples
    .filter((sample) => sample[key] && Number.isFinite(sample[key].usedSize))
    .map((sample) => ({at: sample.at, usedSize: sample[key].usedSize}));
  if (points.length < 2) {
    return {sampleCount: points.length, passed: false, reason: "insufficient GC samples"};
  }
  const synthetic = points.map((point) => ({at: point.at, value: point.usedSize}));
  const bytesPerSecond = slope(synthetic, (point) => point.value);
  const tolerance = 1024 * 1024;
  const sustainedGrowth = points.slice(1).every(
    (point, index) => point.usedSize > points[index].usedSize + tolerance,
  );
  const first = points[0].usedSize;
  const last = points[points.length - 1].usedSize;
  const growthLimit = first * 1.15 + 32 * 1024 * 1024;
  const slopeMiBPerMinute = bytesPerSecond * 60 / (1024 * 1024);
  return {
    sampleCount: points.length,
    firstUsedMiB: first / (1024 * 1024),
    lastUsedMiB: last / (1024 * 1024),
    peakUsedMiB: Math.max(...points.map((point) => point.usedSize)) / (1024 * 1024),
    slopeMiBPerMinute,
    sustainedGrowth,
    passed: !sustainedGrowth && slopeMiBPerMinute <= 1 && last <= growthLimit,
  };
}

function max(samples, selector) {
  return samples.reduce((maximum, sample) => Math.max(maximum, Number(selector(sample)) || 0), 0);
}

function analyze(samples, stabilitySamples, telemetry, heapSamples, events, strict, context) {
  const profile = context.profile || benchmarkProfile;
  const gates = profile.gates || {};
  const contract = context.performanceContract || performanceContract;
  const environmentRules = contract.environment || {};
  const measurementRules = contract.measurement || {};
  const visualOutput = summarizeVisualOutput(
    context.visualOutputSamples || [],
    {
      ...(contract.visualOutput || visualOutputContract),
      requireSceneChange: benchmarkProfile.workload === "traverse",
    },
  );
  const startupRules = contract.startup || startupContract;
  const startupTimings = context.worldEntryTimings || null;
  const startupLimitMillis = Number(startupRules.newWorldInteractiveMsMax || 15_000);
  let startupStatus = "pass";
  const startupReasons = [];
  if (!startupTimings || !Number.isFinite(Number(startupTimings.worldInteractiveMillis))) {
    startupStatus = "inconclusive";
    startupReasons.push("new-world interactive timing was not captured");
  } else if (startupTimings.createdNewWorld !== true) {
    startupStatus = "inconclusive";
    startupReasons.push("the run did not create a fresh single-player world");
  } else if (startupTimings.readiness?.verdict !== "pass") {
    startupStatus = startupTimings.readiness?.verdict === "fail" ? "fail" : "inconclusive";
    startupReasons.push("strict terrain, targeting, and frame readiness did not pass");
  } else if (Number(startupTimings.worldInteractiveMillis) > startupLimitMillis) {
    startupStatus = "fail";
    startupReasons.push(
      `new world became interactive in ${Number(startupTimings.worldInteractiveMillis)} ms; `
        + `limit is ${startupLimitMillis} ms`,
    );
  }
  const startup = {
    verdict: startupStatus,
    limitMillis: startupLimitMillis,
    timings: startupTimings,
    reasons: startupReasons,
  };
  const validSamples = mergeMonotonicSamples(samples.filter((sample) => !sample.error));
  const validStabilitySamples = mergeMonotonicSamples(
    stabilitySamples.filter((sample) => !sample.error),
  );
  const allSamples = mergeMonotonicSamples(validSamples, validStabilitySamples);
  const performanceFrame = telemetry.frame || {};
  const frameTimes = validSamples.flatMap((sample) => sample.frame?.frameTimes || [])
    .concat(performanceFrame.undrainedFrameTimes || []);
  const lostFrameTimes = validSamples.reduce(
    (sum, sample) => sum + Number(sample.frame?.lostFrameTimes || 0),
    0,
  );
  const elapsedMillis = Math.max(
    0,
    Number(performanceFrame.endedAt || 0) - Number(performanceFrame.startedAt || 0),
  );
  const frames = {
    ...frameMetrics(frameTimes, elapsedMillis),
    rawFrameCount: Number(performanceFrame.frameCount || 0),
    rawSampleCount: Number(performanceFrame.sampleCount || 0),
  };
  const performanceProbe = telemetry.performanceProbe || telemetry.probe || {};
  const stabilityProbe = telemetry.stabilityProbe || telemetry.probe || {};
  const stabilityFrame = telemetry.stabilityFrame || performanceFrame;
  const performanceWorker = telemetry.performanceWorker || telemetry.worker || {};
  const stabilityWorker = telemetry.worker || {};

  const options = parseOptionsText(telemetry.environment?.optionsText);
  const framePacingEvidence = evaluateUncappedFramePacing({
    samples: validSamples
      .map((sample) => ({
        ...(sample.runtimeInvariants?.framePacing || {}),
        ...Object.fromEntries(Object.entries(sample.frame || {})
          .filter(([, value]) => value != null)),
      }))
      .filter((sample) => sample && typeof sample === "object"),
    final: performanceFrame,
    requirements: environmentRules.uncappedEvidence || {},
  });
  const rafIntervals = Array.from(performanceProbe.rafIntervals || [], Number)
    .filter((current) => Number.isFinite(current) && current > 0);
  const raf = summarizeScalarSamples(rafIntervals);
  const estimatedRefreshHz = raf.p50 > 0
    ? Math.round((1000 / raf.p50) * 1000) / 1000
    : null;
  let query = {};
  try {
    query = Object.fromEntries(new URL(context.targetUrl).searchParams.entries());
  } catch {
    // The invalid URL is reported as an environment issue below.
  }
  const visibilityStates = new Set(allSamples.map((sample) => sample.visibilityState));
  const focusStates = new Set(allSamples.map((sample) => sample.hasFocus));
  const dprValues = new Set(allSamples.map((sample) => sample.devicePixelRatio));
  const viewportValues = new Set(allSamples.map((sample) => JSON.stringify(sample.viewport)));
  const visualViewportValues = new Set(
    allSamples.map((sample) => JSON.stringify(sample.visualViewport)),
  );
  const canvasValues = new Set(allSamples.map((sample) => JSON.stringify(sample.canvas)));
  const distances = new Set(validSamples.flatMap((sample) => sample.distances || []));
  const workerDistanceEvidence = context.workerDistanceEvidence || {};
  const naturalWorkerEvidence = workerDistanceEvidence.naturalWorkerEvidence || {};
  const harnessOverrideEvidence = workerDistanceEvidence.harnessOverrideEvidence || {};
  const naturalWorkerMessages = Array.isArray(naturalWorkerEvidence.messages)
    ? naturalWorkerEvidence.messages : [];
  const workerStartMessages = naturalWorkerMessages.filter(
    (item) => item && item.type === "start",
  );
  const workerStartContractMatches = workerStartMessages.filter((item) =>
    Number(item.renderDistance) === expectedRenderDistance
      && Number(item.simulationDistance) === expectedSimulationDistance
      && String(item.profileId || "") === activeStorageProfileId
      && Number(item.worldVersion) === activeStorageWorldVersion
      && Number(item.storageSchema) === activeStorageConfig.schema
      && String(item.storageDatabaseName || "") === activeStorageConfig.databaseName
      && String(item.storagePrefix || "") === activeStorageConfig.prefix
      && String(item.storageOpfsDirectory || "") === activeStorageConfig.opfsDirectory,
  );
  const naturalWorkerDistances = new Set(naturalWorkerMessages
    .filter((item) => item && (item.type === "start" || item.type === "distances"))
    .map((item) => {
      const render = Number(item.renderDistance);
      const simulation = Number(item.simulationDistance);
      return Number.isFinite(render) && Number.isFinite(simulation)
        ? `${render}:${simulation}` : null;
    })
    .filter(Boolean));
  const optionsRenderDistance = Number(options.renderDistance);
  const optionsSimulationDistance = Number(options.simulationDistance);
  const optionsPreferenceDistance = Number.isFinite(optionsRenderDistance)
      && Number.isFinite(optionsSimulationDistance)
    ? `${optionsRenderDistance}:${optionsSimulationDistance}` : null;
  const naturalServerDistance = workerStartMessages.length > 0
    ? `${Number(workerStartMessages.at(-1).renderDistance)}:${Number(
      workerStartMessages.at(-1).simulationDistance,
    )}` : null;
  const effectiveDistance = Number.isFinite(optionsRenderDistance)
      && Number.isFinite(optionsSimulationDistance) && naturalServerDistance
    ? `${Math.min(optionsRenderDistance, Number(workerStartMessages.at(-1).renderDistance))}:${Math.min(
      optionsSimulationDistance, Number(workerStartMessages.at(-1).simulationDistance),
    )}` : null;
  const environmentIssues = [];
  if (context.headless) environmentIssues.push("headless Chrome is non-gating");
  if (!/(?:Google )?Chrome\//i.test(String(context.browserVersion?.Browser || ""))) {
    environmentIssues.push("Chrome version is unavailable or not Google Chrome");
  }
  if (!telemetry.environment?.platform) environmentIssues.push("OS/platform is unavailable");
  const foregroundRequired = environmentRules.foregroundRequired !== false;
  if (foregroundRequired) {
    if (visibilityStates.size !== 1 || !visibilityStates.has("visible")
        || telemetry.environment?.visibilityState !== "visible") {
      environmentIssues.push("document.visibilityState was not continuously visible");
    }
    if (focusStates.size !== 1 || !focusStates.has(true)
        || telemetry.environment?.hasFocus !== true) {
      environmentIssues.push("the game did not retain foreground focus");
    }
    if (Number(performanceFrame.hiddenFrameCount || 0) !== 0) {
      environmentIssues.push("BrowserGlfw hiddenFrameCount was non-zero");
    }
  }
  const minimumDisplayHz = Number(environmentRules.minimumDisplayHz || 120);
  if (foregroundRequired && (!(estimatedRefreshHz >= minimumDisplayHz) || !(raf.p95 < 500))) {
    environmentIssues.push(
      `foreground RAF cadence did not verify a non-throttled >=${minimumDisplayHz} Hz display`,
    );
  }
  if (dprValues.size !== 1 || viewportValues.size !== 1
      || visualViewportValues.size !== 1 || canvasValues.size !== 1) {
    environmentIssues.push(
      "DPR, layout viewport, visual viewport, or canvas resolution changed during measurement",
    );
  }
  const expectedViewport = environmentRules.viewport || {};
  const expectedWidth = Number(expectedViewport.width || 1280);
  const expectedHeight = Number(expectedViewport.height || 720);
  const expectedDpr = Number(expectedViewport.deviceScaleFactor || 1);
  if (Number(telemetry.environment?.devicePixelRatio) !== expectedDpr
      || Number(telemetry.environment?.viewport?.width) !== expectedWidth
      || Number(telemetry.environment?.viewport?.height) !== expectedHeight) {
    environmentIssues.push("DPR or viewport did not match the selected contract");
  }
  const visualViewport = telemetry.environment?.visualViewport;
  if (!visualViewport
      || Number(visualViewport.width) !== expectedWidth
      || Number(visualViewport.height) !== expectedHeight
      || Number(visualViewport.scale) !== 1) {
    environmentIssues.push("visualViewport dimensions or scale did not match the contract");
  }
  const canvas = telemetry.environment?.canvas;
  if (!canvas
      || Number(canvas.width) !== Math.round(expectedWidth * expectedDpr)
      || Number(canvas.height) !== Math.round(expectedHeight * expectedDpr)
      || Number(canvas.clientWidth) !== expectedWidth
      || Number(canvas.clientHeight) !== expectedHeight) {
    environmentIssues.push("canvas CSS size or backing-store resolution did not match the contract");
  }
  if (distances.size !== 1 || !distances.has(expectedDistanceLabel)) {
    environmentIssues.push(
      `active Worker render/simulation distance was not exactly ${expectedDistanceLabel}`,
    );
  }
  if (workerStartContractMatches.length === 0) {
    environmentIssues.push(
      `no natural Worker start message proved ${expectedDistanceLabel} with the active profile storage namespace`,
    );
  }
  if (optionsPreferenceDistance !== expectedDistanceLabel) {
    environmentIssues.push(
      `options.txt render/simulation preference was not exactly ${expectedDistanceLabel}`,
    );
  }
  if (pinWorkerDistance) {
    environmentIssues.push(
      "--pin-worker-distance is diagnostic-only; harness overrides can never provide release evidence",
    );
  }
  if (strict && profile.releaseEvidence === true && !releaseDistanceProfileCompatible) {
    environmentIssues.push(
      `strict 6/4 release evidence is only supported for active profile 26.2; active profile is ${activeStorageProfileId}`,
    );
  }
  const expectedMaxFps = Number(environmentRules.maxFps || 260);
  const unlimitedSentinel = Number(environmentRules.unlimitedSentinel || expectedMaxFps);
  const expectedGraphicsPreset = String(environmentRules.graphicsPreset || "fast").toLowerCase();
  if (Number(options.maxFps) !== expectedMaxFps
      || String(options.graphicsPreset).toLowerCase() !== expectedGraphicsPreset) {
    environmentIssues.push("Video Settings were not Unlimited with the Fast preset");
  }
  if (options.enableVsync !== false) environmentIssues.push("VSync was not disabled");
  const runtimeGameFps = Number(telemetry.environment?.fps?.gameFps);
  const optionsSeeded = context.profileGateResult?.seeded === true;
  if (!optionsSeeded) {
    environmentIssues.push("benchmark options were not proven to be seeded before profile submit");
  }
  const configuredUncapped = optionsSeeded
    && Number(options.maxFps) === unlimitedSentinel
    && options.enableVsync === false
    && Number.isFinite(runtimeGameFps)
    && runtimeGameFps > 0
    && Number(performanceFrame.frameCount || 0) > 0;
  const uncapped = configuredUncapped && framePacingEvidence.verdict === "pass";
  if (!configuredUncapped) {
    environmentIssues.push(
      "runtime frame telemetry did not prove the loaded Unlimited/VSync-off configuration",
    );
  }
  if (framePacingEvidence.verdict !== "pass") {
    environmentIssues.push(...framePacingEvidence.reasons);
  }
  if (query.targetFps !== String(expectedMaxFps)
      || query.autoDpr !== (environmentRules.autoDpr === false ? "0" : "1")
      || query.glStats !== "1"
      || query.perfHud !== "0") {
    environmentIssues.push(
      `URL did not request targetFps=${expectedMaxFps}, glStats=1, perfHud=0, and the contracted autoDpr mode`,
    );
  }
  if (strict && profile.requireNewChunks && !context.freshChromeProfile) {
    environmentIssues.push("new-terrain traversal requires a fresh Chrome profile and world");
  }
  if (strict && warmupMillis < Number(profile.warmupMs || measurementRules.warmupMs || 0)) {
    environmentIssues.push("warmup was shorter than the selected profile contract");
  }
  if (strict && frameMeasurementMillis < Number(
    profile.fpsWindowMs || profile.durationMs || measurementRules.sampleMs || 0,
  )) {
    environmentIssues.push("FPS window was shorter than the selected profile contract");
  }
  if (strict && gates.memory
      && heapMillis < Number(profile.soakMs || measurementRules.soakMs || 0)) {
    environmentIssues.push("memory soak was shorter than the selected profile contract");
  }
  if (strict && cleanupMillis < Number(measurementRules.cleanupMs || 30_000)) {
    environmentIssues.push("post-exit cleanup observation was shorter than the contract");
  }
  if (strict && startupTimeoutMillis < 900_000) {
    environmentIssues.push("startup timeout was shorter than 15 minutes");
  }
  const environment = {
    valid: environmentIssues.length === 0,
    issues: environmentIssues,
    chromeVersion: context.browserVersion?.Browser || null,
    protocolVersion: context.browserVersion?.["Protocol-Version"] || null,
    operatingSystem: telemetry.environment?.platform || null,
    userAgent: telemetry.environment?.userAgent || null,
    headless: context.headless,
    visibilityStates: [...visibilityStates],
    focusStates: [...focusStates],
    devicePixelRatio: telemetry.environment?.devicePixelRatio || null,
    viewport: telemetry.environment?.viewport || null,
    visualViewport: telemetry.environment?.visualViewport || null,
    screen: telemetry.environment?.screen || null,
    canvas: telemetry.environment?.canvas || null,
    displayTelemetry: telemetry.environment?.display || null,
    estimatedRefreshHz,
    rafIntervalsMillis: raf,
    query,
    options,
    optionsSeed: context.profileGateResult || null,
    distanceContract: {
      optionsPreference: optionsPreferenceDistance,
      effectiveDistanceModel: "min(client-options-preference,worker-server-distance)",
      effectiveDistance,
      activeWorkerDistances: [...distances],
      naturalWorkerDistances: [...naturalWorkerDistances],
      naturalServerDistance,
      expectedWorkerServerDistance: expectedDistanceLabel,
      mode: pinWorkerDistance ? "harness-pin-diagnostic" : "natural-observation",
      releaseEligible: !pinWorkerDistance && releaseDistanceProfileCompatible,
      releaseTargetProfile: "26.2",
      activeProfileId: activeStorageProfileId,
      workerStartMessages: workerStartMessages.length,
      matchingWorkerStartMessages: workerStartContractMatches.length,
      naturalWorkerEvidence,
      harnessOverrideEvidence,
      expectedStorage: {
        profileId: activeStorageProfileId,
        worldVersion: activeStorageWorldVersion,
        storageSchema: activeStorageConfig.schema,
        storageDatabaseName: activeStorageConfig.databaseName,
        storagePrefix: activeStorageConfig.prefix,
        storageOpfsDirectory: activeStorageConfig.opfsDirectory,
      },
    },
    frameLimiter: {
      uncapped,
      configured: configuredUncapped,
      source: "runtime-loaded-options-and-uncapped-frame-pacing-telemetry",
      configuredMaxFps: Number.isFinite(Number(options.maxFps)) ? Number(options.maxFps) : null,
      unlimitedSentinel,
      runtimeGameFps: Number.isFinite(runtimeGameFps) ? runtimeGameFps : null,
      vsync: options.enableVsync,
      evidence: framePacingEvidence,
    },
    foregroundRequired,
    frameSampleCapacity: Number(performanceFrame.sampleCapacity || 0),
    activeDistances: [...distances],
    expectedDistances: expectedDistanceLabel,
    profileName,
    durations: {
      warmupMillis,
      fpsWindowMillis: frameMeasurementMillis,
      soakMillis: heapMillis,
      cleanupMillis,
      startupTimeoutMillis,
      startupContract,
    },
    evidenceRole: profile.evidenceRole || null,
    releaseEvidence: profile.releaseEvidence === true,
  };
  if (Number(performanceFrame.sampleCapacity || 0) !== frameSampleCapacity) {
    environment.valid = false;
    environment.issues.push(
      `frame ring capacity was ${Number(performanceFrame.sampleCapacity || 0)}, expected ${frameSampleCapacity}`,
    );
  }

  const frameFreezeCount = Math.max(
    Number(performanceFrame.freezeCount || 0),
    Number(frames.longFrames.atLeast500Ms || 0),
  );
  const pageHeartbeatGaps = Array.from(performanceProbe.heartbeatGaps || [], Number)
    .filter((current) => Number.isFinite(current) && current >= 500);
  const performanceSampleFailures = samples.filter(
    (sample) => sample.error || Number(sample.evaluationLatencyMillis) >= 500,
  );
  const workerFreezeCount = Number(performanceWorker.maxRttMillis || 0) >= 500
      || Number(performanceWorker.longestHeartbeatDelayMillis || 0) >= 500
    ? 1
    : 0;
  const freezeReasons = [];
  if (frameFreezeCount) freezeReasons.push(`${frameFreezeCount} frame(s) reached 500 ms`);
  if (pageHeartbeatGaps.length) {
    freezeReasons.push(`${pageHeartbeatGaps.length} page heartbeat gap(s) reached 500 ms`);
  }
  if (performanceSampleFailures.length) {
    freezeReasons.push(`${performanceSampleFailures.length} Chrome sample(s) failed or reached 500 ms`);
  }
  if (workerFreezeCount) freezeReasons.push("Worker heartbeat or MessagePort RTT reached 500 ms");
  const freezes = {
    total: frameFreezeCount + pageHeartbeatGaps.length
      + performanceSampleFailures.length + workerFreezeCount,
    reasons: freezeReasons,
    frameFreezeCount,
    pageHeartbeatFreezeCount: pageHeartbeatGaps.length,
    pageHeartbeatGapsMillis: pageHeartbeatGaps,
    samplingFreezeCount: performanceSampleFailures.length,
    samplingLatencyMillis: summarizeScalarSamples(
      samples.map((sample) => sample.evaluationLatencyMillis),
    ),
    workerFreezeCount,
  };

  const queueBaseAt = allSamples[0]?.at || 0;
  const queueTimeline = allSamples.map((sample) => ({
    atMillis: Number(sample.at || 0) - queueBaseAt,
    chunk: sample.pipeline || {},
    network: sample.network || {},
    worker: {
      chunk: sample.worker?.chunkPriority || {},
      network: sample.worker?.network || {},
      worldgen: sample.worker?.worldgen || {},
    },
    heartbeat: {
      pending: Number(sample.worker?.pending || 0),
      maxRttMillis: Number(sample.worker?.maxRttMillis || 0),
    },
  }));
  const requiredHighWaterMillis = Number(measurementRules.queueHighWaterDurationMs || 10_000);
  const queueMeasurementEndMillis = Math.max(
    Number(queueTimeline.at(-1)?.atMillis || 0),
    Number(context.measurementEndedAt || 0) - queueBaseAt,
  );
  const queueSummary = summarizeQueueTimeline(
    queueTimeline,
    contract.queueHighWater || {},
    requiredHighWaterMillis,
    queueMeasurementEndMillis,
  );
  const queueFirst = allSamples[0] || {};
  const queueLast = allSamples.at(-1) || {};
  const compileSlope = slope(allSamples, (sample) => sample.pipeline?.compileBacklog);
  const uploadSlope = slope(allSamples, (sample) => sample.pipeline?.uploadBacklog);
  const compileGrowing = compileSlope > 0.05
    && Number(queueLast.pipeline?.compileBacklog || 0)
      > Number(queueFirst.pipeline?.compileBacklog || 0) + 8;
  const uploadGrowing = uploadSlope > 0.05
    && Number(queueLast.pipeline?.uploadBacklog || 0)
      > Number(queueFirst.pipeline?.uploadBacklog || 0) + 16;
  const reportedHighWaterMillis = Math.max(
    Number(telemetry.pipeline?.longestHighWaterMillis || 0),
    Number(telemetry.pipeline?.currentHighWaterMillis || 0),
    Number(telemetry.network?.longestHighWatermarkMillis || 0),
    Number(telemetry.network?.activeHighWatermarkMillis || 0),
    Number(telemetry.worker?.network?.longestHighWatermarkMillis || 0),
    Number(telemetry.worker?.network?.activeHighWatermarkMillis || 0),
  );
  const queueReasons = [];
  if (queueSummary.failedQueues.length) {
    queueReasons.push(`10-second high-water: ${queueSummary.failedQueues.join(", ")}`);
  }
  if (reportedHighWaterMillis >= requiredHighWaterMillis) {
    queueReasons.push(
      `runtime reported a continuous queue high-water interval >=${requiredHighWaterMillis} ms`,
    );
  }
  if (compileGrowing) queueReasons.push("compile backlog ended with an unbounded positive trend");
  if (uploadGrowing) queueReasons.push("upload backlog ended with an unbounded positive trend");
  let queueVerdict = queueReasons.length ? "fail" : "pass";
  if (!queueReasons.length && queueSummary.unavailableQueues.length) queueVerdict = "inconclusive";
  const queues = {
    ...queueSummary,
    verdict: queueVerdict,
    reasons: queueReasons,
    requiredContinuousHighWaterMs: requiredHighWaterMillis,
    sourceReportedLongestHighWaterMs: reportedHighWaterMillis,
    compileBacklogSlopePerSecond: compileSlope,
    uploadBacklogSlopePerSecond: uploadSlope,
    rawTimeline: queueTimeline,
  };
  const decodedThresholds = Object.fromEntries(
    Object.entries(contract.queueHighWater || {})
      .filter(([path]) => path.endsWith(".decodedPacketQueue")),
  );
  const decodedSummary = summarizeQueueTimeline(
    queueTimeline,
    decodedThresholds,
    requiredHighWaterMillis,
    queueMeasurementEndMillis,
  );
  const decodedReasons = [];
  if (decodedSummary.failedQueues.length) {
    decodedReasons.push(
      `decoded packet queue remained high: ${decodedSummary.failedQueues.join(", ")}`,
    );
  }
  if (decodedSummary.unavailableQueues.length) {
    decodedReasons.push(
      `decoded packet queue telemetry unavailable: ${decodedSummary.unavailableQueues.join(", ")}`,
    );
  }
  const decodedPacketQueue = {
    ...decodedSummary,
    verdict: decodedSummary.failedQueues.length > 0
      ? "fail"
      : (decodedSummary.unavailableQueues.length > 0 ? "inconclusive" : "pass"),
    reasons: decodedReasons,
  };

  const observedChunks = new Set(allSamples
    .filter((sample) => sample.chunk)
    .map((sample) => sample.chunk.x + ":" + sample.chunk.z));
  const loadedChunkCounts = allSamples
    .map((sample) => Number(sample.loadedChunkCount))
    .filter((value) => Number.isFinite(value));
  const loadedChunkDelta = loadedChunkCounts.length >= 2
    ? Math.max(0, loadedChunkCounts.at(-1) - loadedChunkCounts[0])
    : null;
  const loadedChunkDeltaSource = loadedChunkCounts.length >= 2
    ? "runtime-loaded-chunk-count"
    : "unavailable";

  const heapScopeBytes = (scope) => scope
    ? Number(scope.usedSize || 0)
      + Number(scope.embedderHeapUsedSize || 0)
      + Number(scope.backingStorageSize || 0)
    : null;
  const heapTotal = (sample) => sample?.page && sample?.worker
    ? heapScopeBytes(sample.page) + heapScopeBytes(sample.worker)
    : null;
  const regularHeap = heapSamples.map((sample) => ({
    atMillis: sample.atMillis,
    totalUsedBytes: heapTotal(sample),
  })).filter((sample) => Number.isFinite(sample.totalUsedBytes));
  const postGcHeap = heapSamples.filter((sample) => sample.postGc).map((sample) => ({
    atMillis: sample.atMillis,
    totalUsedBytes: heapTotal(sample),
    supported: sample.page?.gcSupported !== false && sample.worker?.gcSupported !== false,
  })).filter((sample) => Number.isFinite(sample.totalUsedBytes));
  const memoryRequiredDurationMillis = Number(
    profile.soakMs || measurementRules.soakMs || 1_800_000,
  );
  const heapAttemptedSampleTimes = heapSamples.map((sample) => sample.atMillis);
  const memoryTrend = summarizeMemoryTrend({
    regularSamples: regularHeap,
    postGcSamples: postGcHeap,
    durationMs: heapMillis,
    requiredDurationMs: memoryRequiredDurationMillis,
    loadedChunkDelta,
    postGcFinalWindows,
    retainedGrowthPercentMax: Number(heapMemoryContract.retainedGrowthPercentMax || 15),
    retainedGrowthBytesMax: Number(heapMemoryContract.retainedGrowthBytesMax || 268435456),
    peakUsedBytesMax: Number(heapMemoryContract.peakUsedBytesMax || 8589934592),
    plateauSlopeMiBPerMinuteMax: Number(
      heapMemoryContract.plateauSlopeMiBPerMinuteMax || 1,
    ),
    attemptedSampleTimes: heapAttemptedSampleTimes,
    sampleIntervalMs: Number(measurementContract.heapIntervalMs || 5000),
    minimumAvailableRatio: Number(heapMemoryContract.minimumAvailableRatio || 0.9),
    minimumDurationCoverageRatio: Number(
      heapMemoryContract.minimumDurationCoverageRatio || 0.98,
    ),
    maximumSampleGapRatio: Number(heapMemoryContract.maximumSampleGapRatio || 1.5),
  });
  const nativeSample = (sample, requireWorker = true) => {
    if (!sample?.page?.browserMemory
        || (requireWorker && !sample?.worker?.browserMemory)) return null;
    const combined = combineBrowserMemory(
      sample?.page?.browserMemory,
      sample?.worker?.browserMemory,
    );
    return combined ? {atMillis: sample.atMillis, ...combined} : null;
  };
  const nativeRegular = heapSamples.map((sample) => nativeSample(sample, true)).filter(Boolean);
  const nativePostGc = heapSamples.filter((sample) => sample.postGc)
    .map((sample) => nativeSample(sample, true)).filter(Boolean);
  const nativeCleanup = (context.cleanupHeapSamples || [])
    .map((sample) => nativeSample(sample, true)).filter(Boolean);
  const missingCombinedNativeSamples = heapSamples.filter(
    (sample) => !sample?.page?.browserMemory || !sample?.worker?.browserMemory,
  );
  const cleanupHeapSamples = context.cleanupHeapSamples || [];
  const missingCleanupNativeSamples = cleanupHeapSamples.filter(
    (sample) => !sample?.page?.browserMemory || !sample?.worker?.browserMemory,
  );
  const cleanupSourceComplete = cleanupHeapSamples.length > 0
    && missingCleanupNativeSamples.length === 0;
  const nativeMemory = summarizeNativeMemoryTrend({
    regularSamples: nativeRegular,
    postGcSamples: nativePostGc,
    cleanupSamples: nativeCleanup,
    baseline: context.browserMemoryBaseline,
    durationMs: heapMillis,
    requiredDurationMs: memoryRequiredDurationMillis,
    postGcFinalWindows,
    attemptedSampleTimes: heapAttemptedSampleTimes,
    sampleIntervalMs: Number(measurementContract.heapIntervalMs || 5000),
    minimumAvailableRatio: Number(browserMemoryContract.minimumAvailableRatio || 0.9),
    minimumDurationCoverageRatio: Number(
      browserMemoryContract.minimumDurationCoverageRatio || 0.98,
    ),
    maximumSampleGapRatio: Number(browserMemoryContract.maximumSampleGapRatio || 1.5),
    cleanupSourceComplete,
  });
  const browserMemorySafetySamples = [
    ...heapSamples.map((sample) => ({...sample, sourcePhase: sample.phase || "soak"})),
    ...cleanupHeapSamples.map((sample) => ({...sample, sourcePhase: "cleanup"})),
  ].flatMap((sample) => [
    {
      source: `${sample.sourcePhase}:page`,
      atMillis: sample.atMillis,
      memory: sample?.page?.browserMemory,
    },
    {
      source: `${sample.sourcePhase}:worker`,
      atMillis: sample.atMillis,
      memory: sample?.worker?.browserMemory,
    },
    {
      source: `${sample.sourcePhase}:page+worker`,
      atMillis: sample.atMillis,
      aggregate: true,
      memory: combineBrowserMemorySafety(
        sample?.page?.browserMemory,
        sample?.worker?.browserMemory,
      ),
    },
  ]);
  const browserMemorySafety = evaluateBrowserMemorySafety({
    snapshots: browserMemorySafetySamples,
    requiredFields: [
      ...(browserMemoryContract.liveFields || []),
      ...(browserMemoryContract.safetyRequiredFields || []),
    ],
    failureFields: browserMemoryContract.failureFields || [],
    limits: browserMemoryContract.limits || {},
    aggregateLimits: browserMemoryContract.aggregateLimits || {},
  });
  nativeMemory.safety = browserMemorySafety;
  if (heapMillis >= memoryRequiredDurationMillis
      && browserMemorySafety.verdict === "fail") {
    nativeMemory.verdict = "fail";
    nativeMemory.reasons.push(...browserMemorySafety.reasons);
  } else if (heapMillis >= memoryRequiredDurationMillis
      && browserMemorySafety.verdict !== "pass"
      && nativeMemory.verdict !== "fail") {
    nativeMemory.verdict = "inconclusive";
    nativeMemory.reasons.push(...browserMemorySafety.reasons);
  }
  if (nativeMemory.verdict === "pass" && !context.leftWorld) {
    nativeMemory.verdict = "inconclusive";
    nativeMemory.reasons.push("the benchmark did not verify a successful world exit");
  }
  if (nativeMemory.verdict === "pass" && missingCombinedNativeSamples.length > 0) {
    nativeMemory.verdict = "inconclusive";
    nativeMemory.reasons.push(
      `${missingCombinedNativeSamples.length} BrowserMemory sample(s) did not include both page and integrated-server Worker`,
    );
  }
  nativeMemory.missingCombinedSourceSamples = missingCombinedNativeSamples.length;
  nativeMemory.missingCleanupSourceSamples = missingCleanupNativeSamples.length;
  nativeMemory.cleanupSourceComplete = cleanupSourceComplete;
  nativeMemory.requiredRuntimeSourceCount = 2;
  const processRssRules = contract.processRss || {};
  const processRss = summarizeChromeProcessRssTrend({
    samples: context.processRssSamples || [],
    cleanupSamples: context.cleanupProcessRssSamples || [],
    durationMs: Number(context.processRssDurationMillis || 0),
    requiredDurationMs: Number(
      profile.soakMs || measurementRules.soakMs || processRssRules.requiredDurationMs || 1_800_000,
    ),
    sampleIntervalMs: Number(processRssRules.sampleIntervalMs || 15_000),
    minimumAvailableRatio: Number(processRssRules.minimumAvailableRatio || 0.9),
    minimumDurationCoverageRatio: Number(
      processRssRules.minimumDurationCoverageRatio || 0.98,
    ),
    maximumSampleGapRatio: Number(processRssRules.maximumSampleGapRatio || 1.5),
    trendWindowCount: Number(processRssRules.trendWindowCount || 4),
    processTypes: processRssRules.processTypes,
    requiredProcessTypes: processRssRules.requiredProcessTypes,
    totalThresholds: processRssRules.total,
    typeThresholds: processRssRules.byType,
  });
  const memoryReasons = [
    ...memoryTrend.reasons,
    ...nativeMemory.reasons,
    ...processRss.reasons,
  ];
  let memoryVerdict = "inconclusive";
  if ([memoryTrend, nativeMemory, processRss].some((metric) => metric.verdict === "fail")) {
    memoryVerdict = "fail";
  } else if ([memoryTrend, nativeMemory, processRss]
    .every((metric) => metric.verdict === "pass")) {
    memoryVerdict = "pass";
  } else if ([memoryTrend, nativeMemory, processRss]
    .every((metric) => metric.verdict === "not-evaluated")) {
    memoryVerdict = "not-evaluated";
  }
  const memory = {
    verdict: memoryVerdict,
    reasons: memoryReasons,
    v8Heap: memoryTrend,
    browserMemory: nativeMemory,
    processRss,
    browserMemoryBaseline: context.browserMemoryBaseline,
    browserMemoryBaselineSources: context.browserMemoryBaselineSources || null,
    loadedChunkDelta,
    loadedChunkDeltaSource,
    leftWorld: context.leftWorld,
    rawSoakSamples: heapSamples,
    rawCleanupSamples: context.cleanupHeapSamples || [],
    rawProcessRssSamples: context.processRssSamples || [],
    rawCleanupProcessRssSamples: context.cleanupProcessRssSamples || [],
  };

  const resourceFirst = allSamples.find((sample) => sample.resources)?.resources || null;
  const resourceLast = [...allSamples].reverse().find((sample) => sample.resources)?.resources || null;
  const resourceDelta = (path) => {
    const read = (root) => path.reduce((current, key) => current?.[key], root);
    const firstValue = Number(read(resourceFirst));
    const lastValue = Number(read(resourceLast));
    return Number.isFinite(firstValue) && Number.isFinite(lastValue)
      ? lastValue - firstValue
      : null;
  };
  const resources = {
    first: resourceFirst,
    last: resourceLast,
    deltas: {
      performanceEntries: resourceDelta(["entries"]),
      transferBytes: resourceDelta(["transferBytes"]),
      decodedBytes: resourceDelta(["decodedBytes"]),
      webglBuffers: resourceDelta(["webgl", "buffers"]),
      webglTextures: resourceDelta(["webgl", "textures"]),
      webglFramebuffers: resourceDelta(["webgl", "framebuffers"]),
      audioBuffers: resourceDelta(["audio", "buffers"]),
      audioSources: resourceDelta(["audio", "sources"]),
    },
    conclusion: nativeRegular.length > 0
      ? "WebGL/audio counters and BrowserMemory were captured with cleanup evidence"
      : "inconclusive: BrowserMemory telemetry is unavailable",
  };

  const gameplayAuthority = evaluateGameplayAuthority({
    evidence: telemetry.gameplayAuthority,
    contract: resolvedGameplayAuthorityContract,
  });

  const minecraftEvents = (context.warmupSamples || [])
    .flatMap((sample) => sample.minecraftEvents || [])
    .concat(allSamples.flatMap((sample) => sample.minecraftEvents || []))
    .concat(telemetry.minecraftEvents || [])
    .map((event) => ({source: "minecraft", ...event}));
  const lifecycleSamples = allSamples
    .map((sample) => ({at: sample.at, ...sample.workerLifecycle}))
    .concat([{at: telemetry.stabilityCapturedAt, ...telemetry.workerLifecycle}])
    .filter((lifecycle) => Number.isFinite(Number(lifecycle.at)));
  const workerHeartbeatTelemetry = validateWorkerHeartbeatTelemetry(
    performanceWorker,
    telemetry.workerLifecycle,
    context,
    heartbeatContract,
  );
  const terminalWorkerSamples = lifecycleSamples.filter(
    (lifecycle) => Number(lifecycle.terminalCount || 0) > 0,
  );
  const disconnectedWorkerSamples = lifecycleSamples.filter(
    (lifecycle) => Number(lifecycle.activeCount || 0) === 0,
  );
  const fatalPattern = /renderer crash|worker crash|bootstrap-crash|worker:error|out of memory|allocation failed|\bOOM\b|messageport.{0,40}unavailable|local server.{0,40}unavailable|connection lost|disconnected/i;
  const phaseEvents = events.filter((event) => {
    const at = Number(event?.at);
    return Number.isFinite(at)
      && at >= Number(context.runEventStartedAt || 0)
      && at <= Number(context.runEventEndedAt || Number.POSITIVE_INFINITY);
  });
  const fatalEvents = phaseEvents.concat(minecraftEvents).filter((event) => {
    const detail = (() => {
      try {
        return JSON.stringify(event);
      } catch {
        return String(event.text || event.detail || event.event || "");
      }
    })();
    return event.source === "exception"
      || event.source === "renderer crash"
      || event.source === "renderer detached"
      || fatalPattern.test(detail);
  });
  const contextLosses = Array.from(stabilityProbe.contextLosses || []);
  const stateViolations = allSamples.filter((sample) =>
    !Number.isFinite(Number(sample.stateAt))
      || Number(sample.stateAt) <= 0
      || sample.level !== "net.minecraft.client.multiplayer.ClientLevel"
      || sample.screen !== null
      || sample.overlay !== null
      || sample.noRender !== false
      || sample.running !== true
      || sample.pause !== false);
  let maximumStateStallMillis = 0;
  let lastStateAt = null;
  let lastStateProgressAt = null;
  for (const sample of allSamples) {
    const stateAt = Number(sample.stateAt);
    const at = Number(sample.at);
    if (!Number.isFinite(stateAt) || !Number.isFinite(at)) continue;
    if (lastStateAt == null || stateAt > lastStateAt) {
      lastStateAt = stateAt;
      lastStateProgressAt = at;
    } else if (lastStateProgressAt != null) {
      maximumStateStallMillis = Math.max(maximumStateStallMillis, at - lastStateProgressAt);
    }
  }
  const maximumAllowedStateStallMillis = Number(
    measurementRules.maximumStateStallMs || 1000,
  );
  const stateStalled = maximumStateStallMillis > maximumAllowedStateStallMillis;
  const worldLost = stateViolations.length > 0 || stateStalled;
  const stabilitySamplingFailures = samples.concat(stabilitySamples).filter(
    (sample) => sample.error || Number(sample.evaluationLatencyMillis) >= 500,
  );
  const cleanupSamplingFailures = (context.cleanupHeapSamples || []).filter(
    (sample) => sample.pageError || Number(sample.durationMillis) >= 500,
  );
  const stabilityHeartbeatGaps = Array.from(stabilityProbe.heartbeatGaps || [], Number)
    .filter((current) => Number.isFinite(current) && current >= 500);
  const stabilityWorkerFrozen = workerHeartbeatTelemetry.verdict !== "pass"
    || Number(stabilityWorker.maxRttMillis)
      > Number(heartbeatContract.rttMaxMs || 250)
    || Number(stabilityWorker.p99RttMillis)
      > Number(heartbeatContract.rttP99MaxMs || 50)
    || Number(stabilityWorker.longestHeartbeatDelayMillis)
      > Number(heartbeatContract.delayMaxMs || 250);
  const stabilityWorkerFailed = Number(stabilityWorker.missed || 0) > 0
    || Number(stabilityWorker.errors || 0) > 0
    || Number(stabilityWorker.pending || 0) > Number(heartbeatContract.pendingMax || 3);
  const audioState = resourceLast?.audio?.contextState || null;
  const stabilityReasons = [];
  if (fatalEvents.length) stabilityReasons.push(`${fatalEvents.length} crash/exception/OOM signal(s)`);
  if (contextLosses.length) stabilityReasons.push(`${contextLosses.length} WebGL context loss(es)`);
  if (terminalWorkerSamples.length) {
    stabilityReasons.push("the integrated-server Worker entered a terminal state during the run");
  }
  if (disconnectedWorkerSamples.length) {
    stabilityReasons.push("the integrated-server Worker was absent or disconnected during the run");
  }
  if (worldLost) stabilityReasons.push("world connection or visible render state was lost");
  if (stabilitySamplingFailures.length) stabilityReasons.push("a stability sample failed or reached 500 ms");
  if (cleanupSamplingFailures.length) stabilityReasons.push("a cleanup sample failed or reached 500 ms");
  if (stabilityHeartbeatGaps.length) stabilityReasons.push("a page heartbeat gap reached 500 ms");
  if (Number(stabilityFrame.freezeCount || 0) > 0) {
    stabilityReasons.push("BrowserGlfw recorded a >=500 ms frame during performance/soak");
  }
  if (Number(stabilityFrame.hiddenFrameCount || 0) > 0) {
    stabilityReasons.push("BrowserGlfw recorded hidden frames during performance/soak");
  }
  if (stabilityWorkerFrozen) {
    stabilityReasons.push("Worker heartbeat or MessagePort RTT exceeded the contract");
  }
  stabilityReasons.push(...workerHeartbeatTelemetry.reasons);
  if (stabilityWorkerFailed) {
    stabilityReasons.push("Worker heartbeat reported missed, errored, or excessive pending probes");
  }
  if (audioState !== "running") {
    stabilityReasons.push(audioState == null
      ? "audio context state was unavailable"
      : `audio context ended in ${audioState} state`);
  }
  const stability = {
    verdict: stabilityReasons.length ? "fail" : "pass",
    reasons: stabilityReasons,
    fatalEvents,
    phaseEvents,
    minecraftEvents,
    terminalWorkerSamples,
    disconnectedWorkerSamples,
    contextLosses,
    worldLost,
    stateViolations,
    maximumStateStallMillis,
    maximumAllowedStateStallMillis,
    cleanupSamplingFailures,
    frameFreezeCount: Number(stabilityFrame.freezeCount || 0),
    hiddenFrameCount: Number(stabilityFrame.hiddenFrameCount || 0),
    audioContextState: audioState,
    workerHeartbeat: stabilityWorker,
  };

  const positions = validSamples.filter((sample) => sample.workloadActive === true && sample.player
    && Number.isFinite(sample.player.x) && Number.isFinite(sample.player.z));
  const startPosition = positions[0]?.player || null;
  const endPosition = positions.at(-1)?.player || null;
  const displacementBlocks = startPosition && endPosition
    ? Math.hypot(endPosition.x - startPosition.x, endPosition.z - startPosition.z)
    : 0;
  let maximumTraversalStallMillis = 0;
  let lastTravelChunk = null;
  let lastTravelChunkChangeAt = null;
  for (const sample of validSamples) {
    if (sample.workloadActive !== true || !sample.chunk) {
      lastTravelChunk = null;
      lastTravelChunkChangeAt = null;
      continue;
    }
    const key = `${sample.chunk.x}:${sample.chunk.z}`;
    const at = Number(sample.at);
    if (!Number.isFinite(at)) continue;
    if (lastTravelChunk == null || key !== lastTravelChunk) {
      lastTravelChunk = key;
      lastTravelChunkChangeAt = at;
    } else if (lastTravelChunkChangeAt != null) {
      maximumTraversalStallMillis = Math.max(
        maximumTraversalStallMillis,
        at - lastTravelChunkChangeAt,
      );
    }
  }
  const minimumTraversalChunks = Number(measurementRules.minimumTraversalChunks || 16);
  const minimumTraversalBlocks = Number(measurementRules.minimumTraversalBlocks || 256);
  const maximumAllowedTraversalStallMillis = Number(
    measurementRules.maximumTraversalStallMs || 10_000,
  );
  const travelPassed = context.freshChromeProfile
    && observedChunks.size >= minimumTraversalChunks
    && displacementBlocks >= minimumTraversalBlocks
    && maximumTraversalStallMillis <= maximumAllowedTraversalStallMillis;
  const travel = {
    uniqueChunkCoordinates: observedChunks.size,
    displacementBlocks,
    startPosition,
    endPosition,
    minimumTraversalChunks,
    minimumTraversalBlocks,
    maximumTraversalStallMillis,
    maximumAllowedTraversalStallMillis,
    freshChromeProfile: context.freshChromeProfile,
    crossedNewTerrainVerdict: travelPassed
      ? "pass"
      : (context.freshChromeProfile ? "fail" : "inconclusive"),
    note: context.freshChromeProfile
      ? "A fresh Chrome profile created a new local world before traversal."
      : "An attached profile cannot prove that crossed chunks were newly generated.",
  };
  const targetingAgeMillis = Number(telemetry.capturedAt || 0)
    - Number(telemetry.targeting?.lastAt || 0);
  const targeting = {
    ...telemetry.targeting,
    ageAtPerformanceSnapshotMillis: targetingAgeMillis,
    verdict: Number(telemetry.targeting?.updates || 0) >= Number(performanceFrame.frameCount || 0) * 0.5
        && targetingAgeMillis >= 0 && targetingAgeMillis < 500
      ? "pass"
      : "fail",
  };
  const invariantCapacity = Math.max(
    64,
    Math.min(16384, Number(measurementRules.runtimeInvariantSampleCapacity || 4096)),
  );
  const invariantSnapshots = allSamples.slice(-invariantCapacity)
    .map((sample) => sample.runtimeInvariants)
    .filter((snapshot) => snapshot && typeof snapshot === "object");
  for (const snapshot of [
    telemetry.runtimeInvariants,
    telemetry.stabilityRuntimeInvariants,
  ]) {
    if (snapshot && typeof snapshot === "object") invariantSnapshots.push(snapshot);
  }
  const runtimeInvariantTelemetry = summarizeRuntimeInvariantTelemetry({
    snapshots: invariantSnapshots,
    capacity: invariantCapacity,
    frameCount: Number(performanceFrame.frameCount || 0),
    capturedAt: telemetry.stabilityCapturedAt || telemetry.capturedAt,
  });
  runtimeInvariantTelemetry.diagnostics = {
    targetingUpdates: Number(telemetry.targeting?.updates || 0),
    targetingSkips: Number(telemetry.targeting?.skips || 0),
    targetingAgeMillis,
    renderedFrames: Number(performanceFrame.frameCount || 0),
  };
  const runtimeInvariants = evaluateRuntimeInvariants({
    contract: context.runtimeInvariantContract
      || contract.runtimeInvariants
      || runtimeInvariantContract,
    telemetry: runtimeInvariantTelemetry,
    worldgenTelemetryMode: context.worldgenTelemetryMode || activeWorldgenTelemetryMode,
  });

  const contractEvaluation = evaluatePerformanceGates({
    profile,
    environment,
    frames,
    freezes,
    queues,
    decodedPacketQueue,
    memory,
    gameplayAuthority,
    stability,
    runtimeInvariants,
  });
  const heartbeatPendingMax = Number(heartbeatContract.pendingMax || 3);
  const heartbeatRttP99Max = Number(heartbeatContract.rttP99MaxMs || 50);
  const heartbeatRttMax = Number(heartbeatContract.rttMaxMs || 250);
  const heartbeatDelayMax = Number(heartbeatContract.delayMaxMs || 250);
  const workerStatus = workerHeartbeatTelemetry.verdict === "pass"
      && Number(performanceWorker.missed) === 0
      && Number(performanceWorker.errors) === 0
      && Number(performanceWorker.pending) <= heartbeatPendingMax
      && Number(performanceWorker.p99RttMillis) <= heartbeatRttP99Max
      && Number(performanceWorker.maxRttMillis) <= heartbeatRttMax
      && Number(performanceWorker.longestHeartbeatDelayMillis) <= heartbeatDelayMax
    ? "pass" : "fail";
  const heartbeat = {
    verdict: workerStatus,
    reasons: workerHeartbeatTelemetry.reasons,
    telemetryValidation: workerHeartbeatTelemetry,
    sessionId: performanceWorker.sessionId || null,
    measurementId: performanceWorker.measurementId || null,
    updatedAt: Number.isFinite(Number(performanceWorker.updatedAt))
      ? Number(performanceWorker.updatedAt) : null,
    pending: Number.isFinite(Number(performanceWorker.pending))
      ? Number(performanceWorker.pending) : null,
    pendingMax: heartbeatPendingMax,
    p99RttMillis: Number.isFinite(Number(performanceWorker.p99RttMillis))
      ? Number(performanceWorker.p99RttMillis) : null,
    p99RttMaxMillis: heartbeatRttP99Max,
    maxRttMillis: Number.isFinite(Number(performanceWorker.maxRttMillis))
      ? Number(performanceWorker.maxRttMillis) : null,
    rttMaxMillis: heartbeatRttMax,
    longestDelayMillis: Number.isFinite(Number(performanceWorker.longestHeartbeatDelayMillis))
      ? Number(performanceWorker.longestHeartbeatDelayMillis) : null,
    delayMaxMillis: heartbeatDelayMax,
    note: "Heartbeat pending is probe health, not a world-generation or packet backlog.",
  };
  const worldgen = {
    ...(performanceWorker.worldgen || {}),
    runtimeInvariant: runtimeInvariants.components.worldgen,
    note: "Worker worldgen scheduler limits are release-gated when runtimeInvariants is required.",
  };
  const performanceEvidence = buildPerformanceEvidence({
    frames,
    framePacing: framePacingEvidence,
    memory,
    freezes,
    travel,
    gates,
    buildIdentity: context.buildIdentity || null,
  });
  const rawFrameStatus = Number(performanceFrame.frameCount || 0) > 0
      && Number(performanceFrame.sampleCount || 0) > 0
      && frameTimes.length > 0
      && lostFrameTimes === 0
      && Number(frames.invalidFrameIntervalCount || 0) === 0
      && frameTimes.length === Number(performanceFrame.frameCount || 0)
    ? "pass" : "fail";
  const travelStatus = profile.requireNewChunks
    ? travel.crossedNewTerrainVerdict : "not-required";
  const targetingStatus = gates.targeting ? targeting.verdict : "not-required";
  const externalSmokeEvidence = context.externalSmokeEvidence || {
    verdict: gates.runtimeInvariants ? "inconclusive" : "not-required",
    required: [],
    results: [],
  };
  const naturalDistanceStatus = pinWorkerDistance
    ? "inconclusive"
    : (workerStartContractMatches.length > 0
        && optionsPreferenceDistance === expectedDistanceLabel
      ? "pass" : "fail");
  const naturalDistanceReasons = pinWorkerDistance
    ? ["--pin-worker-distance enabled; raw natural Worker evidence is non-gating"]
    : (naturalDistanceStatus === "pass" ? [] : [
        `natural Worker/options distance did not prove exact ${expectedDistanceLabel}`,
      ]);
  const checks = [
    {name: "environment validity", status: contractEvaluation.independent.environment.verdict,
      actual: environment},
    {name: "natural Worker distance and active profile storage namespace",
      status: naturalDistanceStatus,
      actual: {
        expected: expectedDistanceLabel,
        optionsPreference: optionsPreferenceDistance,
        naturalWorkerDistances: [...naturalWorkerDistances],
        matchingStartMessages: workerStartContractMatches.length,
        reasons: naturalDistanceReasons,
      }},
    {name: "new single-player world becomes interactive within the startup target",
      status: startup.verdict, actual: startup},
    {name: "profile frame-performance thresholds",
      status: contractEvaluation.independent.framePerformance.verdict,
      actual: {frames, reasons: contractEvaluation.independent.framePerformance.reasons}},
    {name: "no contracted freeze signal", status: contractEvaluation.independent.freezes.verdict,
      actual: freezes},
    {name: "raw frame ring is complete and ordered",
      status: rawFrameStatus,
      actual: {sampled: frameTimes.length, frameCount: performanceFrame.frameCount, lostFrameTimes}},
    {name: "compositor output is nonblank before and after measurement",
      status: visualOutput.verdict, actual: visualOutput},
    {name: "queue high-water contract",
      status: contractEvaluation.independent.queues.verdict, actual: queues},
    {name: "decoded packet queue contract",
      status: contractEvaluation.independent.decodedPacketQueue.verdict,
      actual: decodedPacketQueue},
    {name: "Worker heartbeat satisfies p99 and maximum latency bounds",
      status: workerStatus,
      actual: heartbeat},
    {name: "new-chunk traversal", status: travelStatus, actual: travel},
    {name: "targeting telemetry remains fresh", status: targetingStatus, actual: targeting},
    {name: "runtime invariant contract",
      status: contractEvaluation.independent.runtimeInvariants.verdict,
      actual: runtimeInvariants},
    {name: "required external invariant smokes",
      status: gates.runtimeInvariants ? externalSmokeEvidence.verdict : "not-required",
      actual: externalSmokeEvidence},
    {name: "gameplay block authority",
      status: contractEvaluation.independent.gameplayAuthority.verdict,
      actual: gameplayAuthority},
    {name: "post-GC memory trend", status: contractEvaluation.independent.memory.verdict,
      actual: memory},
    {name: "crash/freeze/OOM/disconnect/context stability",
      status: contractEvaluation.independent.stability.verdict, actual: stability},
  ].map((check) => ({...check, passed: check.status === "pass"}));
  const smokeChecks = [
    frameTimes.length > 0,
    samples.every((sample) => !sample.error && Number(sample.evaluationLatencyMillis) < 500),
    stability.verdict === "pass",
    visualOutput.verdict === "pass",
  ];
  const strictStatuses = checks
    .map((check) => check.status)
    .filter((status) => status !== "not-required");
  const passed = strict
    ? (!pinWorkerDistance && strictStatuses.every((status) => status === "pass"))
    : smokeChecks.every(Boolean);
  let strictVerdict = "pass";
  if (strictStatuses.includes("invalid")) strictVerdict = "invalid";
  else if (strictStatuses.includes("fail")) strictVerdict = "fail";
  else if (strictStatuses.includes("inconclusive")
      || strictStatuses.includes("not-evaluated")) strictVerdict = "inconclusive";
  if (pinWorkerDistance && strict) strictVerdict = "inconclusive";
  const releaseEvidence = strict && !pinWorkerDistance && releaseDistanceProfileCompatible
    && profile.releaseEvidence === true;
  return {
    mode: strict
      ? (pinWorkerDistance
          ? "diagnostic-pin-non-gating"
          : (releaseEvidence ? "release-gating" : "diagnostic-stress"))
      : (pinWorkerDistance ? "smoke-pin-non-gating" : "smoke-non-gating"),
    gating: releaseEvidence,
    releaseEvidence,
    workerDistanceMode: pinWorkerDistance ? "harness-pin-diagnostic" : "natural-observation",
    evidenceRole: profile.evidenceRole || null,
    verdict: strict ? strictVerdict : (passed ? "non-gating" : "smoke-fail"),
    passed,
    note: pinWorkerDistance
      ? "Worker distance pin is a diagnostic fixture only; naturalWorkerEvidence is the release contract and this report is never release evidence."
      : (strict
          ? (releaseEvidence
              ? "All independent gates must pass. Fail, invalid, and inconclusive are non-zero outcomes."
              : "Diagnostic stress profile only; a pass is not evidence for the 6/4 release target.")
          : "Smoke mode only checks plumbing and stability; it is never release evidence."),
    checks,
    contractEvaluation,
    environment,
    naturalWorkerEvidence,
    harnessOverrideEvidence,
    performanceEvidence,
    failureEvidence: strict && !passed ? performanceEvidence : null,
    startup,
    frame: frames,
    freezes,
    queues,
    decodedPacketQueue,
    workerMessage: performanceWorker,
    heartbeat,
    worldgen,
    travel,
    targeting,
    runtimeInvariants,
    externalSmokeEvidence,
    gameplayAuthority,
    visualOutput,
    memory,
    resources,
    stability,
    raw: {
      frameTimesMillis: frameTimes,
      frameHistogramQuarterMillisecondBuckets: performanceFrame.histogram || [],
      queueTimeline,
      heapSamples,
    },
  };
}


let staticServer;
let chrome;
let session;
let browserSession;
let browserSessionError = null;
let profileDirectory;
let stopTravel = async () => {};
let debuggingPort = attachPort;
let chromeOutput = "";
const events = [];
const stickyFatalEvents = [];
const immediateFatalPattern = /---- Minecraft Crash Report ----|Game crashed!|renderer crash|worker crash|bootstrap-crash|out of memory|allocation failed|\bOOM\b/i;
const appendChromeOutput = (chunk) => {
  chromeOutput = (chromeOutput + String(chunk)).slice(-64_000);
};
const recordEvent = (event) => {
  events.push(event);
  if (events.length > 1000) events.splice(0, events.length - 1000);
  const text = String(event?.text || event?.detail || "");
  if (event?.source === "exception"
      || event?.source === "renderer crash"
      || event?.source === "renderer detached"
      || immediateFatalPattern.test(text)) {
    stickyFatalEvents.push(event);
    if (stickyFatalEvents.length > 64) stickyFatalEvents.shift();
  }
};
const combinedEvents = () => {
  const combined = events.slice();
  for (const event of stickyFatalEvents) {
    if (!combined.includes(event)) combined.push(event);
  }
  return combined;
};
const benchmarkStartedAt = Date.now();
let result;
let externalSmokeEvidence;
const watchdogMillis = startupTimeoutMillis + warmupMillis + frameMeasurementMillis
  + cleanupMillis + Number(measurementContract.watchdogGraceMs || 300_000);
const watchdogTimer = setTimeout(() => {
  recordEvent({
    at: Date.now(),
    source: "benchmark watchdog",
    text: `benchmark exceeded its ${watchdogMillis} ms hard deadline`,
  });
  if (session) session.close();
  if (browserSession) browserSession.close();
  if (chrome) chrome.kill("SIGTERM");
}, watchdogMillis);

try {
  externalSmokeEvidence = await runExternalSmokeSuite();
  let targetUrl = explicitUrl;
  if (!targetUrl) {
    staticServer = await startStaticServer(distRoot);
    targetUrl = staticServer.url;
  }
  targetUrl = configureBenchmarkUrl(targetUrl);
  if (!debuggingPort) {
    debuggingPort = await freePort();
    profileDirectory = await mkdtemp(resolve(tmpdir(), "gaius-chunk-benchmark-"));
    const chromeArgs = [
      "--remote-debugging-port=" + debuggingPort,
      "--remote-allow-origins=*",
      "--user-data-dir=" + profileDirectory,
      "--no-first-run",
      "--no-default-browser-check",
      "--ignore-gpu-blocklist",
      "--enable-webgl",
      "--enable-unsafe-swiftshader",
      "--disable-background-networking",
      "--disable-background-timer-throttling",
      "--disable-renderer-backgrounding",
      // Synthetic CDP clicks must never put a visible benchmark tab into OS-level
      // pointer lock; keep the operator's real cursor independent of the test.
      "--disable-pointer-lock",
      "--enable-precise-memory-info",
      "--window-size=" + Number(environmentContract.viewport?.width || 1280)
        + "," + Number(environmentContract.viewport?.height || 720),
    ];
    if (headless) chromeArgs.push("--headless=new");
    chromeArgs.push("about:blank");
    chrome = spawn(chromeBinary, chromeArgs, {stdio: ["ignore", "pipe", "pipe"]});
    chrome.stdout.on("data", appendChromeOutput);
    chrome.stderr.on("data", appendChromeOutput);
    chrome.on("exit", (code, signal) => recordEvent({
      at: Date.now(),
      source: "chrome process",
      text: `Chrome exited with code ${code} signal ${signal || "none"}`,
    }));
  }

  const browserVersion = await waitForJson(
    "http://127.0.0.1:" + debuggingPort + "/json/version",
    20_000,
  );
  if (browserVersion.webSocketDebuggerUrl) {
    try {
      browserSession = new CdpSession(browserVersion.webSocketDebuggerUrl);
      await browserSession.open();
    } catch (error) {
      browserSessionError = String(error && (error.message || error) || error);
      recordEvent({
        at: Date.now(),
        source: "process-rss",
        text: `browser CDP session unavailable: ${browserSessionError}`,
      });
      if (browserSession) browserSession.close();
      browserSession = null;
    }
  } else {
    browserSessionError = "Chrome /json/version did not expose webSocketDebuggerUrl";
    recordEvent({at: Date.now(), source: "process-rss", text: browserSessionError});
  }
  const targets = await waitForJson("http://127.0.0.1:" + debuggingPort + "/json/list", 20_000);
  const page = targets.find((target) => target.type === "page");
  if (!page?.webSocketDebuggerUrl) throw new Error("Chrome did not expose a page target");
  session = new CdpSession(page.webSocketDebuggerUrl);
  await session.open();
  session.on("Runtime.consoleAPICalled", (event) => {
    recordEvent({
      at: Date.now(),
      source: "console",
      type: event.type,
      text: (event.args || []).map((item) => item.value ?? item.description ?? "").join(" "),
    });
  });
  session.on("Runtime.exceptionThrown", (event) => {
    recordEvent({
      at: Date.now(),
      source: "exception",
      text: event.exceptionDetails?.exception?.description || event.exceptionDetails?.text || "Unknown exception",
    });
  });
  session.on("Inspector.targetCrashed", (event) => {
    recordEvent({at: Date.now(), source: "renderer crash", text: "renderer crash", detail: event});
  });
  session.on("Inspector.detached", (event) => {
    recordEvent({at: Date.now(), source: "renderer detached", text: "renderer detached", detail: event});
  });
  await Promise.all([
    session.send("Page.enable"),
    session.send("Runtime.enable"),
    session.send("Performance.enable"),
    session.send("Inspector.enable"),
  ]);
  await session.send("Emulation.setDeviceMetricsOverride", {
    width: Number(environmentContract.viewport?.width || 1280),
    height: Number(environmentContract.viewport?.height || 720),
    deviceScaleFactor: Number(environmentContract.viewport?.deviceScaleFactor || 1),
    mobile: false,
  });
  await session.send("Page.bringToFront");
  const navigation = await session.send("Page.navigate", {url: targetUrl});
  if (navigation.errorText) throw new Error("Chrome navigation failed: " + navigation.errorText);
  const profileGateResult = await passProfileGate(session);
  if (!profileGateResult?.seeded) {
    recordEvent({
      at: Date.now(),
      source: "benchmark",
      text: "The benchmark could not seed profile options before profile submit",
    });
  }
  const browserMemoryBaselineSnapshot = await collectCombinedBrowserMemory(session);
  const browserMemoryBaseline = browserMemoryBaselineSnapshot.combined;
  await rm(visualOutputDirectory, {recursive: true, force: true});
  await resetMeasurement(session);
  const semanticWorldEntryTimings = await enterWorld(session);
  let center = await focusGame(session);
  center = await aimTowardTerrain(session, center);
  const worldReadiness = await waitForStrictWorldReadiness(
    session,
    semanticWorldEntryTimings.worldLoadStartedAt + startupTimeoutMillis,
  );
  if (worldReadiness.verdict !== "pass") {
    throw new Error(
      "Strict world readiness failed: " + JSON.stringify({
        reason: worldReadiness.reason,
        consecutiveFrames: worldReadiness.consecutiveFrames,
        streakMillis: worldReadiness.streakMillis,
        blockHitFrames: worldReadiness.blockHitFrames,
        validVisualSamples: worldReadiness.validVisualSamples,
        last: worldReadiness.last,
      }),
    );
  }
  const worldEntryTimings = {
    ...semanticWorldEntryTimings,
    semanticActiveAt: semanticWorldEntryTimings.activeAt,
    activeAt: worldReadiness.interactiveAt,
    worldInteractiveMillis:
      worldReadiness.interactiveAt - semanticWorldEntryTimings.worldLoadStartedAt,
    totalMillis: worldReadiness.interactiveAt - semanticWorldEntryTimings.startedAt,
    readiness: worldReadiness,
  };
  const gameplayProbe = await installGameplayAuthorityProbe(session);
  if (!gameplayProbe?.installed) {
    recordEvent({
      at: Date.now(),
      source: "gameplay-authority",
      text: gameplayProbe?.reason || "live gameplay authority probe was not installed",
    });
  }

  await resetMeasurement(session);
  const warmupSamples = [];
  await sampleFor(session, warmupMillis, warmupSamples);
  const visualOutputSamples = await collectVisualOutput(session, "pre-measurement");
  if (benchmarkProfile.workload === "traverse") {
    stopTravel = await startTravel(session, center);
  }
  const reset = await resetMeasurement(session);
  if (!reset.distances.includes(expectedDistanceLabel)) {
    recordEvent({
      at: Date.now(),
      source: "benchmark",
      text: `Expected ${expectedDistanceLabel} distances, got ${reset.distances.join(",")}`,
    });
  }
  const samples = [];
  const stabilitySamples = [];
  let heapSamples = [];
  let processRssSamples = [];
  let performanceTelemetry;
  let workerDistanceEvidence;
  let gameplayExercise;
  const measurementStartedAt = Date.now();
  const processRssDurationMillis = frameMeasurementMillis;
  const performanceTask = (async () => {
    await sampleFor(session, frameMeasurementMillis, samples);
    samples.push(await samplePage(session));
    performanceTelemetry = await finalTelemetry(session);
  })();
  const heapTask = heapMillis > 0
    ? collectHeapTrend(session, heapMillis, heapSampleMillis, heapIntervalMillis)
    : Promise.resolve([]);
  const continuousVisualTask = collectContinuousVisualOutput(
    session,
    frameMeasurementMillis,
  );
  const processRssTask = collectChromeProcessRssTimeline(
    browserSession,
    processRssDurationMillis,
    processRssSampleMillis,
    heapMillis > 0 ? "soak" : "performance",
    browserSessionError,
  );
  const gameplayTask = benchmarkProfile.gates?.gameplayAuthority
    ? exerciseGameplayAuthority(session, center, stopTravel)
    : Promise.resolve({verdict: "not-required"});
  let continuousVisualSamples;
  [, heapSamples, continuousVisualSamples, processRssSamples, gameplayExercise] = await Promise.all([
    performanceTask,
    heapTask,
    continuousVisualTask,
    processRssTask,
    gameplayTask,
  ]);
  workerDistanceEvidence = await collectWorkerDistanceEvidence(session);
  visualOutputSamples.push(...continuousVisualSamples);
  if (gameplayExercise?.verdict === "inconclusive") {
    recordEvent({
      at: Date.now(),
      source: "gameplay-authority",
      text: String(gameplayExercise.reason || "gameplay action evidence was inconclusive"),
    });
  }
  const stabilityTelemetry = performanceTelemetry;
  const measurementEndedAt = Date.now();
  await stopTravel();
  stopTravel = async () => {};
  visualOutputSamples.push(...await collectVisualOutput(session, "post-measurement"));
  let leftWorld = false;
  try {
    // Release benchmark input before Escape/leave-world.  If a CDP key-up or pointer-lock
    // transition was delayed, Minecraft can otherwise keep the browser canvas in a captured
    // selection state and never expose its pause screen to cleanup.
    await releaseInputCapture(session);
    await leaveWorld(session);
    leftWorld = true;
  } catch (error) {
    recordEvent({
      at: Date.now(),
      source: "cleanup",
      text: String(error && (error.stack || error.message) || error),
    });
  }
  const [cleanupHeapSamples, cleanupProcessRssSamples] = await Promise.all([
    collectHeapTrend(
      session,
      cleanupMillis,
      Math.min(5000, Math.max(1000, cleanupMillis)),
      5000,
      "cleanup",
    ),
    collectChromeProcessRssTimeline(
      browserSession,
      cleanupMillis,
      Math.min(processRssSampleMillis, Math.max(1000, cleanupMillis)),
      "cleanup",
      browserSessionError,
    ),
  ]);
  const cleanupTelemetry = await finalTelemetry(session);
  const telemetry = {
    ...stabilityTelemetry,
    capturedAt: performanceTelemetry.capturedAt,
    stabilityCapturedAt: stabilityTelemetry.capturedAt,
    frame: performanceTelemetry.frame,
    stabilityFrame: stabilityTelemetry.frame,
    environment: performanceTelemetry.environment,
    performanceProbe: performanceTelemetry.probe,
    stabilityProbe: stabilityTelemetry.probe,
    cleanupProbe: cleanupTelemetry.probe,
    performanceWorker: performanceTelemetry.worker,
    performanceNetwork: performanceTelemetry.network,
    runtimeInvariants: performanceTelemetry.runtimeInvariants,
    stabilityRuntimeInvariants: stabilityTelemetry.runtimeInvariants,
    cleanupRuntimeInvariants: cleanupTelemetry.runtimeInvariants,
    cleanupMemory: cleanupTelemetry.memory,
    targeting: performanceTelemetry.targeting,
    longTasks: stabilityTelemetry.longTasks,
    performanceLongTasks: performanceTelemetry.longTasks,
    minecraftEvents: [
      ...(performanceTelemetry.minecraftEvents || []),
      ...(stabilityTelemetry.minecraftEvents || []),
    ],
    pipeline: {
      ...stabilityTelemetry.pipeline,
      taskHistogram: performanceTelemetry.pipeline.taskHistogram,
      uploadPassHistogram: performanceTelemetry.pipeline.uploadPassHistogram,
    },
  };
  const analysis = analyze(
    samples,
    stabilitySamples,
    telemetry,
    heapSamples,
    combinedEvents(),
    !smoke,
    {
      browserVersion,
      targetUrl,
      headless,
      freshChromeProfile: Boolean(profileDirectory),
      profile: benchmarkProfile,
      performanceContract,
      runtimeInvariantContract: runtimeInvariantContractForProfile,
      worldgenTelemetryMode: activeWorldgenTelemetryMode,
      browserMemoryBaseline,
      browserMemoryBaselineSources: browserMemoryBaselineSnapshot,
      buildIdentity: benchmarkBuildIdentity,
      cleanupHeapSamples,
      processRssSamples,
      cleanupProcessRssSamples,
      processRssDurationMillis,
      visualOutputSamples,
      leftWorld,
      profileGateResult,
      workerDistanceEvidence,
      measurementStartedAt,
      measurementEndedAt,
      expectedWorkerMeasurementId: reset.measurementId,
      worldEntryTimings,
      warmupSamples,
      runEventStartedAt: benchmarkStartedAt,
      runEventEndedAt: Date.now(),
      externalSmokeEvidence,
    },
  );
  result = {
    schemaVersion: performanceContract.schemaVersion,
    generatedAt: new Date().toISOString(),
    passed: analysis.passed,
    verdict: analysis.verdict,
    targetUrl,
    buildIdentity: benchmarkBuildIdentity,
    configuration: {
      browser: "Google Chrome",
      browserVersion,
      buildIdentity: benchmarkBuildIdentity,
      headless,
      requestedFrameLimit: environmentContract.maxFpsLabel || "Unlimited",
      verifiedUncapped: analysis.environment?.frameLimiter?.uncapped === true,
      mode: analysis.mode,
      gating: analysis.gating,
      releaseEvidence: analysis.releaseEvidence,
      evidenceRole: analysis.evidenceRole,
      contractSchemaVersion: performanceContract.schemaVersion,
      profileName,
      profile: benchmarkProfile,
      worldgenTelemetryMode: activeWorldgenTelemetryMode,
      expectedRenderDistance,
      expectedSimulationDistance,
      workerDistanceContract: analysis.environment?.distanceContract || null,
      warmupMillis,
      performanceMillis,
      fpsWindowMillis: frameMeasurementMillis,
      heapMillis,
      soakMillis: heapMillis,
      heapIntervalMillis,
      heapSampleMillis,
      processRssSampleMillis,
      processRssDurationMillis,
      cleanupMillis,
      sampleMillis,
      startupTimeoutMillis,
      strictChecks: !smoke,
    },
    performanceEvidence: analysis.performanceEvidence,
    failureEvidence: analysis.failureEvidence,
    analysis,
    telemetry,
    samples,
    stabilitySamples,
    mergedSamples: mergeMonotonicSamples(samples, stabilitySamples),
    heapSamples,
    cleanupHeapSamples,
    processRssSamples,
    cleanupProcessRssSamples,
    visualOutputSamples,
    cleanupTelemetry,
    browserMemoryBaseline,
    browserMemoryBaselineSources: browserMemoryBaselineSnapshot,
    profileGateResult,
    workerDistanceEvidence,
    naturalWorkerEvidence: workerDistanceEvidence?.naturalWorkerEvidence || null,
    harnessOverrideEvidence: workerDistanceEvidence?.harnessOverrideEvidence || null,
    worldEntryTimings,
    externalSmokeEvidence,
    leftWorld,
    warmupSampleCount: warmupSamples.length,
    events: combinedEvents().slice(-500),
    chromeOutput: chromeOutput.slice(-12_000),
  };
  await mkdir(resolve(outputPath, ".."), {recursive: true});
  await writeFile(outputPath, JSON.stringify(result, null, 2) + "\n");
  console.log(JSON.stringify({
    passed: analysis.passed,
    verdict: analysis.verdict,
    gating: analysis.gating,
    output: outputPath,
    frame: analysis.frame,
    freezes: analysis.freezes,
    queues: analysis.queues,
    workerMessage: analysis.workerMessage,
    travel: analysis.travel,
    visualOutput: analysis.visualOutput,
    memory: analysis.memory,
    runtimeInvariants: analysis.runtimeInvariants,
    externalSmokeEvidence: analysis.externalSmokeEvidence,
    nonPassingChecks: analysis.checks.filter(
      (check) => check.status !== "pass" && check.status !== "not-required",
    ),
  }, null, 2));
  process.exitCode = analysis.passed ? 0 : 1;
} catch (error) {
  const startupDiagnostics = await collectFailureDiagnostics(session);
  const failure = {
    schemaVersion: performanceContract.schemaVersion,
    generatedAt: new Date().toISOString(),
    passed: false,
    verdict: pinWorkerDistance ? "inconclusive" : "fail",
    buildIdentity: benchmarkBuildIdentity,
    configuration: {
      profileName,
      profile: benchmarkProfile,
      worldgenTelemetryMode: activeWorldgenTelemetryMode,
      strictChecks: !smoke,
      mode: pinWorkerDistance
        ? (smoke ? "smoke-pin-non-gating" : "diagnostic-pin-non-gating")
        : (smoke ? "smoke-non-gating"
          : (benchmarkProfile.releaseEvidence === true && releaseDistanceProfileCompatible
            ? "release-gating" : "diagnostic-stress")),
      gating: !pinWorkerDistance && !smoke && releaseDistanceProfileCompatible
        && benchmarkProfile.releaseEvidence === true,
      releaseEvidence: !pinWorkerDistance && !smoke && releaseDistanceProfileCompatible
        && benchmarkProfile.releaseEvidence === true,
      workerDistanceMode: pinWorkerDistance ? "harness-pin-diagnostic" : "natural-observation",
      workerDistancePin: pinWorkerDistance,
      workerDistanceContract: {
        mode: pinWorkerDistance ? "harness-pin-diagnostic" : "natural-observation",
        releaseEligible: !pinWorkerDistance && releaseDistanceProfileCompatible,
        releaseTargetProfile: "26.2",
        activeProfileId: activeStorageProfileId,
        expectedStartDistance: expectedDistanceLabel,
        expectedStorage: activeStorageConfig,
      },
      buildIdentity: benchmarkBuildIdentity,
    },
    failureEvidence: buildPerformanceEvidence({
      buildIdentity: benchmarkBuildIdentity,
      gates: benchmarkProfile.gates || {},
    }),
    error: String(error && (error.stack || error.message) || error),
    naturalWorkerEvidence: null,
    harnessOverrideEvidence: {
      enabled: pinWorkerDistance,
      mode: pinWorkerDistance ? "harness-pin-diagnostic" : "disabled",
      releaseEligible: false,
    },
    startupDiagnostics,
    events: combinedEvents().slice(-500),
    chromeOutput: chromeOutput.slice(-12_000),
  };
  await mkdir(resolve(outputPath, ".."), {recursive: true});
  await writeFile(outputPath, JSON.stringify(failure, null, 2) + "\n");
  console.error(failure.error);
  process.exitCode = 1;
} finally {
  clearTimeout(watchdogTimer);
  try {
    await stopTravel();
  } catch (ignored) {
  } finally {
    // Never let a failed workload-stop skip the unconditional key/mouse/pointer
    // cleanup.  This is intentionally before closing either CDP session.
    await releaseInputCapture(session);
  }
  if (session) session.close();
  if (browserSession) browserSession.close();
  if (chrome && !keepChrome) {
    chrome.kill("SIGTERM");
    await sleep(500);
    if (!chrome.killed) chrome.kill("SIGKILL");
  }
  if (staticServer) {
    await new Promise((resolveClose) => staticServer.server.close(resolveClose));
  }
  if (profileDirectory && !keepChrome) {
    await rm(profileDirectory, {recursive: true, force: true});
  }
}
