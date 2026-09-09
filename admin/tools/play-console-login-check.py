from playwright.sync_api import sync_playwright
import sys
from pathlib import Path

out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
out_dir.mkdir(parents=True, exist_ok=True)

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9222")
    ctx = browser.contexts[0]
    page = ctx.new_page()
    page.goto("chrome://version/", wait_until="domcontentloaded", timeout=30000)
    page.wait_for_timeout(1000)
    print("VERSION_TEXT")
    print(page.locator("body").inner_text()[:2000])
    page.screenshot(path=str(out_dir / "chrome-version.png"))

    page.goto("https://accounts.google.com/", wait_until="domcontentloaded", timeout=60000)
    page.wait_for_timeout(4000)
    print("ACCOUNTS_URL", page.url)
    print("ACCOUNTS_TITLE", page.title())
    print(page.locator("body").inner_text()[:3000])
    page.screenshot(path=str(out_dir / "accounts-google.png"), full_page=True)
    page.close()
