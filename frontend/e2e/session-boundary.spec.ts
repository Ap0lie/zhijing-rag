import { expect, test } from "@playwright/test";

test("an unauthenticated deep link stays in the SPA and shows login", async ({ page }) => {
  const documentRequests: string[] = [];
  page.on("request", (request) => {
    if (request.resourceType() === "document") {
      documentRequests.push(request.url());
    }
  });

  await page.goto("/admin/evaluations?tab=runs");

  await expect(page.getByRole("heading", { name: "登录知识工作台" })).toBeVisible();
  await expect(page.getByLabel("用户名")).toBeVisible();
  await expect(page.getByRole("textbox", { name: "密码" })).toBeVisible();
  expect(documentRequests).toHaveLength(1);
});

test("an authenticated user can navigate without document reloads", async ({ page }) => {
  const username = process.env.E2E_USERNAME;
  const password = process.env.E2E_PASSWORD;
  test.skip(!username || !password, "Set E2E_USERNAME and E2E_PASSWORD for the authenticated flow");

  await page.goto("/login");
  await page.getByLabel("用户名").fill(username!);
  await page.getByLabel("密码").fill(password!);
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("link", { name: "文档" })).toBeVisible();

  let documentReloads = 0;
  page.on("request", (request) => {
    if (request.resourceType() === "document") {
      documentReloads += 1;
    }
  });
  await page.getByRole("link", { name: "问答" }).click();
  await expect(page).toHaveURL(/\/chat$/);
  await page.getByRole("link", { name: "检索" }).click();
  await expect(page).toHaveURL(/\/search$/);
  expect(documentReloads).toBe(0);
});
