import { createRequire } from "node:module";
import { writeFile } from "node:fs/promises";
import path from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require("C:/Users/encep/AppData/Roaming/npm/node_modules/playwright");

const CDP = "http://127.0.0.1:9222";
const TARGET = "https://play.google.com/console/u/0/developers/";
const outDir = process.argv[2] || ".";

const browser = await chromium.connectOverCDP(CDP);
const context = browser.contexts()[0] ?? (await browser.newContext());
const page =
  context.pages().find((p) => /play\.google\.com|accounts\.google\.com/.test(p.url())) ??
  context.pages()[0] ??
  (await context.newPage());

await page.goto(TARGET, { waitUntil: "domcontentloaded", timeout: 60000 });
await page.waitForTimeout(4000);

const info = {
  url: page.url(),
  title: await page.title(),
  cookies: (await context.cookies("https://play.google.com")).map((c) => c.name),
  googleCookies: (await context.cookies("https://accounts.google.com")).map((c) => c.name),
};
const bodyText = (await page.locator("body").innerText().catch(() => "")).slice(0, 4000);

await page.screenshot({
  path: path.join(outDir, "play-console.png"),
  fullPage: true,
});
await writeFile(
  path.join(outDir, "play-console.json"),
  JSON.stringify({ ...info, bodyText }, null, 2),
  "utf8",
);
console.log(JSON.stringify(info, null, 2));
console.log("--- body ---");
console.log(bodyText);
// Keep CDP connection; don't close the user's Chrome.
await browser.close();
