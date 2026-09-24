import { readFileSync } from 'node:fs';

// Shared by the real-browser runner and its independent evidence validator.
export const expectedResourcePack = Object.freeze(process.env.RESOURCE_PACK_EXPECTATION_FILE
  ? JSON.parse(readFileSync(process.env.RESOURCE_PACK_EXPECTATION_FILE, 'utf8')) : {
    originalUrl: 'https://jihulab.com/-/project/356228/uploads/076ac7018675285fa0f103e4a5ade52a/resource_pack.zip',
    fixedMirrorUrl: null,
    bytes: 62_503_372,
    sha1: '964540e45751bc24d51453dba500a485be3cff68',
    sha256: 'cd5a0aa7b2a333b3b921673042ccf17c7c73727e628e19e46e0199c31bcab25e',
  });

if (!Number.isSafeInteger(expectedResourcePack.bytes) || expectedResourcePack.bytes <= 0
    || !/^[0-9a-f]{40}$/.test(expectedResourcePack.sha1)
    || !/^[0-9a-f]{64}$/.test(expectedResourcePack.sha256)
    || !['http:', 'https:'].includes(new URL(expectedResourcePack.originalUrl).protocol)) {
  throw new Error('Invalid resource pack expectation: require URL, byte length, SHA1 and SHA256');
}
