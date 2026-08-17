#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GitHub Wiki Publisher for Saegeul
Pushes docs/wiki/ markdown files directly to GitHub Wiki repository (saegeul.wiki.git)
"""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

def main():
    wiki_src = Path(r"d:\workspace\fcitx5-android\docs\wiki")
    if not wiki_src.exists():
        print(f"Error: {wiki_src} does not exist")
        return

    tmp_dir = Path(tempfile.gettempdir()) / "saegeul-wiki-deploy"
    if tmp_dir.exists():
        try:
            shutil.rmtree(tmp_dir, ignore_errors=True)
        except Exception:
            pass
    
    os.makedirs(tmp_dir, exist_ok=True)
    shutil.copytree(wiki_src, tmp_dir, dirs_exist_ok=True)
    print(f"Copied {len(list(tmp_dir.glob('*.md')))} wiki documents to {tmp_dir}")

    commands = [
        ["git", "init", "-b", "master"],
        ["git", "config", "user.name", "Yun Chan"],
        ["git", "config", "user.email", "yunchan8804@gmail.com"],
        ["git", "add", "."],
        ["git", "commit", "-m", "Publish Saegeul official wiki documentation"],
        ["git", "remote", "add", "origin", "https://github.com/yunchan8804-blip/saegeul.wiki.git"],
        ["git", "push", "origin", "master", "--force"],
    ]

    for cmd in commands:
        print(f"Running: {' '.join(cmd)}")
        res = subprocess.run(cmd, cwd=tmp_dir, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if res.stdout:
            print(res.stdout.strip())
        if res.returncode != 0:
            print(f"Stderr: {res.stderr.strip()}")
            if "push" in cmd:
                print("\n[안내] GitHub Wiki 저장소에 푸시하지 못했습니다.")
            return

    print("\n[SUCCESS] GitHub Wiki 배포가 성공적으로 완료되었습니다!")

if __name__ == "__main__":
    main()
