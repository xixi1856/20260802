import { cpSync, mkdirSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";

const projectRoot = resolve(import.meta.dirname, "..");
const outputRoot = resolve(projectRoot, "target", "contract-docs", "site");
const npx = process.platform === "win32" ? "npx.cmd" : "npx";

function run(args) {
  const result = spawnSync(npx, args, {
    cwd: projectRoot,
    stdio: "inherit",
    shell: process.platform === "win32"
  });
  if (result.status !== 0) {
    if (result.error) {
      console.error(result.error.message);
    }
    process.exit(result.status ?? 1);
  }
}

rmSync(outputRoot, { recursive: true, force: true });
mkdirSync(resolve(outputRoot, "rest"), { recursive: true });
cpSync(resolve(projectRoot, "docs", "site"), outputRoot, { recursive: true });
cpSync(resolve(projectRoot, "contracts"), resolve(outputRoot, "contracts"), { recursive: true });

run([
  "--yes",
  "@redocly/cli@1.34.5",
  "build-docs",
  "contracts/openapi.yaml",
  "--output",
  "target/contract-docs/site/rest/index.html"
]);

console.log(`Contract documentation built at ${outputRoot}`);
