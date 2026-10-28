import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests", timeout: 90_000, workers: 1,
  use: { baseURL: "http://127.0.0.1:5178", trace: "retain-on-failure", actionTimeout: 10_000 },
  webServer: [
    { command: "npm run dev -- --port 5178 --strictPort", url: "http://127.0.0.1:5178", reuseExistingServer: !process.env.CI },
    { command: `../relay/node_modules/.bin/wrangler dev --config ../relay/wrangler.jsonc --port 8791 --persist-to ../relay/.wrangler/e2e-${Date.now()} --var WEB_ORIGINS:http://127.0.0.1:5178 --var ENROLLMENT_KEY:test-only-enrollment-key`,
      url: "http://127.0.0.1:8791/health", reuseExistingServer: false, timeout: 60_000 },
  ],
});
