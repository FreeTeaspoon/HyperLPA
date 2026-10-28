import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

export default defineConfig({
  plugins: [cloudflareTest({
    wrangler: { configPath: "./wrangler.jsonc" },
    miniflare: { bindings: { ENROLLMENT_KEY: "test-only-enrollment-key", WEB_ORIGINS: "https://dashboard.test" } },
  })],
  test: { testTimeout: 15_000 },
});
