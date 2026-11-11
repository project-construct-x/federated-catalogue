import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import vuetify, { transformAssetUrls } from "vite-plugin-vuetify";

const staging = "https://federated-catalogue.staging.construct-x.net";

export default defineConfig({
  base: "/dashboard/",
  plugins: [
    vue({ template: { transformAssetUrls } }),
    vuetify({ autoImport: true })
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: Object.fromEntries(
      ["/admin", "/users", "/roles", "/schemas", "/session"].map((path) => [
        path,
        { target: staging, changeOrigin: true, secure: true }
      ])
    )
  }
});
