/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_WS_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

type ElectronEnvironment = {
  mode: "development" | "production";
  platform: string;
  versions: {
    chrome?: string;
    electron?: string;
    node?: string;
  };
};

interface Window {
  electronAPI?: {
    logDiagnostic?: (entry: { channel: string; event: string; details: Record<string, unknown> }) => void;
    getEnvironment: () => Promise<ElectronEnvironment>;
  };
}
