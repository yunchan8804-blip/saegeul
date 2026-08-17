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
        shutil.rmtree(tmp_dir, ignore_errors=True)
    
    shutil.copytree(wiki_src, tmp_dir)
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
                print("\n[안내] GitHub Wiki 저장소가 아직 초기화되지 않았습니다.")
                print("GitHub 웹사이트 (https://github.com/yunchan8804-blip/saegeul/wiki)에서")
                print("'Create the first page' 버튼을 눌러 첫 페이지만 저장해주시면 즉시 위키가 활성화되고 동기화됩니다.")
            return

    print("\n✨ GitHub Wiki 배포가 성공적으로 완료되었습니다!")

if __name__ == "__main__":
    main()
