#!/usr/bin/env node

import {execFileSync} from "node:child_process";
import {readFileSync} from "node:fs";

const tracked = execFileSync("git", ["ls-files", "-z"], {encoding: "utf8"})
  .split("\0").filter(Boolean);
const ipv4 = /\b(?:\d{1,3}\.){3}\d{1,3}\b/g;
const allowed = ip => {
  const octets = ip.split(".").map(Number);
  if (octets.some(value => value > 255)) return true;
  const [a, b] = octets;
  return a === 0 || a === 10 || a === 127 || a >= 224
    || (a === 100 && b >= 64 && b <= 127)
    || (a === 169 && b === 254)
    || (a === 172 && b >= 16 && b <= 31)
    || (a === 192 && (b === 0 || b === 168))
    || (a === 198 && (b === 18 || b === 19 || b === 51))
    || (a === 203 && b === 0);
};

const findings = [];
for (const path of tracked) {
  let text;
  try {
    const bytes = readFileSync(path);
    if (bytes.includes(0)) continue;
    text = bytes.toString("utf8");
  } catch {
    continue;
  }
  const lines = text.split(/\r?\n/u);
  lines.forEach((line, index) => {
    for (const ip of line.match(ipv4) || []) {
      if (!allowed(ip)) findings.push(`${path}:${index + 1}: ${ip}`);
    }
  });
}
if (findings.length) {
  console.error("Public IPv4 literals found in tracked files:");
  console.error(findings.join("\n"));
  process.exit(1);
}
console.log(`public network secret scan passed (${tracked.length} tracked files)`);
