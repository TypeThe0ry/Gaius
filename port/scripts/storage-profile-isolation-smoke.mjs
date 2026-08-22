#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const persistencePath = resolve(
  repositoryRoot,
  "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java",
);
const workerPath = resolve(
  repositoryRoot,
  "port/web/singleplayer/server-worker-bootstrap.js",
);
const integratedServerPath = resolve(
  repositoryRoot,
  "port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java",
);
const profileIds = ["1.21.11", "26.2"];
const profilePaths = profileIds.map(id => resolve(
  repositoryRoot,
  "port/versions/" + id + ".json",
));
const [persistence, worker, integratedServer, ...profileSources] = await Promise.all([
  readFile(persistencePath, "utf8"),
  readFile(workerPath, "utf8"),
  readFile(integratedServerPath, "utf8"),
  ...profilePaths.map(path => readFile(path, "utf8")),
]);

assert.match(persistence, /private static int currentDataVersion\(\)/);
assert.match(persistence, /private static String storagePrefix\(\)/);
assert.match(persistence, /runtimeWorldVersion\(\)/);
assert.match(persistence, /runtimeStoragePrefix\(\)/);
assert.match(persistence, /runtimeStorageConfigurationSignature\(\)/);
assert.match(persistence, /gaius-fs-v2-1\.21\.11/);
assert.match(persistence, /gaius-fs-v2-26\.2/);
assert.match(persistence, /LEGACY_DATA_VERSION = 4671/);
assert.match(
  persistence,
  /private static int currentDataVersion\(\)[\s\S]*?if \(value <= 0\) \{[\s\S]*?throw new IllegalStateException/,
);
assert.match(
  persistence,
  /private static String storagePrefix\(\)[\s\S]*?value == null[\s\S]*?gaius\.fs\.v1:[\s\S]*?throw new IllegalStateException/,
);
assert.match(
  persistence,
  /public static void mount\(\)[\s\S]*?String prefix = storagePrefix\(\);[\s\S]*?currentDataVersion\(\);[\s\S]*?mounted = true;/,
);
assert.doesNotMatch(persistence, /PREFIX\s*=\s*["']gaius\.fs\.v1:/);
assert.doesNotMatch(persistence, /(?:getItem|removeItem|setItem)\(\s*["']gaius\.fs\.v1:/);
assert.doesNotMatch(
  persistence,
  /(?:migrat|import)[\s\S]{0,160}gaius\.fs\.v1:/i,
);

assert.match(worker, /configureStorage\(message\)/);
assert.match(worker, /const storageProfiles = Object\.freeze\(/);
assert.match(worker, /storage configuration does not match profile/);
assert.match(worker, /worldVersion: 4671/);
assert.match(worker, /worldVersion: 4903/);
assert.match(worker, /gaius-fs-v2-1\.21\.11/);
assert.match(worker, /gaius-fs-v2-26\.2/);
assert.match(worker, /indexedDB\.open\(databaseName, schema\)/);
assert.match(worker, /__gaiusStorageDatabaseName/);
assert.match(worker, /__gaiusStorageOpfsDirectory/);
assert.doesNotMatch(worker, /indexedDB\.open\(\s*["']gaius-fs-v1/);
assert.doesNotMatch(worker, /getDirectoryHandle\(\s*["']regions["']/);
assert.doesNotMatch(worker, /deleteDatabase\s*\(/);
assert.doesNotMatch(worker, /(?:migrat|import)[\s\S]{0,160}(?:gaius-fs-v1|gaius\.fs\.v1:)/i);

assert.match(integratedServer, /__gaiusStorageDatabaseName/);
assert.match(integratedServer, /storageMatchesProfile/);
assert.match(integratedServer, /gaius-fs-v2-1\.21\.11/);
assert.match(integratedServer, /gaius-fs-v2-26\.2/);
assert.match(integratedServer, /indexedDB\.open\(storageDatabaseName, storageSchema\)/);
assert.doesNotMatch(integratedServer, /indexedDB\.open\(\s*["']gaius-fs-v1/);
assert.doesNotMatch(integratedServer, /deleteDatabase\s*\(/);

const clientPath = resolve(
  repositoryRoot,
  "port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java",
);
const client = await readFile(clientPath, "utf8");
assert.match(client, /storageConfigurationValid\(\)/);
assert.match(client, /storageMatchesProfile/);
assert.match(client, /gaius-fs-v2-1\.21\.11/);
assert.match(client, /gaius-fs-v2-26\.2/);

const expectedWorldVersions = new Map([
  ["1.21.11", 4671],
  ["26.2", 4903],
]);

const profiles = profileSources.map((source, index) => {
  const profile = JSON.parse(source);
  const id = profileIds[index];
  const expectedWorldVersion = expectedWorldVersions.get(id);
  const expectedStorage = {
    schema: 2,
    databaseName: "gaius-fs-v2-" + id,
    prefix: "gaius.fs.v2:" + id + ":",
    opfsDirectory: "regions-v2-" + id,
  };
  assert.equal(profile.id, id);
  assert.equal(profile.worldVersion, expectedWorldVersion);
  assert.deepEqual(profile.storage, expectedStorage);
  assert.equal(profile.storage.schema, 2);
  assert.match(profile.storage.databaseName, /^gaius-fs-v2-/);
  assert.match(profile.storage.prefix, /^gaius\.fs\.v2:/);
  assert.match(profile.storage.opfsDirectory, /^regions-v2-/);
  return {
    id,
    worldVersion: profile.worldVersion,
    storageSchema: profile.storage.schema,
    databaseName: profile.storage.databaseName,
    prefix: profile.storage.prefix,
    opfsDirectory: profile.storage.opfsDirectory,
  };
});

function storageIdentity(profile, worldId) {
  return {
    database: profile.databaseName,
    key: profile.prefix + "/gaius/saves/" + worldId + "/level.dat",
    opfs: "gaius/" + profile.opfsDirectory + "/" + worldId + ".regions",
  };
}

const identities = profiles.map(profile => storageIdentity(profile, "smoke-world"));
assert.equal(new Set(identities.map(identity => identity.database)).size, profiles.length);
assert.equal(new Set(identities.map(identity => identity.key.split("/gaius/")[0])).size, profiles.length);
assert.equal(new Set(identities.map(identity => identity.opfs)).size, profiles.length);
assert.ok(identities.every(identity => identity.opfs.startsWith("gaius/")));
assert.equal(new Set(profiles.map(profile => profile.prefix)).size, profiles.length);
for (const [index, identity] of identities.entries()) {
  const profile = profiles[index];
  assert.ok(identity.key.startsWith(profile.prefix + "/gaius/"));
  assert.ok(identity.opfs.startsWith("gaius/" + profile.opfsDirectory + "/"));
}

// A tiny in-memory persistence model makes the isolation assertion independent
// of a browser and proves that a write in one profile cannot be read by the other.
const databases = new Map();
for (const [index, profile] of profiles.entries()) {
  const identity = identities[index];
  const database = databases.get(identity.database) || new Map();
  database.set(identity.key, profile.id);
  databases.set(identity.database, database);
}
for (const [index, profile] of profiles.entries()) {
  const identity = identities[index];
  assert.equal(databases.get(identity.database).get(identity.key), profile.id);
  for (const other of profiles) {
    if (other === profile) continue;
    assert.equal(databases.get(identity.database).get(
      other.prefix + "/gaius/saves/smoke-world/level.dat",
    ), undefined);
  }
}

console.log(JSON.stringify({
  ok: true,
  profiles: profiles.map((profile, index) => ({
    id: profile.id,
    worldVersion: profile.worldVersion,
    databaseName: identities[index].database,
    prefix: profile.prefix,
    opfsPath: identities[index].opfs,
  })),
  checks: [
    "dynamic Java storage version and prefix",
    "dynamic Worker IndexedDB and profile OPFS directory",
    "dynamic integrated-server fallback IndexedDB",
    "cross-profile in-memory key isolation",
    "no unconditional legacy open/delete path",
  ],
}, null, 2));
