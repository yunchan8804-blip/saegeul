#!/usr/bin/env python3
"""Run fixed raw Korean-continuation experiments with Polyglot-Ko in an isolated venv."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any
import unicodedata
from urllib.request import Request, urlopen


REPOSITORY = "EleutherAI/polyglot-ko-1.3b"
LICENSE = "apache-2.0"
INSTALL_ROOT = Path(r"C:\Users\encep\AppData\Local\SaegeulBench\polyglot-1.3b")
ARTIFACT_ROOT = Path(".artifacts/polyglot-benchmark")
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
SAFE_FILENAMES = {
    "config.json",
    "generation_config.json",
    "model.safetensors.index.json",
    "model-00001-of-00003.safetensors",
    "model-00002-of-00003.safetensors",
    "model-00003-of-00003.safetensors",
    "special_tokens_map.json",
    "tokenizer.json",
    "tokenizer_config.json",
}
TORCH_VERSION = "2.7.1+cu128"
TRANSFORMERS_VERSION = "4.52.4"
SAFETENSORS_VERSION = "0.5.3"
PSUTIL_VERSION = "7.0.0"
NEXT_EOJEOL_BEAM_WIDTH = 16
NEXT_EOJEOL_TOP_K = 8
NEXT_EOJEOL_MAX_NEW_TOKENS = 8


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def bench_environment() -> dict[str, str]:
    root = INSTALL_ROOT.resolve()
    tmp = root / "tmp"
    hf_home = root / "hf-home"
    pip_cache = root / "pip-cache"
    for directory in (root, tmp, hf_home, pip_cache):
        directory.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment.update(
        {
            "HF_HOME": str(hf_home),
            "TMP": str(tmp),
            "TEMP": str(tmp),
            "PIP_CACHE_DIR": str(pip_cache),
            "PYTHONUTF8": "1",
        }
    )
    return environment


def run_checked(command: list[str], environment: dict[str, str]) -> None:
    subprocess.run(command, check=True, env=environment, timeout=1800)


def bootstrap(next_eojeol: bool = False, token_boundary: bool = False) -> int:
    environment = bench_environment()
    venv = INSTALL_ROOT / "venv"
    python = venv / "Scripts" / "python.exe"
    if not python.exists():
        run_checked(["py", "-3.12", "-m", "venv", str(venv)], environment)
    run_checked([str(python), "-m", "pip", "install", "--upgrade", "pip"], environment)
    run_checked(
        [
            str(python),
            "-m",
            "pip",
            "install",
            f"torch=={TORCH_VERSION}",
            "--index-url",
            "https://download.pytorch.org/whl/cu128",
        ],
        environment,
    )
    run_checked(
        [
            str(python),
            "-m",
            "pip",
            "install",
            f"transformers=={TRANSFORMERS_VERSION}",
            f"safetensors=={SAFETENSORS_VERSION}",
            f"psutil=={PSUTIL_VERSION}",
        ],
        environment,
    )
    completed = subprocess.run(
        [
            str(python),
            str(Path(__file__).resolve()),
            "--token-boundary-runner" if token_boundary else "--next-eojeol-runner" if next_eojeol else "--runner",
        ],
        env=environment,
        timeout=3600,
    )
    return completed.returncode


def get_json(url: str) -> dict[str, Any]:
    with urlopen(Request(url, method="GET"), timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def resolve_revision() -> tuple[str, list[dict[str, Any]], str]:
    api_url = f"https://huggingface.co/api/models/{REPOSITORY}/revision/main?blobs=true"
    model = get_json(api_url)
    revision = model.get("sha")
    if not isinstance(revision, str) or not revision:
        raise RuntimeError("official Hugging Face API did not return a revision SHA")
    license_value = (model.get("cardData") or {}).get("license")
    if license_value != LICENSE:
        raise RuntimeError(f"unexpected model license: {license_value!r}")
    siblings = [item for item in model.get("siblings", []) if item.get("rfilename") in SAFE_FILENAMES]
    actual_names = {item.get("rfilename") for item in siblings}
    if actual_names != SAFE_FILENAMES:
        raise RuntimeError(f"safe file set does not match official revision: {actual_names}")
    return revision, siblings, api_url


def download_safe_model(revision: str, siblings: list[dict[str, Any]]) -> tuple[Path, list[dict[str, Any]]]:
    from huggingface_hub import hf_hub_download

    model_dir = INSTALL_ROOT / "model" / revision
    model_dir.mkdir(parents=True, exist_ok=True)
    manifest: list[dict[str, Any]] = []
    sibling_by_name = {item["rfilename"]: item for item in siblings}
    for filename in sorted(SAFE_FILENAMES):
        downloaded = Path(
            hf_hub_download(
                repo_id=REPOSITORY,
                filename=filename,
                revision=revision,
                local_dir=model_dir,
                local_files_only=False,
            )
        )
        if downloaded.resolve().parent != model_dir.resolve():
            raise RuntimeError(f"download escaped benchmark model directory: {downloaded}")
        sibling = sibling_by_name[filename]
        expected_size = sibling.get("size")
        actual_size = downloaded.stat().st_size
        if expected_size != actual_size:
            raise RuntimeError(f"size mismatch for {filename}: {actual_size}/{expected_size}")
        expected_hash = (sibling.get("lfs") or {}).get("sha256")
        actual_hash = sha256_file(downloaded)
        if expected_hash is not None and expected_hash != actual_hash:
            raise RuntimeError(f"SHA-256 mismatch for {filename}")
        manifest.append(
            {
                "filename": filename,
                "bytes": actual_size,
                "sha256": actual_hash,
                "official_lfs_sha256": expected_hash,
                "url": f"https://huggingface.co/{REPOSITORY}/resolve/{revision}/{filename}",
            }
        )
    forbidden = list(model_dir.glob("*.bin")) + list(model_dir.glob("*.py"))
    if forbidden:
        raise RuntimeError(f"forbidden pickle or remote-code file appeared: {forbidden}")
    return model_dir, manifest


def memory_snapshot(torch: Any, process: Any, device: str) -> dict[str, int | None]:
    snapshot: dict[str, int | None] = {"process_rss_bytes": process.memory_info().rss}
    if device == "cuda":
        snapshot.update(
            {
                "cuda_allocated_bytes": torch.cuda.memory_allocated(),
                "cuda_reserved_bytes": torch.cuda.memory_reserved(),
                "cuda_peak_allocated_bytes": torch.cuda.max_memory_allocated(),
            }
        )
    return snapshot


def validate_generation_contract(model: Any, tokenizer: Any, torch: Any, device: str) -> dict[str, Any]:
    encoded = tokenizer(PREFIXES[0], return_tensors="pt", add_special_tokens=False)
    encoded = {
        name: value.to(device)
        for name, value in encoded.items()
        if name in {"input_ids", "attention_mask"}
    }
    input_length = encoded["input_ids"].shape[1]
    try:
        with torch.inference_mode():
            generated = model.generate(
                **encoded,
                do_sample=False,
                max_new_tokens=1,
                pad_token_id=tokenizer.eos_token_id,
            )
        output = generated[0, input_length:].tolist()
        return {
            "status": "ok",
            "model_input_keys": sorted(encoded),
            "probe_prefix": PREFIXES[0],
            "generated_token_ids": output,
            "raw_output": tokenizer.decode(output, skip_special_tokens=False),
        }
    except Exception as error:
        return {
            "status": "failure",
            "model_input_keys": sorted(encoded),
            "probe_prefix": PREFIXES[0],
            "error": f"{type(error).__name__}: {error}",
        }


def generate_records(model: Any, tokenizer: Any, torch: Any, process: Any, device: str, prefix: str) -> list[dict[str, Any]]:
    encoded = tokenizer(prefix, return_tensors="pt", add_special_tokens=False)
    encoded = {
        name: value.to(device)
        for name, value in encoded.items()
        if name in {"input_ids", "attention_mask"}
    }
    input_length = encoded["input_ids"].shape[1]
    input_tokens = encoded["input_ids"][0].tolist()
    common = {
        "prefix": prefix,
        "input_token_ids": input_tokens,
        "input_token_count": input_length,
        "device": device,
    }
    records: list[dict[str, Any]] = []
    with torch.inference_mode():
        greedy_started = time.perf_counter()
        try:
            greedy = model.generate(
                **encoded,
                do_sample=False,
                max_new_tokens=32,
                pad_token_id=tokenizer.eos_token_id,
            )
            generated = greedy[0, input_length:].tolist()
            records.append(
                {
                    "task": "greedy_raw_continuation",
                    **common,
                    "status": "ok",
                    "wall_time_ms": round((time.perf_counter() - greedy_started) * 1000, 3),
                    "generated_token_ids": generated,
                    "generated_token_count": len(generated),
                    "raw_output": tokenizer.decode(generated, skip_special_tokens=False),
                    "memory": memory_snapshot(torch, process, device),
                }
            )
        except Exception as error:
            records.append(
                {
                    "task": "greedy_raw_continuation",
                    **common,
                    "status": "failure",
                    "wall_time_ms": round((time.perf_counter() - greedy_started) * 1000, 3),
                    "error": f"{type(error).__name__}: {error}",
                    "memory": memory_snapshot(torch, process, device),
                }
            )

        beam_started = time.perf_counter()
        try:
            beams = model.generate(
                **encoded,
                do_sample=False,
                num_beams=4,
                num_return_sequences=4,
                max_new_tokens=12,
                length_penalty=1.0,
                early_stopping=False,
                pad_token_id=tokenizer.eos_token_id,
            )
            candidates = []
            for sequence in beams:
                generated = sequence[input_length:].tolist()
                candidates.append(
                    {
                        "generated_token_ids": generated,
                        "generated_token_count": len(generated),
                        "raw_output": tokenizer.decode(generated, skip_special_tokens=False),
                    }
                )
            records.append(
                {
                    "task": "beam_raw_continuation",
                    **common,
                    "status": "ok",
                    "wall_time_ms": round((time.perf_counter() - beam_started) * 1000, 3),
                    "num_beams": 4,
                    "num_return_sequences": 4,
                    "max_new_tokens": 12,
                    "length_penalty": 1.0,
                    "early_stopping": False,
                    "candidates": candidates,
                    "memory": memory_snapshot(torch, process, device),
                }
            )
        except Exception as error:
            records.append(
                {
                    "task": "beam_raw_continuation",
                    **common,
                    "status": "failure",
                    "wall_time_ms": round((time.perf_counter() - beam_started) * 1000, 3),
                    "error": f"{type(error).__name__}: {error}",
                    "memory": memory_snapshot(torch, process, device),
                }
            )
    return records


def decode_tokens(tokenizer: Any, token_ids: list[int]) -> str:
    return tokenizer.decode(
        token_ids,
        skip_special_tokens=False,
        clean_up_tokenization_spaces=False,
    )


def first_eojeol_boundary(text: str) -> tuple[str, str] | None:
    start = next((index for index, char in enumerate(text) if not char.isspace()), None)
    if start is None:
        return None
    for index in range(start + 1, len(text)):
        char = text[index]
        if char.isspace():
            return text[start:index], "whitespace"
        if char in ".?!":
            return text[start:index], "sentence_punctuation"
    return None


def invalid_final_candidate(surface: str) -> bool:
    return not surface or any(
        char == "\ufffd" or unicodedata.category(char) in {"Cc", "Cf", "Cs"}
        for char in surface
    )


def complete_eojeol_path(tokenizer: Any, token_ids: list[int], score: float, eos_ids: set[int], token_id: int) -> tuple[str, dict[str, Any]] | None:
    raw_text = decode_tokens(tokenizer, token_ids)
    boundary = first_eojeol_boundary(raw_text)
    if boundary is not None:
        surface, reason = boundary
    elif token_id in eos_ids:
        raw_without_eos = decode_tokens(tokenizer, token_ids[:-1])
        first_non_whitespace = raw_without_eos.lstrip()
        if not first_non_whitespace:
            return "incomplete", {
                "raw_generated_text": raw_text,
                "token_ids": token_ids,
                "cumulative_logprob": score,
                "termination_reason": "eos_empty",
            }
        surface = first_non_whitespace
        reason = "eos"
    else:
        return None
    record = {
        "raw_generated_text": raw_text,
        "surface": surface,
        "token_ids": token_ids,
        "cumulative_logprob": score,
        "termination_reason": reason,
    }
    if invalid_final_candidate(surface):
        return "invalid", record
    return "completed", record


def next_eojeol_record(model: Any, tokenizer: Any, torch: Any, process: Any, device: str, prefix: str) -> dict[str, Any]:
    conditioned_prefix = prefix.rstrip() + " "
    encoded = tokenizer(conditioned_prefix, return_tensors="pt", add_special_tokens=False)
    base_ids = encoded["input_ids"][0].tolist()
    eos_token_id = model.generation_config.eos_token_id
    eos_ids = {eos_token_id} if isinstance(eos_token_id, int) else set(eos_token_id or [])
    started = time.perf_counter()
    active = [{"token_ids": [], "cumulative_logprob": 0.0}]
    completed: list[dict[str, Any]] = []
    invalid: list[dict[str, Any]] = []
    incomplete: list[dict[str, Any]] = []
    try:
        with torch.inference_mode():
            for step in range(1, NEXT_EOJEOL_MAX_NEW_TOKENS + 1):
                if not active:
                    break
                batch_ids = [base_ids + state["token_ids"] for state in active]
                input_ids = torch.tensor(batch_ids, dtype=torch.long, device=device)
                attention_mask = torch.ones_like(input_ids, device=device)
                logits = model(input_ids=input_ids, attention_mask=attention_mask).logits[:, -1, :]
                log_probs = torch.log_softmax(logits, dim=-1)
                values, token_ids = torch.topk(log_probs, k=NEXT_EOJEOL_TOP_K, dim=-1)
                extensions: list[dict[str, Any]] = []
                for state_index, state in enumerate(active):
                    for rank in range(NEXT_EOJEOL_TOP_K):
                        token_id = int(token_ids[state_index, rank].item())
                        path = [*state["token_ids"], token_id]
                        score = float(state["cumulative_logprob"] + values[state_index, rank].item())
                        finished = complete_eojeol_path(tokenizer, path, score, eos_ids, token_id)
                        if finished is None:
                            extensions.append({"token_ids": path, "cumulative_logprob": score})
                            continue
                        kind, record = finished
                        record["step"] = step
                        if kind == "completed":
                            completed.append(record)
                        elif kind == "invalid":
                            invalid.append(record)
                        else:
                            incomplete.append(record)
                active = sorted(
                    extensions,
                    key=lambda state: state["cumulative_logprob"],
                    reverse=True,
                )[:NEXT_EOJEOL_BEAM_WIDTH]
        for state in active:
            incomplete.append(
                {
                    "raw_generated_text": decode_tokens(tokenizer, state["token_ids"]),
                    "token_ids": state["token_ids"],
                    "cumulative_logprob": state["cumulative_logprob"],
                    "termination_reason": "max_generated_tokens",
                    "step": NEXT_EOJEOL_MAX_NEW_TOKENS,
                }
            )
        best_by_surface: dict[str, dict[str, Any]] = {}
        for candidate in completed:
            previous = best_by_surface.get(candidate["surface"])
            if previous is None or candidate["cumulative_logprob"] > previous["cumulative_logprob"]:
                best_by_surface[candidate["surface"]] = candidate
        selected = sorted(
            best_by_surface.values(),
            key=lambda candidate: candidate["cumulative_logprob"],
            reverse=True,
        )[:4]
        return {
            "task": "next_eojeol_raw_beam",
            "prefix": prefix,
            "conditioned_prefix": conditioned_prefix,
            "input_token_ids": base_ids,
            "status": "ok" if selected else "failure",
            "wall_time_ms": round((time.perf_counter() - started) * 1000, 3),
            "algorithm": {
                "name": "bounded_next_eojeol_logprob_beam",
                "beam_width": NEXT_EOJEOL_BEAM_WIDTH,
                "per_step_top_k": NEXT_EOJEOL_TOP_K,
                "max_generated_tokens": NEXT_EOJEOL_MAX_NEW_TOKENS,
                "score": "cumulative_logprob_without_length_normalization",
                "completion": "first non-whitespace eojeol followed by whitespace, .?!, newline, or EOS",
            },
            "selected_top4": selected,
            "completed_pool": completed,
            "invalid_terminations": invalid,
            "incomplete_paths": incomplete,
            "memory": memory_snapshot(torch, process, device),
        }
    except Exception as error:
        return {
            "task": "next_eojeol_raw_beam",
            "prefix": prefix,
            "conditioned_prefix": conditioned_prefix,
            "input_token_ids": base_ids,
            "status": "failure",
            "wall_time_ms": round((time.perf_counter() - started) * 1000, 3),
            "error": f"{type(error).__name__}: {error}",
            "memory": memory_snapshot(torch, process, device),
        }


def is_punctuation(char: str) -> bool:
    return unicodedata.category(char).startswith("P")


def token_boundary_eojeol_path(tokenizer: Any, token_ids: list[int], score: float, eos_ids: set[int], token_id: int) -> tuple[str, dict[str, Any]] | None:
    raw_text = decode_tokens(tokenizer, token_ids)
    eos = token_id in eos_ids
    visible_text = decode_tokens(tokenizer, token_ids[:-1]) if eos else raw_text
    base_record = {
        "raw_generated_text": raw_text,
        "token_ids": token_ids,
        "cumulative_logprob": score,
    }
    if not visible_text:
        if eos:
            return "no_eojeol", {**base_record, "termination_reason": "eos_before_eojeol"}
        return None
    first_char = visible_text[0]
    if first_char == "\ufffd":
        if eos:
            return "incomplete", {**base_record, "termination_reason": "eos_before_leading_whitespace_unresolved_utf8"}
        return None
    if not first_char.isspace():
        if is_punctuation(first_char):
            return "no_eojeol", {**base_record, "termination_reason": "leading_punctuation"}
        return "current_word_extension", {**base_record, "termination_reason": "missing_leading_whitespace"}
    eojeol_text = visible_text.lstrip()
    if not eojeol_text:
        if eos:
            return "no_eojeol", {**base_record, "termination_reason": "eos_after_leading_whitespace"}
        return None
    if eojeol_text[0] == "\ufffd":
        if eos:
            return "invalid", {
                **base_record,
                "surface": eojeol_text,
                "termination_reason": "eos_invalid_leading_eojeol",
            }
        return None
    if is_punctuation(eojeol_text[0]):
        return "no_eojeol", {**base_record, "termination_reason": "leading_punctuation"}
    boundary = first_eojeol_boundary(eojeol_text)
    if boundary is not None:
        surface, reason = boundary
    elif eos:
        surface = eojeol_text
        reason = "eos"
    else:
        return None
    record = {
        **base_record,
        "surface": surface,
        "termination_reason": reason,
    }
    if invalid_final_candidate(surface):
        return "invalid", record
    return "completed", record


def token_boundary_eojeol_record(model: Any, tokenizer: Any, torch: Any, process: Any, device: str, prefix: str) -> dict[str, Any]:
    conditioned_prefix = prefix.rstrip()
    encoded = tokenizer(conditioned_prefix, return_tensors="pt", add_special_tokens=False)
    base_ids = encoded["input_ids"][0].tolist()
    eos_token_id = model.generation_config.eos_token_id
    eos_ids = {eos_token_id} if isinstance(eos_token_id, int) else set(eos_token_id or [])
    started = time.perf_counter()
    active = [{"token_ids": [], "cumulative_logprob": 0.0}]
    completed: list[dict[str, Any]] = []
    invalid: list[dict[str, Any]] = []
    incomplete: list[dict[str, Any]] = []
    current_word_extensions: list[dict[str, Any]] = []
    no_eojeol: list[dict[str, Any]] = []
    try:
        with torch.inference_mode():
            for step in range(1, NEXT_EOJEOL_MAX_NEW_TOKENS + 1):
                if not active:
                    break
                batch_ids = [base_ids + state["token_ids"] for state in active]
                input_ids = torch.tensor(batch_ids, dtype=torch.long, device=device)
                attention_mask = torch.ones_like(input_ids, device=device)
                logits = model(input_ids=input_ids, attention_mask=attention_mask).logits[:, -1, :]
                log_probs = torch.log_softmax(logits, dim=-1)
                values, token_ids = torch.topk(log_probs, k=NEXT_EOJEOL_TOP_K, dim=-1)
                extensions: list[dict[str, Any]] = []
                for state_index, state in enumerate(active):
                    for rank in range(NEXT_EOJEOL_TOP_K):
                        token_id = int(token_ids[state_index, rank].item())
                        path = [*state["token_ids"], token_id]
                        score = float(state["cumulative_logprob"] + values[state_index, rank].item())
                        finished = token_boundary_eojeol_path(tokenizer, path, score, eos_ids, token_id)
                        if finished is None:
                            extensions.append({"token_ids": path, "cumulative_logprob": score})
                            continue
                        kind, record = finished
                        record["step"] = step
                        if kind == "completed":
                            completed.append(record)
                        elif kind == "invalid":
                            invalid.append(record)
                        elif kind == "current_word_extension":
                            current_word_extensions.append(record)
                        elif kind == "no_eojeol":
                            no_eojeol.append(record)
                        else:
                            incomplete.append(record)
                active = sorted(
                    extensions,
                    key=lambda state: state["cumulative_logprob"],
                    reverse=True,
                )[:NEXT_EOJEOL_BEAM_WIDTH]
        for state in active:
            incomplete.append(
                {
                    "raw_generated_text": decode_tokens(tokenizer, state["token_ids"]),
                    "token_ids": state["token_ids"],
                    "cumulative_logprob": state["cumulative_logprob"],
                    "termination_reason": "max_generated_tokens",
                    "step": NEXT_EOJEOL_MAX_NEW_TOKENS,
                }
            )
        best_by_surface: dict[str, dict[str, Any]] = {}
        for candidate in completed:
            previous = best_by_surface.get(candidate["surface"])
            if previous is None or candidate["cumulative_logprob"] > previous["cumulative_logprob"]:
                best_by_surface[candidate["surface"]] = candidate
        selected = sorted(
            best_by_surface.values(),
            key=lambda candidate: candidate["cumulative_logprob"],
            reverse=True,
        )[:4]
        return {
            "task": "next_eojeol_token_boundary_beam",
            "prefix": prefix,
            "conditioned_prefix": conditioned_prefix,
            "input_token_ids": base_ids,
            "status": "ok" if selected else "failure",
            "wall_time_ms": round((time.perf_counter() - started) * 1000, 3),
            "algorithm": {
                "name": "bounded_next_eojeol_token_boundary_logprob_beam",
                "beam_width": NEXT_EOJEOL_BEAM_WIDTH,
                "per_step_top_k": NEXT_EOJEOL_TOP_K,
                "max_generated_tokens": NEXT_EOJEOL_MAX_NEW_TOKENS,
                "score": "cumulative_logprob_without_length_normalization",
                "candidate_requirement": "generated text must begin with whitespace before the first eojeol",
                "completion": "whitespace-prefixed first eojeol followed by whitespace, .?!, newline, or EOS",
                "leading_punctuation": "no_eojeol",
                "leading_non_whitespace_text": "current_word_extension",
            },
            "selected_top4": selected,
            "completed_pool": completed,
            "invalid_terminations": invalid,
            "incomplete_paths": incomplete,
            "current_word_extensions": current_word_extensions,
            "no_eojeol_terminations": no_eojeol,
            "memory": memory_snapshot(torch, process, device),
        }
    except Exception as error:
        return {
            "task": "next_eojeol_token_boundary_beam",
            "prefix": prefix,
            "conditioned_prefix": conditioned_prefix,
            "input_token_ids": base_ids,
            "status": "failure",
            "wall_time_ms": round((time.perf_counter() - started) * 1000, 3),
            "error": f"{type(error).__name__}: {error}",
            "memory": memory_snapshot(torch, process, device),
        }


def runner() -> int:
    import importlib.metadata
    import psutil
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    run_id = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    run_dir = (ARTIFACT_ROOT / "runs" / run_id).resolve()
    run_dir.mkdir(parents=True, exist_ok=False)
    process = psutil.Process()
    metadata: dict[str, Any] = {
        "status": "running",
        "run_id": run_id,
        "started_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "command": [sys.executable, *sys.argv],
        "model": {"repository": REPOSITORY, "required_license": LICENSE, "trust_remote_code": False, "use_safetensors": True},
        "runtime": {
            "install_root": str(INSTALL_ROOT),
            "hf_home": os.environ.get("HF_HOME"),
            "tmp": os.environ.get("TMP"),
            "package_versions": {
                name: importlib.metadata.version(name)
                for name in ("torch", "transformers", "safetensors", "huggingface_hub", "psutil")
            },
            "torch_cuda_version": torch.version.cuda,
            "cuda_available": torch.cuda.is_available(),
        },
        "inputs": list(PREFIXES),
    }
    write_json(run_dir / "metadata.json", metadata)
    records: list[dict[str, Any]] = []
    try:
        revision, siblings, api_url = resolve_revision()
        model_dir, files = download_safe_model(revision, siblings)
        metadata["model"].update(
            {
                "revision": revision,
                "api_url": api_url,
                "local_path": str(model_dir),
                "files": files,
            }
        )
        device = "cuda" if torch.cuda.is_available() else "cpu"
        dtype = torch.float16 if device == "cuda" else torch.float32
        metadata["runtime"].update({"device": device, "dtype": str(dtype), "memory_before_load": memory_snapshot(torch, process, device)})
        if device == "cuda":
            torch.cuda.reset_peak_memory_stats()
        load_started = time.perf_counter()
        tokenizer = AutoTokenizer.from_pretrained(model_dir, local_files_only=True, trust_remote_code=False)
        model = AutoModelForCausalLM.from_pretrained(
            model_dir,
            local_files_only=True,
            trust_remote_code=False,
            use_safetensors=True,
            torch_dtype=dtype,
        ).to(device)
        model.eval()
        metadata["runtime"].update(
            {
                "model_load_wall_time_ms": round((time.perf_counter() - load_started) * 1000, 3),
                "memory_after_load": memory_snapshot(torch, process, device),
            }
        )
        metadata["runtime"]["generation_setup_validation"] = validate_generation_contract(
            model, tokenizer, torch, device
        )
        if metadata["runtime"]["generation_setup_validation"]["status"] != "ok":
            raise RuntimeError("generation setup validation failed before benchmark tasks")
        with (run_dir / "results.jsonl").open("x", encoding="utf-8") as output:
            for index, prefix in enumerate(PREFIXES, start=1):
                for record in generate_records(model, tokenizer, torch, process, device, prefix):
                    record["input_index"] = index
                    records.append(record)
                    output.write(json.dumps(record, ensure_ascii=False) + "\n")
                    output.flush()
        metadata["status"] = "completed"
        metadata["result_counts"] = {
            status: sum(item.get("status") == status for item in records)
            for status in sorted({str(item.get("status")) for item in records})
        }
        return 0
    except Exception as error:
        metadata["status"] = "setup_failure"
        metadata["error"] = f"{type(error).__name__}: {error}"
        return 1
    finally:
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(run_dir / "metadata.json", metadata)


def next_eojeol_runner() -> int:
    import importlib.metadata
    import psutil
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    run_id = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    run_dir = (ARTIFACT_ROOT / "next-eojeol-runs" / run_id).resolve()
    run_dir.mkdir(parents=True, exist_ok=False)
    process = psutil.Process()
    metadata: dict[str, Any] = {
        "status": "running",
        "run_id": run_id,
        "started_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "command": [sys.executable, *sys.argv],
        "experiment": {
            "name": "polyglot_next_eojeol_raw_logprob_beam",
            "conditioned_prefix": "prefix.rstrip() + ' '",
            "algorithm": {
                "name": "bounded_next_eojeol_logprob_beam",
                "beam_width": NEXT_EOJEOL_BEAM_WIDTH,
                "per_step_top_k": NEXT_EOJEOL_TOP_K,
                "max_generated_tokens": NEXT_EOJEOL_MAX_NEW_TOKENS,
                "score": "cumulative_logprob_without_length_normalization",
                "completion": "first non-whitespace eojeol followed by whitespace, .?!, newline, or EOS",
                "invalid_final_candidate": "U+FFFD, Unicode control, format, or surrogate character",
            },
        },
        "model": {"repository": REPOSITORY, "required_license": LICENSE, "trust_remote_code": False, "use_safetensors": True},
        "runtime": {
            "install_root": str(INSTALL_ROOT),
            "hf_home": os.environ.get("HF_HOME"),
            "tmp": os.environ.get("TMP"),
            "package_versions": {
                name: importlib.metadata.version(name)
                for name in ("torch", "transformers", "safetensors", "huggingface_hub", "psutil")
            },
            "torch_cuda_version": torch.version.cuda,
            "cuda_available": torch.cuda.is_available(),
        },
        "inputs": list(PREFIXES),
    }
    write_json(run_dir / "metadata.json", metadata)
    records: list[dict[str, Any]] = []
    try:
        revision, siblings, api_url = resolve_revision()
        model_dir, files = download_safe_model(revision, siblings)
        metadata["model"].update(
            {
                "revision": revision,
                "api_url": api_url,
                "local_path": str(model_dir),
                "files": files,
            }
        )
        device = "cuda" if torch.cuda.is_available() else "cpu"
        dtype = torch.float16 if device == "cuda" else torch.float32
        metadata["runtime"].update({"device": device, "dtype": str(dtype), "memory_before_load": memory_snapshot(torch, process, device)})
        if device == "cuda":
            torch.cuda.reset_peak_memory_stats()
        load_started = time.perf_counter()
        tokenizer = AutoTokenizer.from_pretrained(model_dir, local_files_only=True, trust_remote_code=False)
        model = AutoModelForCausalLM.from_pretrained(
            model_dir,
            local_files_only=True,
            trust_remote_code=False,
            use_safetensors=True,
            torch_dtype=dtype,
        ).to(device)
        model.eval()
        metadata["runtime"].update(
            {
                "model_load_wall_time_ms": round((time.perf_counter() - load_started) * 1000, 3),
                "memory_after_load": memory_snapshot(torch, process, device),
            }
        )
        metadata["runtime"]["generation_setup_validation"] = validate_generation_contract(
            model, tokenizer, torch, device
        )
        if metadata["runtime"]["generation_setup_validation"]["status"] != "ok":
            raise RuntimeError("generation setup validation failed before benchmark tasks")
        with (run_dir / "results.jsonl").open("x", encoding="utf-8") as output:
            for index, prefix in enumerate(PREFIXES, start=1):
                record = next_eojeol_record(model, tokenizer, torch, process, device, prefix)
                record["input_index"] = index
                records.append(record)
                output.write(json.dumps(record, ensure_ascii=False) + "\n")
                output.flush()
        metadata["status"] = "completed"
        metadata["result_counts"] = {
            status: sum(item.get("status") == status for item in records)
            for status in sorted({str(item.get("status")) for item in records})
        }
        return 0
    except Exception as error:
        metadata["status"] = "setup_failure"
        metadata["error"] = f"{type(error).__name__}: {error}"
        return 1
    finally:
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(run_dir / "metadata.json", metadata)


def token_boundary_runner() -> int:
    import importlib.metadata
    import psutil
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    run_id = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    run_dir = (ARTIFACT_ROOT / "token-boundary-runs" / run_id).resolve()
    run_dir.mkdir(parents=True, exist_ok=False)
    process = psutil.Process()
    metadata: dict[str, Any] = {
        "status": "running",
        "run_id": run_id,
        "started_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "command": [sys.executable, *sys.argv],
        "experiment": {
            "name": "polyglot_next_eojeol_tokenizer_boundary_logprob_beam",
            "conditioned_prefix": "prefix.rstrip()",
            "algorithm": {
                "name": "bounded_next_eojeol_token_boundary_logprob_beam",
                "beam_width": NEXT_EOJEOL_BEAM_WIDTH,
                "per_step_top_k": NEXT_EOJEOL_TOP_K,
                "max_generated_tokens": NEXT_EOJEOL_MAX_NEW_TOKENS,
                "score": "cumulative_logprob_without_length_normalization",
                "candidate_requirement": "generated text must begin with whitespace before the first eojeol",
                "completion": "whitespace-prefixed first eojeol followed by whitespace, .?!, newline, or EOS",
                "leading_punctuation": "no_eojeol",
                "leading_non_whitespace_text": "current_word_extension",
                "invalid_final_candidate": "U+FFFD, Unicode control, format, or surrogate character",
            },
        },
        "model": {"repository": REPOSITORY, "required_license": LICENSE, "trust_remote_code": False, "use_safetensors": True},
        "runtime": {
            "install_root": str(INSTALL_ROOT),
            "hf_home": os.environ.get("HF_HOME"),
            "tmp": os.environ.get("TMP"),
            "package_versions": {
                name: importlib.metadata.version(name)
                for name in ("torch", "transformers", "safetensors", "huggingface_hub", "psutil")
            },
            "torch_cuda_version": torch.version.cuda,
            "cuda_available": torch.cuda.is_available(),
        },
        "inputs": list(PREFIXES),
    }
    write_json(run_dir / "metadata.json", metadata)
    records: list[dict[str, Any]] = []
    try:
        revision, siblings, api_url = resolve_revision()
        model_dir, files = download_safe_model(revision, siblings)
        metadata["model"].update(
            {
                "revision": revision,
                "api_url": api_url,
                "local_path": str(model_dir),
                "files": files,
            }
        )
        device = "cuda" if torch.cuda.is_available() else "cpu"
        dtype = torch.float16 if device == "cuda" else torch.float32
        metadata["runtime"].update({"device": device, "dtype": str(dtype), "memory_before_load": memory_snapshot(torch, process, device)})
        if device == "cuda":
            torch.cuda.reset_peak_memory_stats()
        load_started = time.perf_counter()
        tokenizer = AutoTokenizer.from_pretrained(model_dir, local_files_only=True, trust_remote_code=False)
        model = AutoModelForCausalLM.from_pretrained(
            model_dir,
            local_files_only=True,
            trust_remote_code=False,
            use_safetensors=True,
            torch_dtype=dtype,
        ).to(device)
        model.eval()
        metadata["runtime"].update(
            {
                "model_load_wall_time_ms": round((time.perf_counter() - load_started) * 1000, 3),
                "memory_after_load": memory_snapshot(torch, process, device),
            }
        )
        metadata["runtime"]["generation_setup_validation"] = validate_generation_contract(
            model, tokenizer, torch, device
        )
        if metadata["runtime"]["generation_setup_validation"]["status"] != "ok":
            raise RuntimeError("generation setup validation failed before benchmark tasks")
        with (run_dir / "results.jsonl").open("x", encoding="utf-8") as output:
            for index, prefix in enumerate(PREFIXES, start=1):
                record = token_boundary_eojeol_record(model, tokenizer, torch, process, device, prefix)
                record["input_index"] = index
                records.append(record)
                output.write(json.dumps(record, ensure_ascii=False) + "\n")
                output.flush()
        metadata["status"] = "completed"
        metadata["result_counts"] = {
            status: sum(item.get("status") == status for item in records)
            for status in sorted({str(item.get("status")) for item in records})
        }
        return 0
    except Exception as error:
        metadata["status"] = "setup_failure"
        metadata["error"] = f"{type(error).__name__}: {error}"
        return 1
    finally:
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(run_dir / "metadata.json", metadata)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runner", action="store_true", help="run inside the isolated Python 3.12 venv")
    parser.add_argument("--next-eojeol", action="store_true", help="bootstrap and run the next-eojeol beam experiment")
    parser.add_argument("--next-eojeol-runner", action="store_true", help="run the next-eojeol beam inside the isolated Python 3.12 venv")
    parser.add_argument("--token-boundary", action="store_true", help="bootstrap and run the tokenizer-boundary next-eojeol experiment")
    parser.add_argument("--token-boundary-runner", action="store_true", help="run the tokenizer-boundary experiment inside the isolated Python 3.12 venv")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.token_boundary_runner:
        return token_boundary_runner()
    if args.next_eojeol_runner:
        return next_eojeol_runner()
    if args.runner:
        return runner()
    return bootstrap(next_eojeol=args.next_eojeol, token_boundary=args.token_boundary)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"polyglot benchmark failed before runner setup: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
