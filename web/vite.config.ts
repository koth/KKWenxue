import { defineConfig } from "vite";

export default defineConfig({
  server: {
    host: "127.0.0.1", port: 5173, strictPort: true,
    proxy: Object.fromEntries(["/api", "/session"].map((path) => [path, {
      target: "http://127.0.0.1:17861", changeOrigin: true,
      configure(proxy: any) {
        proxy.on("proxyReq", (outgoing: any, request: any) => {
          if (/^http:\/\/(127\.0\.0\.1|localhost):5173$/.test(request.headers.origin || ""))
            outgoing.setHeader("Origin", "http://127.0.0.1:17861");
        });
      },
    }])),
  },
});
