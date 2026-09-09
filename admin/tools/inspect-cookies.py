import os
import sqlite3

paths = [
    os.path.expandvars(r"%LOCALAPPDATA%\saegeul-playwright-chrome\Default\Network\Cookies"),
    os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\User Data\Default\Network\Cookies"),
]
for path in paths:
    print("====", path)
    print("exists", os.path.exists(path), "size", os.path.getsize(path) if os.path.exists(path) else 0)
    try:
        conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        cur = conn.cursor()
        tables = [row[0] for row in cur.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        print("tables", tables)
        count = cur.execute("select count(*) from cookies").fetchone()[0]
        print("rows", count)
        rows = cur.execute(
            "select host_key, name, length(encrypted_value) from cookies "
            "where host_key like '%google%' order by host_key, name"
        ).fetchall()
        print("google-like", len(rows))
        for row in rows[:50]:
            print(row)
        conn.close()
    except Exception as exc:
        print("ERR", type(exc).__name__, exc)
