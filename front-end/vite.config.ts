import { defineConfig, loadEnv } from "vite";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiBaseUrl = env.VITE_API_BASE_URL || "http://127.0.0.1:8099";

  return {
    base: "./",
    server: {
      host: "localhost",
      port: 5173,
      proxy: {
        "/api": {
          target: apiBaseUrl,
          changeOrigin: true,
        },
        "/health": {
          target: apiBaseUrl,
          changeOrigin: true,
        },
      },
    },
    preview: {
      host: "localhost",
      port: 4173,
    },
    build: {
      outDir: "dist",
      emptyOutDir: true,
      sourcemap: true,
    },
  };
});
