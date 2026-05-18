import http from "node:http";
import net from "node:net";
import { spawn } from "node:child_process";

const host = "localhost";
const preferredPort = 5173;
const port = await findAvailablePort(preferredPort);
const rendererUrl = `http://${host}:${port}`;
const env = {
  ...process.env,
  ELECTRON_RENDERER_URL: rendererUrl,
};

const children = new Set();

const vite = run("vite", ["--host", host, "--port", String(port), "--strictPort"], env);

try {
  await waitForHttp(rendererUrl);
} catch (error) {
  stopChildren();
  console.error(error);
  process.exit(1);
}

const electron = run("electron", ["."], env);

electron.on("exit", (code) => {
  stopChildren(electron);
  process.exit(code ?? 0);
});

process.on("SIGINT", () => {
  stopChildren();
  process.exit(0);
});

process.on("SIGTERM", () => {
  stopChildren();
  process.exit(0);
});

function run(command, args, childEnv) {
  const child = spawn(command, args, {
    env: childEnv,
    shell: true,
    stdio: "inherit",
  });

  children.add(child);

  child.on("exit", () => {
    children.delete(child);
  });

  child.on("error", (error) => {
    stopChildren(child);
    console.error(error);
    process.exit(1);
  });

  return child;
}

function stopChildren(except) {
  for (const child of children) {
    if (child !== except && !child.killed) {
      child.kill();
    }
  }
}

async function findAvailablePort(startPort) {
  for (let currentPort = startPort; currentPort < startPort + 20; currentPort += 1) {
    if (await canListen(currentPort)) {
      return currentPort;
    }
  }

  throw new Error(`No available Vite port found starting at ${startPort}`);
}

function canListen(port) {
  return new Promise((resolve) => {
    const server = net.createServer();

    server.once("error", () => resolve(false));
    server.once("listening", () => {
      server.close(() => resolve(true));
    });

    server.listen(port, host);
  });
}

function waitForHttp(url) {
  return new Promise((resolve, reject) => {
    const startedAt = Date.now();
    const timeoutMs = 30000;

    const check = () => {
      const request = http.get(url, (response) => {
        response.resume();
        resolve();
      });

      request.on("error", () => {
        if (Date.now() - startedAt > timeoutMs) {
          reject(new Error(`Timed out waiting for Vite at ${url}`));
          return;
        }

        setTimeout(check, 250);
      });
    };

    check();
  });
}
