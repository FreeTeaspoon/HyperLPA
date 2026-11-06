import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

import { dashboard } from "./ui-fixture";

async function nav(page: Page, name: string) { await page.getByRole("tab", { name, exact: true }).click(); }

test("keyboard context, field errors and theme choices remain accessible", async ({ page }) => {
  await dashboard(page);
  await page.getByRole("button", { name: /Example phone Online/ }).click();
  await expect(page.getByRole("heading", { name: "Profiles", exact: true })).toBeFocused();
  await expect(page).toHaveTitle("Profiles · HyperLPA");
  await page.getByRole("tab", { name: "Profiles", exact: true }).focus();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("tabpanel")).toBeFocused();
  expect(await page.getByRole("tabpanel").evaluate(el => getComputedStyle(el).outlineStyle)).toBe("solid");
  await page.getByRole("button", { name: "Download", exact: true }).click();
  const activation = page.getByLabel("Activation code", { exact: true });
  await activation.fill("invalid");
  await page.getByRole("button", { name: "Get preview", exact: true }).click();
  await expect(activation).toBeFocused();
  await expect(activation).toHaveAttribute("aria-invalid", "true");
  await expect(activation).toHaveAttribute("aria-describedby", "activation-code-error");
  await expect(page.locator("#activation-code-error")).toContainText("LPA:1$");
  await activation.fill("LPA:1$example.test$id");
  await expect(activation).not.toHaveAttribute("aria-invalid");
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await page.getByRole("button", { name: "Appearance" }).click();
  await page.getByRole("menuitemradio", { name: "dark", exact: true }).click();
  await page.getByRole("button", { name: "Appearance" }).click();
  await expect(page.getByRole("menuitemradio", { name: "dark", exact: true })).toHaveAttribute("aria-checked", "true");
  await page.keyboard.press("Escape");
  await nav(page, "Tools");
  await expect(page.getByRole("button", { name: "Rename selected profile" })).toHaveCount(0);
  await nav(page, "Profiles");
  await page.getByRole("button", { name: "Actions for Second plan", exact: true }).click();
  await page.getByRole("menuitem", { name: "Rename", exact: true }).click();
  await page.getByLabel("Name", { exact: true }).fill("   ");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByLabel("Name", { exact: true })).toBeFocused();
  await expect(page.locator("#name-error")).toContainText("Enter a name");
});

test("History deletion requires confirmation and cancellation sends nothing", async ({ page }) => {
  await dashboard(page);
  await nav(page, "History");
  await page.getByRole("button", { name: "Delete history entry", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("cannot be undone");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await nav(page, "Activity");
  await expect(page.getByText("No activity yet", { exact: true })).toBeVisible();
  await nav(page, "History");
  await page.getByRole("button", { name: "Delete history entry", exact: true }).click();
  await page.getByRole("button", { name: "Delete entry", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await nav(page, "Activity");
  await expect(page.getByText("Delete history", { exact: true })).toBeVisible();
});

test("long names reflow and shared controls retain their theme", async ({ page }) => {
  await dashboard(page, true);
  for (const theme of ["light", "dark"] as const) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme);
    for (const width of [320, 390, 1280]) {
      await page.setViewportSize({ width, height: 800 });
      await page.evaluate(() => document.fonts.ready);
      for (const name of ["Devices", "Profiles", "Notifications", "History", "Tools", "Group", "Activity"]) {
        await nav(page, name);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      }
    }
    await nav(page, "Profiles");
    await page.getByRole("button", { name: "Copy EID", exact: true }).click();
    const toast = page.locator("[data-sonner-toast]").last();
    await expect(toast).toBeVisible();
    expect(await toast.evaluate(el => ({ font: getComputedStyle(el).fontFamily, radius: getComputedStyle(el).borderRadius,
      matchesCard: getComputedStyle(el).backgroundColor === getComputedStyle(document.querySelector(".records")!).backgroundColor })))
      .toMatchObject({ radius: "13px", matchesCard: true, font: expect.stringContaining("IBM Plex Sans") });
    await page.getByRole("button", { name: "Download", exact: true }).click();
    expect(await page.getByRole("dialog").evaluate(el => getComputedStyle(el).boxShadow)).toContain("8px 30px");
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).not.toBeVisible();
    await page.getByRole("button", { name: "Appearance" }).click();
    expect(await page.getByRole("menuitemradio", { name: "light", exact: true }).evaluate(el => el.offsetHeight)).toBe(32);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("menu")).not.toBeVisible();
    const audit = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(audit.violations).toEqual([]);
  }
});
