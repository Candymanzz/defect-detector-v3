/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  theme: {
    extend: {
      colors: {
        panel: "#151a20",
        panelSoft: "#1e242d",
        accent: "#f59e0b",
        ok: "#22c55e",
        ng: "#ef4444"
      }
    }
  },
  plugins: []
};
