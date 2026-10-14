const fs = require("node:fs");
const path = require("node:path");

const directory = process.env.IML_LOG_SESSION_DIR || path.resolve(
  __dirname, "../../logs", `${new Date().toISOString().replace(/[:.]/g, "-")}-${process.pid}`,
);
fs.mkdirSync(directory, { recursive: true });
const queues = new Map();
const limit = 4096;
const maxBytes = 10 * 1024 * 1024;

function write(event, details = {}, channel = "runtime") {
  if (!["runtime", "requests", "responses", "decisions", "errors"].includes(channel)) return;
  let state = queues.get(channel);
  if (!state) {
    state = { pending: [], busy: false, bytes: 0, dropped: 0 };
    queues.set(channel, state);
  }
  if (state.pending.length >= limit) { state.dropped++; return; }
  // Only primitive metadata is accepted. Never serialize image objects or payloads.
  const safe = {};
  for (const [key, value] of Object.entries(details).slice(0, 20)) {
    if (/image|base64|token|password|cookie|authorization|payload|body/i.test(key)) continue;
    if (typeof value === "string") safe[key] = value.slice(0, 1000);
    else if (["number", "boolean"].includes(typeof value)) safe[key] = value;
  }
  state.pending.push(JSON.stringify({ ts: new Date().toISOString(), event: String(event).slice(0, 100),
    pid: process.pid, ...safe, dropped_before: state.dropped }) + "\n");
  state.dropped = 0;
  flush(channel, state);
}

async function flush(channel, state) {
  if (state.busy) return;
  state.busy = true;
  const filename = path.join(directory, `frontend-${channel}-${process.pid}.log`);
  try {
    while (state.pending.length) {
      const batch = state.pending.splice(0, 100).join("");
      if (state.bytes >= maxBytes) {
        await fs.promises.rm(`${filename}.5`, { force: true });
        for (let i = 4; i >= 1; i--) {
          await fs.promises.rename(`${filename}.${i}`, `${filename}.${i + 1}`).catch(e => {
            if (e.code !== "ENOENT") throw e;
          });
        }
        await fs.promises.rename(filename, `${filename}.1`);
        state.bytes = 0;
      }
      await fs.promises.appendFile(filename, batch, "utf8");
      state.bytes += Buffer.byteLength(batch);
    }
  } catch {
    state.dropped += state.pending.length;
    state.pending.length = 0;
  } finally {
    state.busy = false;
  }
}

async function drain() {
  const deadline = Date.now() + 3000;
  while ([...queues.values()].some(s => s.busy || s.pending.length) && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}

module.exports = { write, drain, directory };
