import { expect, type Page } from "@playwright/test";

// Exercise the actual UI without a phone or backend mutations. Vite serves the
// same controller module as the app; no test hook is included in production.
export async function dashboard(page: Page, longNames = false) {
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Create trusted group" })).toBeVisible();
  await seedDashboard(page, longNames);
}
export async function seedDashboard(page: Page, longNames = false) {
  await page.evaluate(async longNames => {
    const { controller } = await import(location.origin + "/src/lib/controller.ts");
    const reader = { id: "reader", name: longNames ? "Reader".repeat(12) : "SIM 1", kind: "OMAPI", available: true, eid: "89049032000000000000000000000001" };
    const snapshot = { lpa: { readers: [reader], selectedReaderId: reader.id, euiccInfo: { eid: reader.eid }, notifications: [], profiles: [
      { iccid: "first", name: "Pinned plan", nickname: "", providerName: "Example Telecom", state: "ENABLED", isPinned: true },
      { iccid: "second", name: "Second plan", nickname: "", providerName: "Example Telecom", state: "DISABLED" },
    ] }, history: [{ timestampEpochMillis: Date.now(), notificationOperation: "ENABLE", status: "SUCCESS", action: "SEND", profileName: "Pinned plan", endpointHost: "example.test" }] };
    controller.command = async () => { controller.emit({ activities: [{ id: "deleted", peer: "phone", label: "Delete history", status: "Completed", time: Date.now() }] }); };
    controller.requestNotifications = controller.readers = async () => {};
    controller.emit({ loaded: true, connected: true, identity: { version: 1, id: "owner", owner: "owner", role: "owner", name: "Test browser", relay: "https://relay.test", revision: 1, grants: {}, pending: [], peers: [
      { id: "phone", name: longNames ? "TravelPhone".repeat(7) : "Example phone", kind: "phone", approved: true, profiles: true, notifications: true },
    ] }, online: { phone: Date.now() + 3_600_000 }, snapshots: { "phone/": snapshot, "phone/reader": snapshot }, phoneRevisions: { phone: 1 }, activities: [],
    notifications: { phone: { available: true, entries: [] } } });
  }, longNames);
}
