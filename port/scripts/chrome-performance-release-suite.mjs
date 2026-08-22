#!/usr/bin/env node

import {spawn} from "node:child_process";
import {createHash} from "node:crypto";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {basename, dirname, isAbsolute, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {summarizeAcceptanceEvidence} from "./performance-metrics.mjs";

const scriptsRoot = fileURLToPath(new URL(".", import.meta.url));
const repositoryRoot = resolve(scriptsRoot, "../..");
const portRoot = resolve(repositoryRoot, "port");
const contractPath = resolve(scriptsRoot, "performance-contract.json");
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
// Child benchmarks run from repositoryRoot. Resolve all relative environment
// paths explicitly instead of letting cwd=port/scripts or cwd=repositoryRoot
// silently select a different profile/build/dist/overlay tree.
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
const profileEnvironmentNames = [
  "GAIUS_VERSION_PROFILE_PATH",
  "GAIUS_BUILD_ROOT",
  "GAIUS_DIST_DIRECTORY",
  "GAIUS_OVERLAY_DIRECTORY",
];
const isolated = profileEnvironmentNames.some(
  (name) => String(process.env[name] || "").trim() !== "",
);
const portConfig = JSON.parse(await readFile(resolve(portRoot, "config.json"), "utf8"));
const configuredProfilePath = process.env.GAIUS_VERSION_PROFILE_PATH
  || String(portConfig.versionProfile || "");
const activeVersionProfilePath = resolveRepositoryPortPath(configuredProfilePath);
if (!activeVersionProfilePath
    || !(activeVersionProfilePath === portRoot
      || activeVersionProfilePath.startsWith(portRoot + "\\")
      || activeVersionProfilePath.startsWith(portRoot + "/"))) {
  throw new Error("The active version profile escaped the port directory");
}
const activeVersionProfileBytes = await readFile(activeVersionProfilePath);
const activeVersionProfile = JSON.parse(activeVersionProfileBytes.toString("utf8"));
const activeVersionProfileSha256 = createHash("sha256").update(activeVersionProfileBytes).digest("hex");
const profileId = String(activeVersionProfile.id || "");
const profilePath = normalizedPath(relative(portRoot, activeVersionProfilePath));
if (!profileId || !/^versions\/[^/]+\.json$/i.test(profilePath)) {
  throw new Error(`The active version profile path is invalid: ${profilePath}`);
}
const activeStorage = activeVersionProfile.storage || {};
const activeStorageConfig = {
  profileId,
  worldVersion: Number(activeVersionProfile.worldVersion),
  worldgenTelemetryMode: String(activeVersionProfile.worldgenTelemetryMode || ""),
  storageSchema: Number(activeStorage.schema),
  storageDatabaseName: String(activeStorage.databaseName || ""),
  storagePrefix: String(activeStorage.prefix || ""),
  storageOpfsDirectory: String(activeStorage.opfsDirectory || ""),
};
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
const configuredBuildRoot = process.env.GAIUS_BUILD_ROOT
  ? resolveRepositoryPortPath(process.env.GAIUS_BUILD_ROOT)
  : (isolated ? resolve(portRoot, "target", profileId) : resolve(portRoot, "target"));
const configuredOverlayDirectory = process.env.GAIUS_OVERLAY_DIRECTORY
  ? resolveRepositoryPortPath(process.env.GAIUS_OVERLAY_DIRECTORY)
  : (isolated
    ? resolve(portRoot, "work", "overlays", profileId)
    : resolve(portRoot, "work", "overlays"));
const distRoot = resolve(process.env.GAIUS_DIST_DIRECTORY
  ? resolveRepositoryPortPath(process.env.GAIUS_DIST_DIRECTORY)
  : (isolated ? resolve(portRoot, "web", "dist", profileId) : resolve(portRoot, "web", "dist")));
if (isolated && basename(distRoot) !== profileId) {
  throw new Error(
    `An isolated release suite must use a profile-scoped dist basename ${JSON.stringify(profileId)}; got ${JSON.stringify(basename(distRoot))}`,
  );
}
const manifestPath = resolve(distRoot, "Gaius.manifest.json");
const benchmarkPath = resolve(scriptsRoot, "chrome-chunk-benchmark.mjs");
const contract = JSON.parse(await readFile(contractPath, "utf8"));
const runtimeInvariantContractForProfile = {
  ...(contract.runtimeInvariants || {}),
  worldgen: {
    ...(contract.runtimeInvariants?.worldgen || {}),
    telemetryMode: activeWorldgenTelemetryMode,
    worldgenTelemetryMode: activeWorldgenTelemetryMode,
  },
};
const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
const REQUIRED_UNCAPPED_TELEMETRY_FIELDS = [
  "swapInterval",
  "uncappedYieldCount",
  "vsyncYieldCount",
  "presentToRafCount",
  "fairYieldCount",
  "messageChannelCreateFailureCount",
  "messageChannelPostFailureCount",
  "messageChannelRebuildCount",
  "cancelledMessageTaskCount",
  "watchdogYieldCount",
];
const UNCAPPED_HEALTH_LIMITS = [
  ["messageChannelCreateFailureCount", "maximumMessageChannelCreateFailureCount"],
  ["messageChannelPostFailureCount", "maximumMessageChannelPostFailureCount"],
  ["messageChannelRebuildCount", "maximumMessageChannelRebuildCount"],
  ["cancelledMessageTaskCount", "maximumCancelledMessageTaskCount"],
  ["watchdogYieldCount", "maximumWatchdogYieldCount"],
];

function hasFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function uniqueStrings(values) {
  return [...new Set((Array.isArray(values) ? values : [])
    .map((value) => String(value).trim())
    .filter(Boolean))];
}

function validateNaturalWorkerDistanceEvidence(report, profile) {
  const failures = [];
  const expectedRender = Number(profile?.renderDistance);
  const expectedSimulation = Number(profile?.simulationDistance);
  const expectedDistance = `${expectedRender}:${expectedSimulation}`;
  const analysis = report?.analysis || {};
  const configuration = report?.configuration || {};
  const environmentDistance = analysis.environment?.distanceContract
    || configuration.workerDistanceContract || {};
  const natural = analysis.naturalWorkerEvidence
    || report?.naturalWorkerEvidence
    || environmentDistance.naturalWorkerEvidence;
  const harness = analysis.harnessOverrideEvidence
    || report?.harnessOverrideEvidence
    || environmentDistance.harnessOverrideEvidence;
  if (!natural || typeof natural !== "object") {
    failures.push("strict child report is missing naturalWorkerEvidence");
  }
  const messages = Array.isArray(natural?.messages) ? natural.messages : [];
  if (!Array.isArray(natural?.messages)) {
    failures.push("strict child report naturalWorkerEvidence.messages is missing");
  }
  if (natural?.complete === false || Number(natural?.truncatedCount || 0) > 0) {
    failures.push("naturalWorkerEvidence was truncated; raw Worker launch evidence is incomplete");
  }
  if (harness?.enabled === true || harness?.releaseEligible === true
      || String(environmentDistance.mode || "") === "harness-pin-diagnostic") {
    failures.push("harnessOverrideEvidence cannot satisfy strict release evidence");
  }
  if (String(environmentDistance.mode || "") !== "natural-observation") {
    failures.push("strict release Worker distance mode must be natural-observation");
  }
  if (environmentDistance.releaseEligible === false) {
    failures.push("strict release Worker distance contract is marked non-release-eligible");
  }
  if (String(environmentDistance.optionsPreference || "") !== expectedDistance) {
    failures.push(
      `options preference must be exactly ${expectedDistance}; got ${JSON.stringify(environmentDistance.optionsPreference)}`,
    );
  }
  const matches = messages.filter((item) => item && item.type === "start"
    && Number(item.renderDistance) === expectedRender
    && Number(item.simulationDistance) === expectedSimulation
    && String(item.profileId || "") === activeStorageConfig.profileId
    && Number(item.worldVersion) === activeStorageConfig.worldVersion
    && Number(item.storageSchema) === activeStorageConfig.storageSchema
    && String(item.storageDatabaseName || "") === activeStorageConfig.storageDatabaseName
    && String(item.storagePrefix || "") === activeStorageConfig.storagePrefix
    && String(item.storageOpfsDirectory || "") === activeStorageConfig.storageOpfsDirectory);
  if (matches.length === 0) {
    failures.push(
      `no raw natural Worker start message proved ${expectedDistance} with active profile storage namespace`,
    );
  }
  return failures;
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]));
  }
  return value;
}

function canonicalJson(value) {
  return JSON.stringify(canonicalize(value));
}

function exactIdentityValue(actual, expected) {
  if ((actual && typeof actual === "object")
      || (expected && typeof expected === "object")) {
    return canonicalJson(actual) === canonicalJson(expected);
  }
  return Object.is(actual, expected);
}

function exactIdentityFailure(label, actual, expected) {
  return `${label}=${JSON.stringify(actual)} expected ${JSON.stringify(expected)}`;
}

/**
 * Validate the profile portion of a release manifest without coercing any
 * telemetry/storage values.  The portable manifest is an input to the
 * release gate, so a missing field is deliberately different from an
 * equivalent-looking value (for example, the string "4903" is not the
 * configured world version number 4903).
 *
 * `expected` is injectable so the release-suite smoke can exercise this gate
 * against an in-memory/file fixture while the checked-in dist is stale.
 */
export function validateManifestIdentity(manifest, expected = {}) {
  const expectedIdentity = {
    profileId,
    profilePath,
    profileSha256: activeVersionProfileSha256,
    clientDistribution: activeVersionProfile.clientDistribution,
    protocolVersion: Number(activeVersionProfile.protocolVersion),
    worldVersion: Number(activeVersionProfile.worldVersion),
    worldgenTelemetryMode: activeWorldgenTelemetryMode,
    storage: activeStorage,
    ...expected,
  };
  if (!manifest || typeof manifest !== "object") {
    return ["manifest is not an object"];
  }

  const failures = [];
  const manifestProfilePath = normalizedPath(manifest.profilePath);
  const expectedProfilePath = normalizedPath(expectedIdentity.profilePath);
  const topLevelChecks = [
    ["manifest.profile", manifest.profile, expectedIdentity.profileId],
    ["manifest.profilePath", manifestProfilePath, expectedProfilePath],
    ["manifest.profileSha256", manifest.profileSha256, expectedIdentity.profileSha256],
    ["manifest.worldVersion", manifest.worldVersion, expectedIdentity.worldVersion],
    ["manifest.worldgenTelemetryMode", manifest.worldgenTelemetryMode,
      expectedIdentity.worldgenTelemetryMode],
    ["manifest.storage", manifest.storage, expectedIdentity.storage],
  ];
  for (const [label, actual, expectedValue] of topLevelChecks) {
    if (!exactIdentityValue(actual, expectedValue)) {
      failures.push(exactIdentityFailure(label, actual, expectedValue));
    }
  }

  const nestedProfile = manifest?.buildIdentity?.profile;
  if (!nestedProfile || typeof nestedProfile !== "object") {
    failures.push("manifest.buildIdentity.profile is missing");
    return failures;
  }
  const nestedProfileChecks = [
    ["manifest.buildIdentity.profile.id", nestedProfile.id, expectedIdentity.profileId],
    ["manifest.buildIdentity.profile.path", normalizedPath(nestedProfile.path), expectedProfilePath],
    ["manifest.buildIdentity.profile.sha256", nestedProfile.sha256, expectedIdentity.profileSha256],
    ["manifest.buildIdentity.profile.clientDistribution", nestedProfile.clientDistribution,
      expectedIdentity.clientDistribution],
    ["manifest.buildIdentity.profile.protocolVersion", nestedProfile.protocolVersion,
      expectedIdentity.protocolVersion],
    ["manifest.buildIdentity.profile.worldVersion", nestedProfile.worldVersion,
      expectedIdentity.worldVersion],
    ["manifest.buildIdentity.profile.worldgenTelemetryMode", nestedProfile.worldgenTelemetryMode,
      expectedIdentity.worldgenTelemetryMode],
    ["manifest.buildIdentity.profile.storage", nestedProfile.storage, expectedIdentity.storage],
  ];
  for (const [label, actual, expectedValue] of nestedProfileChecks) {
    if (!exactIdentityValue(actual, expectedValue)) {
      failures.push(exactIdentityFailure(label, actual, expectedValue));
    }
  }
  return failures;
}

function profileFrom(contractValue, name) {
  return contractValue?.profiles?.[name] || null;
}

export function validateContractShape(contractValue) {
  const schemaVersion = Number(contractValue?.schemaVersion);
  if (!Number.isSafeInteger(schemaVersion) || schemaVersion <= 0) {
    throw new Error("performance contract schemaVersion must be a positive integer");
  }
  const releaseEvidence = contractValue.releaseEvidence;
  if (!releaseEvidence || typeof releaseEvidence !== "object") {
    throw new Error("performance contract is missing releaseEvidence");
  }
  const uncappedEvidence = contractValue.environment?.uncappedEvidence;
  if (!uncappedEvidence || typeof uncappedEvidence !== "object") {
    throw new Error("performance contract is missing environment.uncappedEvidence");
  }
  const requiredUncappedFields = REQUIRED_UNCAPPED_TELEMETRY_FIELDS;
  if (!Array.isArray(uncappedEvidence.requiredFields)
      || requiredUncappedFields.some((field) => !uncappedEvidence.requiredFields.includes(field))) {
    throw new Error("environment.uncappedEvidence.requiredFields must cover runtime uncapped telemetry");
  }
  if (!Number.isFinite(Number(uncappedEvidence.requiredSwapInterval))
      || Number(uncappedEvidence.requiredSwapInterval) !== 0
      || !Number.isFinite(Number(uncappedEvidence.minimumUncappedYieldCount))
      || Number(uncappedEvidence.minimumUncappedYieldCount) < 1
      || !Number.isFinite(Number(uncappedEvidence.minimumFairYieldCount))
      || Number(uncappedEvidence.minimumFairYieldCount) < 1
      || !Number.isFinite(Number(uncappedEvidence.maximumVsyncYieldCount))
      || Number(uncappedEvidence.maximumVsyncYieldCount) !== 0
      || !Number.isFinite(Number(uncappedEvidence.maximumPresentToRafCount))
      || Number(uncappedEvidence.maximumPresentToRafCount) !== 0
      || UNCAPPED_HEALTH_LIMITS.some(([, limitField]) =>
        !Number.isFinite(Number(uncappedEvidence[limitField]))
        || Number(uncappedEvidence[limitField]) !== 0)) {
    throw new Error("environment.uncappedEvidence has unsafe release requirements");
  }
  const hardTargetProfiles = uniqueStrings(releaseEvidence.hardTargetProfiles);
  const stabilityProfiles = uniqueStrings(releaseEvidence.stabilityProfiles);
  const requiredMemoryProfiles = uniqueStrings(releaseEvidence.requiredMemoryProfiles);
  const matrixProfiles = uniqueStrings(releaseEvidence.uncappedMatrixProfiles);
  const acceptance = releaseEvidence.acceptance;
  if (hardTargetProfiles.length === 0) {
    throw new Error("releaseEvidence.hardTargetProfiles must name at least one hard target");
  }
  if (matrixProfiles.length === 0) {
    throw new Error("releaseEvidence.uncappedMatrixProfiles must name the FPS distance matrix");
  }
  if (!acceptance || typeof acceptance !== "object") {
    throw new Error("releaseEvidence.acceptance is required for release-side measurements");
  }
  for (const [field, minimum] of [
    ["maximumTwoSecondStalls", 0],
    ["maximumFreezeCount", 0],
    ["maximumCrashSignals", 0],
  ]) {
    if (!Number.isFinite(Number(acceptance[field])) || Number(acceptance[field]) !== minimum) {
      throw new Error(`releaseEvidence.acceptance.${field} must be ${minimum}`);
    }
  }
  for (const field of ["messagePortP99MaxMs", "messagePortMaxMs"]) {
    if (!Number.isFinite(Number(acceptance[field])) || Number(acceptance[field]) <= 0) {
      throw new Error(`releaseEvidence.acceptance.${field} must be positive`);
    }
  }
  const acceptanceMemoryProfiles = uniqueStrings(acceptance.memoryRequiredProfiles);
  if (canonicalJson(acceptanceMemoryProfiles) !== canonicalJson(requiredMemoryProfiles)) {
    throw new Error(
      "releaseEvidence.acceptance.memoryRequiredProfiles must match releaseEvidence.requiredMemoryProfiles",
    );
  }
  if (!Array.isArray(acceptance.chunkBacklogPaths)
      || uniqueStrings(acceptance.chunkBacklogPaths).length === 0) {
    throw new Error("releaseEvidence.acceptance.chunkBacklogPaths must name chunk backlog telemetry");
  }
  const allConfiguredProfiles = uniqueStrings([
    ...hardTargetProfiles,
    ...stabilityProfiles,
    ...requiredMemoryProfiles,
    ...matrixProfiles,
  ]);
  for (const name of allConfiguredProfiles) {
    if (!profileFrom(contractValue, name)) {
      throw new Error(`releaseEvidence profile ${JSON.stringify(name)} is undefined`);
    }
  }
  for (const name of hardTargetProfiles) {
    const profile = profileFrom(contractValue, name);
    if (profile.releaseEvidence !== true) {
      throw new Error(`Hard target ${JSON.stringify(name)} is not marked as release evidence`);
    }
    if (profile.driverSupported === false) {
      throw new Error(`Hard target ${JSON.stringify(name)} uses an unsupported driver route`);
    }
  }
  for (const name of matrixProfiles) {
    const profile = profileFrom(contractValue, name);
    if (profile.driverSupported === false) {
      throw new Error(`Uncapped matrix profile ${JSON.stringify(name)} is unsupported`);
    }
    if (profile.renderDistance == null || profile.simulationDistance == null) {
      throw new Error(`Uncapped matrix profile ${JSON.stringify(name)} has no distance pair`);
    }
  }
  const matrixDistances = new Set(matrixProfiles.map((name) => {
    const profile = profileFrom(contractValue, name);
    return `${Number(profile.renderDistance)}/${Number(profile.simulationDistance)}`;
  }));
  for (const requiredDistance of ["6/4", "8/4", "12/4"]) {
    if (!matrixDistances.has(requiredDistance)) {
      throw new Error(`Uncapped matrix is missing the ${requiredDistance} distance pair`);
    }
  }

  const requiredMemorySoakMillis = Number(contractValue.measurement?.soakMs);
  if (requiredMemoryProfiles.length === 0) {
    throw new Error("releaseEvidence.requiredMemoryProfiles must name a memory evidence profile");
  }
  if (requiredMemorySoakMillis !== 30 * 60_000) {
    throw new Error("release memory evidence must retain an explicit 30-minute soak");
  }
  for (const name of requiredMemoryProfiles) {
    const profile = profileFrom(contractValue, name);
    if (profile.releaseEvidence !== true || profile.gates?.memory !== true) {
      throw new Error(
        `Required memory profile ${JSON.stringify(name)} is not marked as release memory evidence`,
      );
    }
    if (profile.driverSupported === false) {
      throw new Error(`Required memory profile ${JSON.stringify(name)} uses an unsupported driver route`);
    }
    const profileSoakMillis = Number(profile.soakMs);
    if (!Number.isFinite(profileSoakMillis) || profileSoakMillis < requiredMemorySoakMillis) {
      throw new Error(
        `Required memory profile ${JSON.stringify(name)} has a soak shorter than 30 minutes`,
      );
    }
  }

  const unsupportedStabilityProfiles = stabilityProfiles.filter(
    (name) => profileFrom(contractValue, name)?.driverSupported === false,
  );
  const supportedStabilityProfiles = stabilityProfiles.filter(
    (name) => !unsupportedStabilityProfiles.includes(name),
  );
  return {
    schemaVersion,
    hardTargetProfiles,
    stabilityProfiles,
    requiredMemoryProfiles,
    matrixProfiles,
    acceptance,
    requiredMemorySoakMillis,
    mandatoryProfiles: uniqueStrings([...hardTargetProfiles, ...requiredMemoryProfiles]),
    supportedStabilityProfiles,
    unsupportedStabilityProfiles,
  };
}

export function createReleasePlan(
  contractValue,
  requestedProfiles = null,
  {smoke = false, matrix = false} = {},
) {
  const contractShape = validateContractShape(contractValue);
  const requested = requestedProfiles == null
    ? null
    : uniqueStrings(Array.isArray(requestedProfiles)
        ? requestedProfiles
        : String(requestedProfiles).split(","));
  const defaultProfiles = uniqueStrings([
    ...contractShape.hardTargetProfiles,
    ...contractShape.requiredMemoryProfiles,
    ...contractShape.supportedStabilityProfiles,
  ]);
  const selectedProfiles = uniqueStrings([
    ...(requested == null ? defaultProfiles : requested),
    ...(matrix ? contractShape.matrixProfiles : []),
  ]);
  const unknownProfiles = selectedProfiles.filter((name) => !profileFrom(contractValue, name));
  if (unknownProfiles.length > 0) {
    throw new Error(`Unknown performance profile(s): ${unknownProfiles.join(", ")}`);
  }
  const unsupportedSelectedProfiles = selectedProfiles.filter(
    (name) => profileFrom(contractValue, name)?.driverSupported === false,
  );
  if (unsupportedSelectedProfiles.length > 0) {
    throw new Error(
      `Unsupported performance profile(s) cannot be release evidence: ${unsupportedSelectedProfiles.join(", ")}`,
    );
  }
  const omittedMandatoryProfiles = contractShape.mandatoryProfiles.filter(
    (name) => !selectedProfiles.includes(name),
  );
  if (!smoke && omittedMandatoryProfiles.length > 0) {
    throw new Error(
      `strict release run omitted mandatory profile(s): ${omittedMandatoryProfiles.join(", ")}`,
    );
  }
  return {
    ...contractShape,
    requestedProfiles: requested,
    selectedProfiles,
    omittedMandatoryProfiles,
    optionalSelectedProfiles: selectedProfiles.filter(
      (name) => !contractShape.mandatoryProfiles.includes(name),
    ),
    matrix,
  };
}

function validateBuildIdentityCandidate(candidate, expectedBuildIdentity, label) {
  if (!candidate || typeof candidate !== "object") {
    return [`${label} is not an object`];
  }
  const failures = [];
  if (candidate.coherent != null && candidate.coherent !== true) {
    failures.push(`${label}.coherent is not true`);
  }
  if (candidate.manifestSha256 != null
      && String(candidate.manifestSha256) !== String(expectedBuildIdentity.manifestSha256)) {
    failures.push(`${label}.manifestSha256 does not match the release manifest`);
  }
  if (candidate.compatibilitySha256 != null
      && String(candidate.compatibilitySha256) !== String(expectedBuildIdentity.compatibilitySha256)) {
    failures.push(`${label}.compatibilitySha256 does not match the release build`);
  }
  if (Array.isArray(candidate.artifactCompatibilities)) {
    const expected = expectedBuildIdentity.artifactCompatibilities || [];
    if (candidate.artifactCompatibilities.length !== expected.length
        || candidate.artifactCompatibilities.some(
          (value, index) => String(value) !== String(expected[index]),
        )) {
      failures.push(`${label}.artifactCompatibilities do not match the release build`);
    }
  }
  if (candidate.compatibilitySha256 == null
      && candidate.manifestSha256 == null
      && !Array.isArray(candidate.artifactCompatibilities)) {
    failures.push(`${label} has no verifiable build identity fields`);
  }
  return failures;
}

export function validateChildReport(report, {
  profileName,
  profile,
  contractSchemaVersion,
  contractValue = contract,
  smoke = false,
  expectedBuildIdentity = null,
  uncappedEvidence = null,
} = {}) {
  const failures = [];
  if (!report || typeof report !== "object") {
    return {valid: false, failures: ["child report is not an object"], buildIdentityStatus: "missing"};
  }
  const configuration = report.configuration;
  const analysis = report.analysis;
  const expectedReleaseEvidence = !smoke && profile?.releaseEvidence === true;
  const expectedMode = smoke
    ? "smoke-non-gating"
    : (expectedReleaseEvidence ? "release-gating" : "diagnostic-stress");
  const expectedVerdict = smoke ? "non-gating" : "pass";
  const releaseEvidenceRequired = !smoke && profile?.releaseEvidence === true;
  const reportInconclusive = !smoke
    && (String(report.verdict || "") === "inconclusive"
      || String(analysis?.verdict || "") === "inconclusive");

  if (Number(report.schemaVersion) !== Number(contractSchemaVersion)) {
    failures.push("child report schemaVersion does not match the active contract");
  }
  if (String(report.verdict || "") !== expectedVerdict && !reportInconclusive) {
    failures.push(`child report verdict must be ${JSON.stringify(expectedVerdict)}`);
  }
  if (!configuration || typeof configuration !== "object") {
    failures.push("child report is missing configuration");
  } else {
    if (String(configuration.profileName || "") !== String(profileName)) {
      failures.push("child report configuration.profileName does not match the selected profile");
    }
    if (String(configuration.worldgenTelemetryMode || "")
        !== activeWorldgenTelemetryMode) {
      failures.push(
        "child report configuration.worldgenTelemetryMode does not match the active version profile",
      );
    }
    if (Number(configuration.contractSchemaVersion) !== Number(contractSchemaVersion)) {
      failures.push("child report configuration.contractSchemaVersion does not match the active contract");
    }
    if (canonicalJson(configuration.profile) !== canonicalJson(profile)) {
      failures.push("child report configuration.profile does not match the active contract profile");
    }
    if (Boolean(configuration.gating) !== expectedReleaseEvidence) {
      failures.push("child report configuration.gating is inconsistent with suite mode");
    }
    if (Boolean(configuration.releaseEvidence) !== expectedReleaseEvidence) {
      failures.push("child report configuration.releaseEvidence is inconsistent with suite mode");
    }
    if (Boolean(configuration.strictChecks) !== !smoke) {
      failures.push("child report configuration.strictChecks is inconsistent with suite mode");
    }
  }
  if (report.profileName != null && String(report.profileName) !== String(profileName)) {
    failures.push("child report profileName does not match the selected profile");
  }
  if (!analysis || typeof analysis !== "object") {
    failures.push("child report is missing analysis");
  } else {
    if (String(analysis.verdict || "") !== expectedVerdict && !reportInconclusive) {
      failures.push(`child report analysis.verdict must be ${JSON.stringify(expectedVerdict)}`);
    }
    if (analysis.passed !== true || report.passed !== true) {
      failures.push("child report is not marked passed");
    }
    if (String(analysis.mode || "") !== expectedMode) {
      failures.push(`child report analysis.mode must be ${JSON.stringify(expectedMode)}`);
    }
    if (Boolean(analysis.gating) !== expectedReleaseEvidence) {
      failures.push("child report analysis.gating is inconsistent with suite mode");
    }
    if (Boolean(analysis.releaseEvidence) !== expectedReleaseEvidence) {
      failures.push("child report analysis.releaseEvidence is inconsistent with suite mode");
    }
    if (String(analysis.evidenceRole || "") !== String(profile?.evidenceRole || "")) {
      failures.push("child report analysis.evidenceRole does not match the selected profile");
    }
    if (analysis.environment?.profileName != null
        && String(analysis.environment.profileName) !== String(profileName)) {
      failures.push("child report environment.profileName does not match the selected profile");
    }
    const reportedWorldgenTelemetryMode = analysis.runtimeInvariants?.worldgenTelemetryMode
      || analysis.worldgenTelemetryMode;
    if (reportedWorldgenTelemetryMode != null
        && String(reportedWorldgenTelemetryMode) !== activeWorldgenTelemetryMode) {
      failures.push(
        "child report analysis worldgen telemetry mode does not match the active version profile",
      );
    }
  }
  if (releaseEvidenceRequired) {
    const evidence = analysis?.performanceEvidence;
    if (!evidence || typeof evidence !== "object") {
      failures.push("release child report is missing analysis.performanceEvidence");
    } else {
      if (evidence.framePacing?.verdict !== "pass") {
        failures.push("release child report did not prove uncapped frame pacing was exercised");
      }
      const requirements = uncappedEvidence || {
        requiredSwapInterval: 0,
        minimumUncappedYieldCount: 1,
        minimumFairYieldCount: 1,
        maximumVsyncYieldCount: 0,
        maximumPresentToRafCount: 0,
        maximumMessageChannelCreateFailureCount: 0,
        maximumMessageChannelPostFailureCount: 0,
        maximumMessageChannelRebuildCount: 0,
        maximumCancelledMessageTaskCount: 0,
        maximumWatchdogYieldCount: 0,
        requiredFields: REQUIRED_UNCAPPED_TELEMETRY_FIELDS,
      };
      const observed = evidence.framePacing.observed;
      const requiredFields = Array.isArray(requirements.requiredFields)
        ? requirements.requiredFields : REQUIRED_UNCAPPED_TELEMETRY_FIELDS;
      const missingFields = requiredFields.filter((field) => {
        const observedFields = field === "swapInterval"
          ? ["swapIntervalMin", "swapIntervalMax"] : [`${field}Max`];
        return observedFields.some((observedField) => !hasFiniteNumber(observed?.[observedField]));
      });
      if (missingFields.length > 0) {
        failures.push(`release child report frame-pacing telemetry is missing: ${missingFields.join(", ")}`);
      }
      if (hasFiniteNumber(observed?.swapIntervalMin)
          && hasFiniteNumber(observed?.swapIntervalMax)
          && (Number(observed.swapIntervalMin) !== Number(requirements.requiredSwapInterval)
            || Number(observed.swapIntervalMax) !== Number(requirements.requiredSwapInterval))) {
        failures.push("release child report did not prove swapInterval=0 during measurement");
      }
      if (hasFiniteNumber(observed?.uncappedYieldCountMax)
          && Number(observed.uncappedYieldCountMax) < Number(requirements.minimumUncappedYieldCount)) {
        failures.push("release child report did not prove uncappedYieldCount>0");
      }
      if (hasFiniteNumber(observed?.fairYieldCountMax)
          && Number(observed.fairYieldCountMax) < Number(requirements.minimumFairYieldCount)) {
        failures.push("release child report did not prove fairYieldCount>0");
      }
      if ((hasFiniteNumber(observed?.vsyncYieldCountMax)
            && Number(observed.vsyncYieldCountMax) > Number(requirements.maximumVsyncYieldCount))
          || (hasFiniteNumber(observed?.presentToRafCountMax)
            && Number(observed.presentToRafCountMax) > Number(requirements.maximumPresentToRafCount))) {
        failures.push("release child report recorded rAF/VSync yields during measurement");
      }
      for (const [field, limitField] of UNCAPPED_HEALTH_LIMITS) {
        const observedValue = observed?.[`${field}Max`];
        if (hasFiniteNumber(observedValue)
            && Number(observedValue) > Number(requirements[limitField])) {
          failures.push(`release child report recorded ${field}=${observedValue}`);
        }
      }
    }
  }
  if (!smoke) failures.push(...validateNaturalWorkerDistanceEvidence(report, profile));
  if (report.gating != null && Boolean(report.gating) !== expectedReleaseEvidence) {
    failures.push("child report gating is inconsistent with suite mode");
  }
  if (report.releaseEvidence != null && Boolean(report.releaseEvidence) !== expectedReleaseEvidence) {
    failures.push("child report releaseEvidence is inconsistent with suite mode");
  }
  if (reportInconclusive) {
    failures.push("child report is inconclusive; release evidence is unverified");
  }

  const acceptanceEvidence = !smoke
    ? summarizeAcceptanceEvidence({
        report,
        profileName,
        profile,
        contract: contractValue,
      })
    : null;
  if (acceptanceEvidence && acceptanceEvidence.verdict !== "pass") {
    if (acceptanceEvidence.failures.length > 0) {
      failures.push(...acceptanceEvidence.failures.map(
        (failure) => `acceptance evidence: ${failure}`,
      ));
    }
    if (acceptanceEvidence.missing.length > 0) {
      failures.push(
        `acceptance evidence is unverified; missing: ${acceptanceEvidence.missing.join(", ")}`,
      );
    }
  }

  const identityCandidates = [
    ["report.buildIdentity", report.buildIdentity],
    ["report.configuration.buildIdentity", configuration?.buildIdentity],
    ["report.analysis.buildIdentity", analysis?.buildIdentity],
  ].filter(([, candidate]) => candidate != null);
  let buildIdentityStatus = "manifest-guarded";
  if (identityCandidates.length > 0) {
    buildIdentityStatus = "reported-and-verified";
    if (!expectedBuildIdentity) {
      failures.push("child report supplied build identity but the suite has no expected identity");
    } else {
      for (const [label, candidate] of identityCandidates) {
        failures.push(...validateBuildIdentityCandidate(candidate, expectedBuildIdentity, label));
      }
    }
  }
  if (releaseEvidenceRequired) {
    if (!report.buildIdentity || typeof report.buildIdentity !== "object") {
      failures.push("release child report is missing buildIdentity");
    } else {
      if (report.buildIdentity.coherent !== true) {
        failures.push("release child report buildIdentity.coherent is not true");
      }
      for (const field of ["manifestSha256", "compatibilitySha256"]) {
        if (!/^[a-f0-9]{64}$/i.test(String(report.buildIdentity[field] || ""))) {
          failures.push(`release child report buildIdentity.${field} is missing or invalid`);
        }
      }
      if (!Array.isArray(report.buildIdentity.artifactCompatibilities)
          || report.buildIdentity.artifactCompatibilities.length !== 4) {
        failures.push("release child report buildIdentity.artifactCompatibilities is incomplete");
      }
    }
  }
  return {
    valid: failures.length === 0,
    failures,
    buildIdentityStatus,
    profileName: configuration?.profileName || report.profileName || null,
    schemaVersion: report.schemaVersion ?? null,
    gating: analysis?.gating ?? configuration?.gating ?? null,
    releaseEvidence: analysis?.releaseEvidence ?? configuration?.releaseEvidence ?? null,
    performanceEvidence: analysis?.performanceEvidence || null,
    acceptanceEvidence,
    inconclusive: reportInconclusive,
  };
}

function value(args, name, fallback = "") {
  const index = args.indexOf(name);
  return index >= 0 && index + 1 < args.length ? args[index + 1] : fallback;
}

export async function readBuildIdentity({manifestPath: manifestPathOverride = manifestPath} = {}) {
  const identityManifestPath = resolve(manifestPathOverride);
  const bytes = await readFile(identityManifestPath);
  const manifest = JSON.parse(bytes.toString("utf8"));
  const nestedProfile = manifest?.buildIdentity?.profile;
  const profileFailures = validateManifestIdentity(manifest);
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
  const coherent = /^[a-f0-9]{64}$/i.test(compatibilitySha256)
    && artifactCompatibilities.length === 4
    && artifactCompatibilities.every((entry) => entry === compatibilitySha256);
  return {
    manifestPath: identityManifestPath,
    manifestSha256: createHash("sha256").update(bytes).digest("hex"),
    compatibilitySha256,
    artifactCompatibilities,
    coherent,
    profile: manifest.profile || null,
    profilePath: manifest.profilePath || null,
    nestedProfile: nestedProfile || null,
    worldVersion: manifest.worldVersion,
    worldgenTelemetryMode: manifest.worldgenTelemetryMode,
    storage: manifest.storage || null,
    buildRoot: configuredBuildRoot,
    overlayDirectory: configuredOverlayDirectory,
  };
}

function timeoutFor(profile, smoke) {
  if (smoke) return 20 * 60_000;
  const measurement = contract.measurement || {};
  return Number(contract.startup?.timeoutMs || 900_000)
    + Number(profile.warmupMs || measurement.warmupMs || 30_000)
    + Math.max(
      Number(profile.fpsWindowMs || profile.durationMs || measurement.sampleMs || 300_000),
      Number(profile.gates?.memory ? profile.soakMs || measurement.soakMs || 0 : 0),
    )
    + Number(measurement.cleanupMs || 30_000)
    + Number(measurement.watchdogGraceMs || 300_000)
    + 120_000;
}

async function runProfile(name, profile, {smoke, beforeIdentity, forwarded, outputDirectory}) {
  const output = resolve(outputDirectory, `${name}.json`);
  const commandArgs = [benchmarkPath, "--profile", name, "--output", output, ...forwarded];
  if (smoke) commandArgs.push("--smoke");
  const startedAt = Date.now();
  const execution = await new Promise((resolveExecution) => {
    const child = spawn(process.execPath, commandArgs, {
      cwd: repositoryRoot,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let tail = "";
    let settled = false;
    let timedOut = false;
    const append = (chunk) => {
      tail = (tail + String(chunk)).slice(-32_000);
    };
    child.stdout.on("data", append);
    child.stderr.on("data", append);
    const timeout = setTimeout(() => {
      timedOut = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 2000).unref();
    }, timeoutFor(profile, smoke));
    const finish = (exitCode, signal, error = null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolveExecution({exitCode, signal, error, tail, timedOut});
    };
    child.on("error", (error) => finish(null, null, String(error?.message || error)));
    child.on("close", (exitCode, signal) => finish(exitCode, signal));
  });
  let report = null;
  let reportError = null;
  try {
    report = JSON.parse(await readFile(output, "utf8"));
  } catch (error) {
    reportError = String(error?.message || error);
  }
  const validation = validateChildReport(report, {
    profileName: name,
    profile,
    contractSchemaVersion: contract.schemaVersion,
    contractValue: contract,
    smoke,
    expectedBuildIdentity: beforeIdentity,
    uncappedEvidence: contract.environment?.uncappedEvidence,
  });
  if (execution.exitCode !== 0) {
    validation.valid = false;
    validation.failures.push(
      `child benchmark exited with ${execution.signal || `code ${execution.exitCode}`}`,
    );
  }
  if (reportError) {
    validation.valid = false;
    validation.failures.push(`child report could not be read: ${reportError}`);
  }
  const expectedVerdict = smoke ? "non-gating" : "pass";
  const acceptanceEvidence = validation.acceptanceEvidence
    || (report ? summarizeAcceptanceEvidence({
      report,
      profileName: name,
      profile,
      contract,
    }) : null);
  const childVerdict = validation.valid
    ? expectedVerdict
    : ((acceptanceEvidence?.verdict === "inconclusive" || validation.inconclusive === true)
      && execution.exitCode === 0
      && validation.failures.every((failure) => failure.startsWith("acceptance evidence")
        || failure.startsWith("child report is inconclusive"))
      ? "inconclusive" : "fail");
  return {
    profile: name,
    evidenceRole: profile.evidenceRole || null,
    verdict: childVerdict,
    durationMs: Date.now() - startedAt,
    output,
    exitCode: execution.exitCode,
    signal: execution.signal,
    timedOut: execution.timedOut,
    error: execution.error || reportError || null,
    outputTail: execution.tail,
    reportVerdict: report?.analysis?.verdict || report?.verdict || null,
    reportProfile: report?.configuration?.profileName || report?.profileName || null,
    reportSchemaVersion: report?.schemaVersion ?? null,
    worldgenTelemetryMode: report?.configuration?.worldgenTelemetryMode
      || report?.analysis?.runtimeInvariants?.worldgenTelemetryMode || null,
    buildIdentity: report?.buildIdentity || null,
    performanceEvidence: report?.analysis?.performanceEvidence
      || report?.performanceEvidence || null,
    failureEvidence: report?.analysis?.failureEvidence
      || report?.failureEvidence || null,
    workerDistanceMode: report?.analysis?.workerDistanceMode
      || report?.configuration?.workerDistanceMode || null,
    naturalWorkerEvidence: report?.analysis?.naturalWorkerEvidence
      || report?.naturalWorkerEvidence || null,
    harnessOverrideEvidence: report?.analysis?.harnessOverrideEvidence
      || report?.harnessOverrideEvidence || null,
    acceptanceEvidence,
    gating: report?.analysis?.gating ?? report?.configuration?.gating
      ?? (smoke ? false : profile.releaseEvidence === true),
    releaseEvidence: report?.analysis?.releaseEvidence ?? report?.configuration?.releaseEvidence
      ?? (smoke ? false : profile.releaseEvidence === true),
    memoryVerdict: report?.analysis?.memory?.verdict || null,
    passed: validation.valid,
    validation,
  };
}

async function main() {
  const args = process.argv.slice(2);
  const smoke = args.includes("--smoke");
  const matrix = args.includes("--matrix");
  if (args.includes("--allow-attached-input")) {
    throw new Error(
      "--allow-attached-input has been removed; release benchmarks never inject input into attached Chrome",
    );
  }
  if (args.includes("--pin-worker-distance")) {
    throw new Error(
      "--pin-worker-distance is diagnostic-only and cannot be forwarded by the release suite",
    );
  }
  if (args.includes("--attach-port") || value(args, "--attach-port")) {
    throw new Error(
      "--attach-port is disabled: release benchmarks require an isolated Chrome they launch themselves",
    );
  }
  if (!smoke && value(args, "--url")) {
    throw new Error(
      "strict release evidence must use the suite-owned local build server; --url is smoke-only",
    );
  }
  const requestedProfiles = args.includes("--profiles") ? value(args, "--profiles") : null;
  const plan = createReleasePlan(contract, requestedProfiles, {smoke, matrix});
  const outputDirectory = resolveRepositoryPortPath(value(
    args,
    "--output-directory",
    isolated ? `port/target/${profileId}/chrome-performance-release-suite` : "port/target/chrome-performance-release-suite",
  ));
  const reportPath = resolveRepositoryPortPath(value(
    args,
    "--output",
    resolve(outputDirectory, "release-suite.json"),
  ));
  const forwarded = [];
  for (const name of ["--chrome", "--url", "--player"]) {
    const selected = value(args, name);
    if (selected) forwarded.push(name, name === "--chrome" ? nativePath(selected) : selected);
  }
  for (const flag of ["--headless", "--keep-chrome"]) {
    if (args.includes(flag)) forwarded.push(flag);
  }

  const initialIdentity = await readBuildIdentity();
  if (!initialIdentity.coherent) {
    throw new Error("Gaius.manifest.json does not describe one coherent client/Worker/Wasm build");
  }
  const configuration = plan.selectedProfiles.map((name) => {
    const profile = profileFrom(contract, name);
    return {
      name,
      required: plan.mandatoryProfiles.includes(name),
      driverSupported: profile.driverSupported !== false,
      evidenceRole: profile.evidenceRole || null,
      memoryRequired: profile.gates?.memory === true,
      releaseEvidence: smoke ? false : profile.releaseEvidence === true,
      soakMs: Number(profile.soakMs || 0),
      timeoutMs: timeoutFor(profile, smoke),
    };
  });
  if (args.includes("--print-config")) {
    console.log(JSON.stringify({
      schemaVersion: contract.schemaVersion,
      contractSchemaVersion: contract.schemaVersion,
      mode: smoke ? "smoke-suite" : "strict-release",
      smoke,
      matrix,
      gating: false,
      releaseEvidence: false,
      profiles: configuration,
      releasePlan: plan,
      acceptance: plan.acceptance,
      requiredMemoryProfiles: plan.requiredMemoryProfiles,
      matrixProfiles: plan.matrixProfiles,
      requiredMemorySoakMillis: plan.requiredMemorySoakMillis,
      uncappedEvidence: contract.environment?.uncappedEvidence,
      unsupportedProfiles: plan.unsupportedStabilityProfiles.map((name) => ({
        profile: name,
        releaseEvidence: false,
        reason: "configured stability profile has no supported Chrome driver route",
      })),
      buildIdentity: initialIdentity,
      activeProfile: {
        id: profileId,
        path: profilePath,
        protocolVersion: activeVersionProfile.protocolVersion,
        worldVersion: activeVersionProfile.worldVersion,
        worldgenTelemetryMode: activeWorldgenTelemetryMode,
      },
      runtimeInvariantContract: runtimeInvariantContractForProfile,
      distRoot,
      overlayDirectory: configuredOverlayDirectory,
      buildRoot: configuredBuildRoot,
      outputDirectory,
      reportPath,
    }, null, 2));
    return;
  }

  await mkdir(outputDirectory, {recursive: true});
  const results = [];
  for (const item of configuration) {
    const before = await readBuildIdentity();
    if (before.manifestSha256 !== initialIdentity.manifestSha256
        || before.compatibilitySha256 !== initialIdentity.compatibilitySha256) {
      results.push({
        profile: item.name,
        evidenceRole: item.evidenceRole,
        verdict: "fail",
        gating: item.releaseEvidence,
        releaseEvidence: item.releaseEvidence,
        reason: "the release build identity changed before this profile ran",
      });
      break;
    }
    const result = await runProfile(item.name, profileFrom(contract, item.name), {
      smoke,
      beforeIdentity: before,
      forwarded,
      outputDirectory,
    });
    const after = await readBuildIdentity();
    if (after.manifestSha256 !== initialIdentity.manifestSha256
        || after.compatibilitySha256 !== initialIdentity.compatibilitySha256) {
      result.verdict = "fail";
      result.passed = false;
      result.validation.valid = false;
      result.validation.failures.push("the release build identity changed while this profile ran");
    }
    results.push(result);
  }

  const requiredMemoryEvidence = plan.requiredMemoryProfiles.map((profileName) => {
    const result = results.find((entry) => entry.profile === profileName);
    const valid = !smoke
      && result?.verdict === "pass"
      && result?.memoryVerdict === "pass"
      && result?.releaseEvidence === true;
    return {
      profile: profileName,
      present: Boolean(result),
      verdict: result?.verdict || null,
      memoryVerdict: result?.memoryVerdict || null,
      releaseEvidence: result?.releaseEvidence === true,
      valid,
    };
  });
  const requiredMemoryEvidenceVerdict = smoke
    ? "not-required"
    : (requiredMemoryEvidence.some((entry) => entry.verdict === "fail" || entry.memoryVerdict === "fail")
        ? "fail"
        : (requiredMemoryEvidence.every((entry) => entry.valid) ? "pass" : "inconclusive"));
  const acceptanceSummary = results.map((entry) => {
    const measured = entry.acceptanceEvidence?.measured || {};
    const frame = measured.frame || {};
    const freezes = measured.freezes || {};
    const messagePort = measured.messagePort || {};
    const profile = profileFrom(contract, entry.profile) || {};
    return {
      profile: entry.profile,
      renderDistance: profile.renderDistance ?? null,
      simulationDistance: profile.simulationDistance ?? null,
      verdict: entry.verdict,
      averageFps: frame.averageFps ?? null,
      onePercentLowFps: frame.onePercentLowFps ?? null,
      longestFrameMs: frame.longestFrameMs ?? null,
      twoSecondStallCount: frame.longFramesAtLeast2s ?? null,
      freezeCount: freezes.freezeCount ?? null,
      memory: measured.memory || null,
      chunkBacklog: measured.chunkBacklog || null,
      messagePort: {
        available: messagePort.available === true,
        sampleCount: messagePort.sampleCount ?? null,
        p99RttMillis: messagePort.p99RttMillis ?? null,
        maxRttMillis: messagePort.maxRttMillis ?? null,
      },
      missing: entry.acceptanceEvidence?.missing || [],
      failures: entry.acceptanceEvidence?.failures || [],
    };
  });
  const selectedResultsValid = results.length === configuration.length
    && results.every((entry) => smoke
      ? entry.verdict === "non-gating" && entry.releaseEvidence === false && entry.gating === false
      : entry.verdict === "pass");
  const strictPass = selectedResultsValid && requiredMemoryEvidenceVerdict === "pass";
  const verdict = smoke
    ? (selectedResultsValid ? "smoke-pass" : "smoke-fail")
      : (results.some((entry) => entry.verdict === "fail")
        || requiredMemoryEvidenceVerdict === "fail"
        ? "fail"
        : (strictPass ? "pass" : "inconclusive"));
  const suiteReleaseEvidence = !smoke && verdict === "pass";
  const report = {
    schemaVersion: contract.schemaVersion,
    contractSchemaVersion: contract.schemaVersion,
    generatedAt: new Date().toISOString(),
    mode: smoke ? "smoke-suite" : "strict-release",
    smoke,
    matrix,
    gating: suiteReleaseEvidence,
    releaseEvidence: suiteReleaseEvidence,
    verdict,
    passed: smoke ? verdict === "smoke-pass" : verdict === "pass",
    buildIdentity: initialIdentity,
    releasePlan: plan,
    requiredProfiles: configuration.filter((entry) => entry.required),
    selectedProfiles: configuration,
    releaseEvidenceProfiles: suiteReleaseEvidence
      ? results.filter((entry) => entry.releaseEvidence === true).map((entry) => entry.profile)
      : [],
    unsupportedProfiles: plan.unsupportedStabilityProfiles.map((name) => ({
      profile: name,
      releaseEvidence: false,
      reason: "configured stability profile has no supported Chrome driver route",
    })),
    requiredMemoryProfiles: plan.requiredMemoryProfiles,
    requiredMemorySoakMillis: plan.requiredMemorySoakMillis,
    matrixProfiles: plan.matrixProfiles,
    acceptance: plan.acceptance,
    requiredMemoryEvidence,
    requiredMemoryEvidenceVerdict,
    acceptanceSummary,
    results,
  };
  await mkdir(dirname(reportPath), {recursive: true});
  await writeFile(reportPath, JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify({verdict, reportPath, acceptanceSummary, results}, null, 2));
  process.exitCode = report.passed ? 0 : 1;
}

if (isMain) await main();
