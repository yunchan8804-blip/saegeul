#!/usr/bin/env python3
"""Run a fixed Korean continuation experiment against an isolated local Ollama server."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


MODEL_REPOSITORY = "Qwen/Qwen3-0.6B-GGUF"
MODEL_FILENAME = "Qwen3-0.6B-Q8_0.gguf"
MODEL_NAME = "saegeul-quality-qwen06"
OLLAMA_HOST = "127.0.0.1:11436"
OLLAMA_BASE_URL = f"http://{OLLAMA_HOST}"
REQUEST_TIMEOUT_SECONDS = 120
PREFIXES = (
    "내가 뭘",
    "나는 지금",
    "오늘 날씨가",
    "이 문제를",
    "회의가 끝나고",
    "친구에게",
    "점심은",
    "시간이 없어서",
    "그렇게 하면",
    "왜 나한테",
    "확인해 주시면",
    "집에 가는",
)


def json_request(url: str, payload: dict[str, Any], timeout: int) -> dict[str, Any]:
    request = Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code}: {body}") from error
    except URLError as error:
        raise RuntimeError(f"network error: {error.reason}") from error


def get_json(url: str, timeout: int) -> dict[str, Any]:
    with urlopen(Request(url, method="GET"), timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_model_metadata() -> tuple[str, int, str, str]:
    api_url = (
        f"https://huggingface.co/api/models/{MODEL_REPOSITORY}/revision/main?blobs=true"
    )
    model = get_json(api_url, timeout=30)
    revision = model.get("sha")
    if not isinstance(revision, str) or not revision:
        raise RuntimeError("Hugging Face API did not provide a revision SHA")
    sibling = next(
        (item for item in model.get("siblings", []) if item.get("rfilename") == MODEL_FILENAME),
        None,
    )
    if not isinstance(sibling, dict):
        raise RuntimeError(f"official model file is missing: {MODEL_FILENAME}")
    size = sibling.get("size")
    lfs = sibling.get("lfs") if isinstance(sibling.get("lfs"), dict) else {}
    sha256 = lfs.get("sha256")
    if not isinstance(size, int) or size <= 0:
        raise RuntimeError("Hugging Face API did not provide the official file size")
    if not isinstance(sha256, str) or len(sha256) != 64:
        raise RuntimeError("Hugging Face API did not provide the official file SHA-256")
    download_url = (
        f"https://huggingface.co/{MODEL_REPOSITORY}/resolve/{revision}/{MODEL_FILENAME}?download=true"
    )
    return revision, size, sha256, download_url


def download_pinned_model(target: Path, expected_size: int, expected_hash: str, url: str) -> None:
    if target.exists():
        actual_size = target.stat().st_size
        actual_hash = sha256_file(target)
        if actual_size == expected_size and actual_hash == expected_hash:
            return
        raise RuntimeError(
            f"existing artifact does not match pinned official file: {target} "
            f"(size={actual_size}, sha256={actual_hash})"
        )

    partial = target.with_suffix(target.suffix + ".part")
    if partial.exists():
        raise RuntimeError(f"incomplete download exists; inspect before retrying: {partial}")
    request = Request(url, headers={"User-Agent": "saegeul-korean-lm-benchmark/1"})
    with urlopen(request, timeout=60) as response, partial.open("xb") as destination:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            destination.write(chunk)
    actual_size = partial.stat().st_size
    actual_hash = sha256_file(partial)
    if actual_size != expected_size or actual_hash != expected_hash:
        raise RuntimeError(
            "download integrity check failed: "
            f"size={actual_size}/{expected_size}, sha256={actual_hash}/{expected_hash}"
        )
    partial.replace(target)


def port_is_in_use() -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.2)
        return probe.connect_ex(("127.0.0.1", 11436)) == 0


def ps_single_quote(value: str) -> str:
    return value.replace("'", "''")


def start_ollama(ollama_executable: Path, artifact_dir: Path) -> int:
    if port_is_in_use():
        raise RuntimeError(f"refusing to use an existing process on {OLLAMA_HOST}")
    starter = artifact_dir / "start-isolated-ollama.ps1"
    models_dir = artifact_dir / "ollama-models"
    starter.write_text(
        "\n".join(
            (
                f"$env:OLLAMA_HOST = '{OLLAMA_HOST}'",
                f"$env:OLLAMA_MODELS = '{ps_single_quote(str(models_dir.resolve()))}'",
                f"$process = Start-Process -FilePath '{ps_single_quote(str(ollama_executable.resolve()))}' -ArgumentList 'serve' -WindowStyle Hidden -PassThru",
                "$process.Id",
            )
        ),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(starter),
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    output = completed.stdout.strip()
    if not output.isdecimal():
        raise RuntimeError(f"could not determine isolated Ollama PID: {output!r}")
    return int(output)


def wait_for_ollama() -> dict[str, Any]:
    deadline = time.monotonic() + 60
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            return get_json(f"{OLLAMA_BASE_URL}/api/version", timeout=2)
        except Exception as error:  # readiness polling records the final concrete error
            last_error = error
            time.sleep(0.5)
    raise RuntimeError(f"isolated Ollama did not become ready: {last_error}")


def stop_ollama(pid: int) -> None:
    subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            f"Stop-Process -Id {pid} -Force -ErrorAction SilentlyContinue",
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )


def upload_blob(model_file: Path) -> str:
    digest = f"sha256:{sha256_file(model_file)}"
    exists_connection = http.client.HTTPConnection(
        "127.0.0.1", 11436, timeout=REQUEST_TIMEOUT_SECONDS
    )
    try:
        exists_connection.request("HEAD", f"/api/blobs/{digest}")
        exists_response = exists_connection.getresponse()
        exists_response.read()
        if exists_response.status == 200:
            return digest
        if exists_response.status != 404:
            raise RuntimeError(f"blob existence check failed: HTTP {exists_response.status}")
    finally:
        exists_connection.close()

    connection = http.client.HTTPConnection("127.0.0.1", 11436, timeout=REQUEST_TIMEOUT_SECONDS)
    try:
        connection.putrequest("POST", f"/api/blobs/{digest}")
        connection.putheader("Content-Type", "application/octet-stream")
        connection.putheader("Content-Length", str(model_file.stat().st_size))
        connection.endheaders()
        with model_file.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                connection.send(chunk)
        response = connection.getresponse()
        body = response.read().decode("utf-8", errors="replace")
        if response.status not in (200, 201):
            raise RuntimeError(f"blob upload failed: HTTP {response.status}: {body}")
    finally:
        connection.close()
    return digest


def create_model(model_file: Path) -> dict[str, Any]:
    digest = upload_blob(model_file)
    return json_request(
        f"{OLLAMA_BASE_URL}/api/create",
        {
            "model": MODEL_NAME,
            "files": {MODEL_FILENAME: digest},
            "parameters": {"temperature": 0, "seed": 42, "num_ctx": 2048},
            "stream": False,
        },
        timeout=REQUEST_TIMEOUT_SECONDS,
    )


def metrics(response: dict[str, Any]) -> dict[str, Any]:
    return {
        "load_duration_ns": response.get("load_duration"),
        "total_duration_ns": response.get("total_duration"),
        "prompt_eval_count": response.get("prompt_eval_count"),
        "prompt_eval_duration_ns": response.get("prompt_eval_duration"),
        "eval_count": response.get("eval_count"),
        "eval_duration_ns": response.get("eval_duration"),
    }


def raw_request(prefix: str) -> dict[str, Any]:
    return {
        "model": MODEL_NAME,
        "prompt": prefix,
        "raw": True,
        "stream": False,
        "options": {"temperature": 0, "seed": 42, "num_ctx": 2048, "num_predict": 32},
    }


def chat_request(prefix: str) -> dict[str, Any]:
    return {
        "model": MODEL_NAME,
        "stream": False,
        "messages": [
            {
                "role": "user",
                "content": (
                    "/no_think\n"
                    "아래 사용자 문맥 바로 뒤에 올 가능성이 높은 한국어 제안을 JSON으로만 반환하세요.\n"
                    "문맥을 반복하지 말고 next_eojeol에는 바로 다음 어절 하나씩 4개를 넣으세요. "
                    "sentence_suffixes에는 앞 문맥에 그대로 이어붙일 자연스러운 짧은 문장 suffix 2개를 넣으세요. "
                    "설명과 마크다운은 쓰지 마세요.\n"
                    "사용자 문맥:\n"
                    f"{prefix}"
                ),
            }
        ],
        "think": False,
        "format": {
            "type": "object",
            "additionalProperties": False,
            "required": ["next_eojeol", "sentence_suffixes"],
            "properties": {
                "next_eojeol": {
                    "type": "array",
                    "minItems": 4,
                    "maxItems": 4,
                    "items": {"type": "string"},
                },
                "sentence_suffixes": {
                    "type": "array",
                    "minItems": 2,
                    "maxItems": 2,
                    "items": {"type": "string"},
                },
            },
        },
        "options": {"temperature": 0, "seed": 42, "num_ctx": 2048, "num_predict": 160},
    }


def run_request(task: str, prefix: str, request: dict[str, Any]) -> dict[str, Any]:
    started_at = dt.datetime.now(dt.timezone.utc).isoformat()
    try:
        endpoint = "/api/generate" if task == "raw_continuation" else "/api/chat"
        response = json_request(f"{OLLAMA_BASE_URL}{endpoint}", request, REQUEST_TIMEOUT_SECONDS)
        output = response.get("response") if task == "raw_continuation" else response.get("message", {}).get("content")
        record: dict[str, Any] = {
            "task": task,
            "prefix": prefix,
            "request": request,
            "started_at_utc": started_at,
            "response_raw": response,
            "output": output,
            "metrics": metrics(response),
        }
        if not isinstance(output, str) or not output.strip():
            record["status"] = "output_empty"
            return record
        if task.startswith("chat_json"):
            try:
                parsed = json.loads(output)
                if not isinstance(parsed, dict):
                    raise ValueError("top-level response is not an object")
                next_eojeol = parsed.get("next_eojeol")
                suffixes = parsed.get("sentence_suffixes")
                if not isinstance(next_eojeol, list) or len(next_eojeol) != 4:
                    raise ValueError("next_eojeol is not a four-item list")
                if not isinstance(suffixes, list) or len(suffixes) != 2:
                    raise ValueError("sentence_suffixes is not a two-item list")
                record["parsed"] = parsed
                record["status"] = "ok"
            except (json.JSONDecodeError, ValueError, TypeError) as error:
                record["status"] = "parse_failure"
                record["parse_error"] = str(error)
        else:
            record["status"] = "ok"
        return record
    except Exception as error:
        return {
            "task": task,
            "prefix": prefix,
            "request": request,
            "started_at_utc": started_at,
            "status": "request_failure",
            "error": f"{type(error).__name__}: {error}",
        }


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--artifact-dir",
        type=Path,
        default=Path(".artifacts/korean-lm-benchmark"),
        help="isolated model, server, and result directory",
    )
    parser.add_argument(
        "--ollama-executable",
        type=Path,
        default=Path(r"C:\Users\encep\AppData\Local\Programs\Ollama\ollama.exe"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    artifact_dir = args.artifact_dir.resolve()
    artifact_dir.mkdir(parents=True, exist_ok=True)
    if not args.ollama_executable.is_file():
        raise RuntimeError(f"Ollama executable not found: {args.ollama_executable}")

    revision, expected_size, expected_hash, download_url = fetch_model_metadata()
    model_dir = artifact_dir / "models"
    model_dir.mkdir(exist_ok=True)
    model_file = model_dir / f"{revision}-{MODEL_FILENAME}"
    download_pinned_model(model_file, expected_size, expected_hash, download_url)

    run_id = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    run_dir = artifact_dir / "runs" / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    metadata: dict[str, Any] = {
        "status": "running",
        "run_id": run_id,
        "started_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "command": [sys.executable, *sys.argv],
        "model": {
            "repository": MODEL_REPOSITORY,
            "revision": revision,
            "filename": MODEL_FILENAME,
            "official_size_bytes": expected_size,
            "official_sha256": expected_hash,
            "download_url": download_url,
            "local_path": str(model_file),
            "local_sha256": sha256_file(model_file),
        },
        "runtime": {
            "ollama_executable": str(args.ollama_executable.resolve()),
            "host": OLLAMA_HOST,
            "ollama_models": str((artifact_dir / "ollama-models").resolve()),
            "request_timeout_seconds": REQUEST_TIMEOUT_SECONDS,
            "options": {"temperature": 0, "seed": 42, "num_ctx": 2048},
        },
        "inputs": list(PREFIXES),
    }
    write_json(run_dir / "metadata.json", metadata)

    pid: int | None = None
    records: list[dict[str, Any]] = []
    try:
        pid = start_ollama(args.ollama_executable, artifact_dir)
        metadata["runtime"]["server_pid"] = pid
        metadata["runtime"]["ollama_version"] = wait_for_ollama()
        metadata["create_response_raw"] = create_model(model_file)
        with (run_dir / "results.jsonl").open("x", encoding="utf-8") as output:
            for index, prefix in enumerate(PREFIXES, start=1):
                for task, request in (
                    ("raw_continuation", raw_request(prefix)),
                    ("chat_json_structured", chat_request(prefix)),
                ):
                    record = {"input_index": index, **run_request(task, prefix, request)}
                    records.append(record)
                    output.write(json.dumps(record, ensure_ascii=False) + "\n")
                    output.flush()
        metadata["status"] = "completed"
        metadata["result_counts"] = {
            status: sum(record.get("status") == status for record in records)
            for status in sorted({str(record.get("status")) for record in records})
        }
        return 0
    except Exception as error:
        metadata["status"] = "setup_failure"
        metadata["error"] = f"{type(error).__name__}: {error}"
        return 1
    finally:
        if pid is not None:
            stop_ollama(pid)
            metadata["runtime"]["server_stop_attempted"] = True
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(run_dir / "metadata.json", metadata)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"benchmark failed before run setup: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
