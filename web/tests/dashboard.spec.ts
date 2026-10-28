import { test, expect, type BrowserContext, type Page, type Route } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { localRelay, Phone, remoteRelay } from "./phone";

async function bridge(context: BrowserContext) {
  await context.route(remoteRelay + "/**", async route => {
    const response = await route.fetch({ url: route.request().url().replace(remoteRelay, localRelay) });
    await route.fulfill({ response });
  });
  await context.routeWebSocket("wss://relay.test/**", route => {
    const socket = new WebSocket(route.url().replace("wss://relay.test", localRelay.replace("http", "ws")));
    const pending: string[] = [];
    route.onMessage(data => { const message = String(data); if (socket.readyState === WebSocket.OPEN) socket.send(message); else pending.push(message); });
    socket.onopen = () => { for (const data of pending) socket.send(data); };
    socket.onmessage = event => route.send(String(event.data));
    socket.onclose = event => route.close({ code: event.code === 1006 ? 1001 : event.code, reason: event.reason });
    route.onClose(() => socket.close());
  });
}
async function nav(page: Page, name: string) { await page.getByRole("tab", { name, exact: true }).click(); }
async function loseCommittedResponse(context: BrowserContext, path: string) {
  await context.route(remoteRelay + path, async route => {
    const response = await route.fetch({ url: localRelay + path });
    if (response.ok()) await route.abort("failed");
    else await route.fulfill({ response });
  }, { times: 1 });
}

test("enroll once, authorize another browser, manage profiles, recover results and revoke", async ({ browser, page, context }) => {
  await bridge(context);
  const errors: string[] = []; page.on("pageerror", e => errors.push(e.message));
  await page.goto("/");
  await page.getByLabel("Relay address").fill(remoteRelay);
  await page.getByLabel("Enrollment key").fill("test-only-enrollment-key");
  await page.getByLabel("Browser name").fill("Owner laptop");
  // Credentials must survive when the relay commits ownership but its response is lost.
  await loseCommittedResponse(context, "/v1/group/create");
  await page.getByRole("button", { name: "Create trusted group" }).click();
  await expect(page.getByText("Connected", { exact: true })).toBeVisible();
  const phone = new Phone(); await phone.start();
  const secondPhone = new Phone(); secondPhone.name = "Pixel 9";
  try {
    await page.getByRole("button", { name: "Add phone", exact: true }).first().click();
    await page.getByLabel("Phone pairing code").fill(phone.invitation());
    await page.getByRole("button", { name: "Connect phone" }).click();
    await expect(page.getByRole("button", { name: /Xiaomi 14/ })).toBeVisible();
    await page.getByRole("button", { name: /Xiaomi 14/ }).click();
    await expect(page.getByText("Travel data", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Actions for Travel data" }).click();
    await page.getByRole("menuitem", { name: "Disable", exact: true }).click();
    await expect(page.getByRole("dialog")).toContainText("last enabled profile");
    phone.dropMutationResult = true;
    await page.getByRole("button", { name: "Confirm", exact: true }).click();
    await expect(page.getByText("Operation awaiting a verified result")).toBeVisible();
    await page.getByRole("button", { name: "Check status", exact: true }).click();
    await expect(page.getByText("Operation awaiting a verified result")).not.toBeVisible();
    expect(phone.mutations).toBe(1);
    await page.getByRole("button", { name: "Refresh", exact: true }).click();
    await expect(page.getByText("Disabled", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Download", exact: true }).click();
    await page.getByLabel("Activation code", { exact: true }).fill("LPA:1$smdp.test$fixture-matching-id");
    await page.getByRole("button", { name: "Get preview", exact: true }).click();
    await expect(page.getByText("Confirm profile installation", { exact: true })).toBeVisible();
    expect(phone.mutations).toBe(1);
    await page.getByRole("button", { name: "Install profile", exact: true }).click();
    await expect(page.getByText("New data profile", { exact: true })).toBeVisible();
    expect(phone.mutations).toBe(2);
    await page.getByRole("button", { name: "Actions for Travel data" }).click();
    await page.getByRole("menuitem", { name: "Edit tags", exact: true }).click();
    await page.getByLabel("Tags", { exact: true }).fill("Trip, Backup");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByText("Trip · Backup", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Actions for Travel data" }).click();
    await page.getByRole("menuitem", { name: "Pin", exact: true }).click();
    await expect(page.getByText("Pinned", { exact: true })).toBeVisible();
    await nav(page, "Notifications");
    await expect(page.getByText("Your verification code is 482916.", { exact: true })).toBeVisible();
    await nav(page, "Group");
    const headerLeft = await page.locator(".brand").evaluate(el => el.getBoundingClientRect().left);
    await page.getByRole("button", { name: "Recovery", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    expect(await dialog.evaluate(el => getComputedStyle(el).animationDuration)).toBe("0.1s");
    await expect(page.locator("body")).toHaveAttribute("data-scroll-locked", "1");
    expect(await page.locator(".brand").evaluate(el => el.getBoundingClientRect().left)).toBe(headerLeft);
    await page.keyboard.press("Tab"); await page.keyboard.press("Shift+Tab");
    expect(await dialog.evaluate(el => el.contains(document.activeElement))).toBe(true);
    await page.keyboard.press("Escape"); await expect(dialog).not.toBeVisible();
    await expect(page.getByRole("button", { name: "Recovery", exact: true })).toBeFocused();
    await expect(page.locator("body")).not.toHaveAttribute("data-scroll-locked");
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.getByRole("button", { name: "Recovery", exact: true }).click();
    expect(await dialog.evaluate(el => getComputedStyle(el).animationDuration)).toBe("0.001s");
    await page.emulateMedia({ reducedMotion: "no-preference" });
    await page.getByRole("button", { name: "Generate export" }).click();
    const recoveryKey = await page.getByLabel("Recovery key", { exact: true }).inputValue();
    const downloading = page.waitForEvent("download");
    await page.getByRole("button", { name: "Download export" }).click();
    const backup = await downloading;
    const backupPath = test.info().outputPath("owner-recovery.hyperlpa"); await backup.saveAs(backupPath);
    await page.getByRole("button", { name: "Done", exact: true }).click();
    await secondPhone.start();
    await nav(page, "Devices");
    await page.getByRole("button", { name: "Add phone", exact: true }).click();
    await page.getByLabel("Phone pairing code").fill(secondPhone.invitation());
    await page.getByRole("button", { name: "Connect phone" }).click();
    await expect(page.getByRole("button", { name: /Pixel 9/ })).toBeVisible();
    await nav(page, "Group");
    await page.getByRole("button", { name: "Invite browser" }).click();
    await page.getByRole("button", { name: "Create invitation" }).click();
    const code = await page.getByLabel("Invitation code", { exact: true }).inputValue();
    await page.getByRole("button", { name: "Done", exact: true }).click();
    const second = await browser.newContext(); await bridge(second);
    const viewer = await second.newPage(); viewer.on("pageerror", e => errors.push(e.message));
    await viewer.goto("/"); await viewer.getByRole("tab", { name: "Join group" }).click();
    await viewer.getByLabel("Group invitation", { exact: true }).fill(code);
    await viewer.getByLabel("Browser name").fill("Second browser");
    await loseCommittedResponse(second, "/v1/group/join");
    await viewer.getByRole("button", { name: "Request to join" }).click();
    await expect(viewer.getByText("Waiting for owner approval")).toBeVisible();
    await expect(page.getByText("Second browser", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Approve", exact: true }).click();
    await expect(viewer.getByText("Waiting for owner approval")).not.toBeVisible();
    await expect(viewer.getByText("No phones shared yet")).toBeVisible();
    await page.getByRole("checkbox", { name: "Manage profiles on Xiaomi 14" }).check();
    await page.getByRole("checkbox", { name: "View notifications on Xiaomi 14" }).check();
    await page.getByRole("checkbox", { name: "Manage profiles on Pixel 9" }).check();
    await expect(viewer.getByRole("button", { name: /Pixel 9/ })).toBeVisible();
    await viewer.getByRole("button", { name: /Pixel 9/ }).click();
    await expect(viewer.getByRole("combobox", { name: "Phone", exact: true })).toHaveText("Pixel 9");
    await expect(viewer.getByText("Travel data", { exact: true })).toBeVisible();
    expect([...secondPhone.peers.values()].find(p => p.kind === "browser")!.secret)
      .not.toBe([...phone.peers.values()].find(p => p.kind === "browser")!.secret);
    await nav(viewer, "Devices");
    await expect(viewer.getByRole("button", { name: /Xiaomi 14/ })).toBeVisible();
    await viewer.getByRole("button", { name: /Xiaomi 14/ }).click();
    await expect(viewer.getByText("Travel data", { exact: true })).toBeVisible();
    const peers = [...phone.peers.values()];
    expect(peers.find(p => p.kind === "browser")!.secret).not.toBe(peers.find(p => p.kind === "owner")!.secret);
    await viewer.evaluate(() => document.fonts.ready);
    await viewer.screenshot({ path: "test-results/profiles-desktop-light.png", fullPage: true });
    const styles = await viewer.getByRole("button", { name: "Download", exact: true }).evaluate(el => ({ height: el.getBoundingClientRect().height,
      radius: getComputedStyle(el).borderRadius, font: getComputedStyle(el).fontFamily }));
    expect(styles.height).toBe(32); expect(styles.radius).toBe("10px"); expect(styles.font).toContain("IBM Plex Sans");
    await viewer.setViewportSize({ width: 375, height: 812 });
    await viewer.getByRole("button", { name: "Appearance" }).click();
    await viewer.getByRole("menuitem", { name: /^dark$/i }).click();
    await viewer.reload();
    await expect(viewer.locator("html")).toHaveAttribute("data-theme", "dark");
    await nav(viewer, "Profiles");
    await expect(viewer.getByText("Travel data", { exact: true })).toBeVisible();
    expect(await viewer.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await viewer.screenshot({ path: "test-results/profiles-mobile-dark.png", fullPage: true });
    const a11y = await new AxeBuilder({ page: viewer }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(a11y.violations).toEqual([]);
    await nav(viewer, "Notifications");
    await expect(viewer.getByText("Your verification code is 482916.", { exact: true })).toBeVisible();
    let interrupted = false;
    const failPublication = async (route: Route) => {
      const body = route.request().postDataJSON();
      if (!interrupted && body.id !== route.request().headers()["x-device-id"]) {
        interrupted = true; await route.fulfill({ status: 503, json: { error: "test_interruption" } });
      } else await route.fallback();
    };
    await context.route(remoteRelay + "/v1/group/publish", failPublication);
    await page.getByRole("checkbox", { name: "Manage profiles on Xiaomi 14" }).uncheck();
    await expect.poll(() => interrupted).toBe(true);
    await context.unroute(remoteRelay + "/v1/group/publish", failPublication);
    await page.getByRole("button", { name: "Browser settings" }).click();
    await page.getByRole("menuitem", { name: "Reconnect", exact: true }).click();
    await nav(viewer, "Profiles");
    await expect(viewer.getByRole("combobox", { name: "Phone", exact: true })).toHaveText("Pixel 9");
    await viewer.getByRole("combobox", { name: "Phone", exact: true }).click();
    await expect(viewer.getByRole("option", { name: "Xiaomi 14", exact: true })).not.toBeVisible();
    await viewer.keyboard.press("Escape");
    await nav(viewer, "Notifications");
    await expect(viewer.getByText("Your verification code is 482916.", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Manage", exact: true }).click();
    await page.getByRole("button", { name: "Manage Second browser" }).click();
    await page.getByRole("menuitem", { name: "Remove device" }).click();
    await page.getByRole("button", { name: "Confirm", exact: true }).click();
    await expect(viewer.getByText(/This device was removed/)).toBeVisible();
    await expect(viewer.getByText("Your verification code is 482916.", { exact: true })).not.toBeVisible();
    expect(errors).toEqual([]);
    await second.close();
    const restoredContext = await browser.newContext(); await bridge(restoredContext);
    const restored = await restoredContext.newPage(); await restored.goto("/");
    await restored.getByRole("tab", { name: "Restore", exact: true }).click();
    await restored.getByLabel("Recovery file").setInputFiles(backupPath);
    await restored.getByLabel("Recovery key", { exact: true }).fill(recoveryKey);
    await restored.getByRole("button", { name: "Restore device" }).click();
    await expect(restored.getByRole("button", { name: /Xiaomi 14/ })).toBeVisible();
    await expect(restored.getByRole("button", { name: /Pixel 9/ })).toBeVisible();
    await nav(restored, "Group");
    // The relay's newer encrypted owner package supplements an older recovery export.
    await expect(restored.getByText("Second browser", { exact: true })).not.toBeVisible();
    await restoredContext.close();
  } finally { phone.close(); secondPhone.close(); }
});

test("setup keyboard navigation, validation, system theme and narrow layout", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 }); await page.goto("/");
  await page.getByRole("tab", { name: "Join group" }).click();
  await page.getByLabel("Group invitation", { exact: true }).fill("invalid");
  await page.getByLabel("Browser name").fill("Test"); await page.getByRole("button", { name: "Request to join" }).click();
  await expect(page.getByRole("alert")).toContainText("valid pairing code");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const a11y = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  expect(a11y.violations).toEqual([]);
});
