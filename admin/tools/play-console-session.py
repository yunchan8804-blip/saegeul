from playwright.sync_api import sync_playwright
import json
import sys
from pathlib import Path

out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
out_dir.mkdir(parents=True, exist_ok=True)

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9222")
    print("contexts", len(browser.contexts))
    for i, ctx in enumerate(browser.contexts):
        cookies = ctx.cookies()
        print(f"context[{i}] pages={len(ctx.pages)} cookies={len(cookies)}")
        for c in cookies:
            print(f"  {c.get('domain')} {c.get('name')}")
        for j, page in enumerate(ctx.pages):
            print(f"  page[{j}] {page.url} | {page.title()}")

    ctx = browser.contexts[0]
    page = ctx.new_page()
    page.goto("https://myaccount.google.com/", wait_until="domcontentloaded", timeout=60000)
    page.wait_for_timeout(4000)
    print("ACCOUNT_URL", page.url)
    print("ACCOUNT_TITLE", page.title())
    text = page.locator("body").inner_text()[:2500]
    print("ACCOUNT_BODY")
    print(text)
    page.screenshot(path=str(out_dir / "google-account.png"), full_page=True)

    page.goto(
        "https://play.google.com/console/developers/",
        wait_until="domcontentloaded",
        timeout=60000,
    )
    page.wait_for_timeout(5000)
    print("PLAY_URL", page.url)
    print("PLAY_TITLE", page.title())
    play_text = page.locator("body").inner_text()[:2500]
    print("PLAY_BODY")
    print(play_text)
    page.screenshot(path=str(out_dir / "play-console-2.png"), full_page=True)
    (out_dir / "play-session.json").write_text(
        json.dumps(
            {
                "accountUrl": page.url,
                "playUrl": page.url,
                "playTitle": page.title(),
                "playText": play_text,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    page.close()
