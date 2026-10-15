const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const loggerPath = path.resolve(__dirname, "../session-logger.cjs");

function runLogger(environment, setup = "") {
  return spawnSync(process.execPath, ["-e", `
    const fs = require("node:fs");
    ${setup}
    const logger = require(process.argv[1]);
    logger.write("startup-check", { user: "kiosk" });
    logger.drain().then(() => console.log(JSON.stringify({ directory: logger.directory })));
  `, loggerPath], {
    encoding: "utf8",
    env: { ...process.env, IML_LOG_SESSION_DIR: "", IML_ELECTRON_LOG_PATH: "", ...environment },
  });
}

test("kiosk log path uses the user's writable state directory", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "frontend-logger-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const result = runLogger({ IML_ELECTRON_LOG_PATH: path.join(root, "electron-runtime.log") });
  assert.equal(result.status, 0, result.stderr);
  const { directory } = JSON.parse(result.stdout);
  assert.equal(path.dirname(directory), root);
  const files = fs.readdirSync(directory);
  assert.equal(files.length, 1);
  assert.match(fs.readFileSync(path.join(directory, files[0]), "utf8"), /startup-check/);
});

test("permission error falls back instead of aborting Electron startup", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "frontend-logger-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const blocked = path.join(root, "protected-opt");
  const result = runLogger({ IML_LOG_SESSION_DIR: blocked, XDG_STATE_HOME: root }, `
    const mkdir = fs.mkdirSync;
    fs.mkdirSync = (directory, options) => {
      if (directory === process.env.IML_LOG_SESSION_DIR) {
        throw Object.assign(new Error("permission denied"), { code: "EACCES" });
      }
      return mkdir(directory, options);
    };
  `);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stderr, /EACCES/);
  const { directory } = JSON.parse(result.stdout);
  assert.equal(path.dirname(directory), path.join(root, "defect-detector"));
  assert.equal(fs.readdirSync(directory).length, 1);
});

test("unavailable log directories disable logging without blocking startup", () => {
  const result = runLogger({}, `
    fs.mkdirSync = () => { throw Object.assign(new Error("permission denied"), { code: "EACCES" }); };
  `);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).directory, null);
});
