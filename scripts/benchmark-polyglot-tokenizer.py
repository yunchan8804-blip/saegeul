#!/usr/bin/env python3
"""Create an experimental vocabulary-only GGUF and fixed Hugging Face tokenizer fixtures."""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any


LLAMA_CPP_SOURCE = Path(r"C:\Users\encep\AppData\Local\SaegeulBench\llama.cpp-a279d0f")
LLAMA_CPP_SHA = "a279d0f0f4e746d1ef3429d8e9d02d2990b2daa7"
INSTALL_ROOT = Path(r"C:\Users\encep\AppData\Local\SaegeulBench\polyglot-1.3b")
MODEL_SHA = "557e162cf6e944fdbae05bab2e45d066a125eacb"
MODEL_DIR = INSTALL_ROOT / "model" / MODEL_SHA
OUTPUT_FILE = INSTALL_ROOT / "gguf" / "vocab-only.gguf"
ARTIFACT_ROOT = Path(".artifacts/polyglot-benchmark/tokenizer-adapter")
NATIVE_TOKENIZE = Path(r"C:\Users\encep\AppData\Local\SaegeulBench\llama-build-windows\bin\llama-tokenize.exe")
QUANTIZE = Path(r"C:\Users\encep\AppData\Local\SaegeulBench\llama-build-windows\bin\llama-quantize.exe")
F16_OUTPUT_FILE = INSTALL_ROOT / "gguf" / "polyglot-f16.gguf"
Q4_OUTPUT_FILE = INSTALL_ROOT / "gguf" / "polyglot-Q4_K_M.gguf"
TOKENIZER_SHA256 = "60e8bd123994badd563a603bce1a319dd7f5dc4798a476c2d88c671766db2f24"
PRE_TOKENIZER_SHA256 = "51cee3903cf47b492d5d73b1f299c20d54d5facc5c77eb090b08b1cf794cc9e0"
EXPECTED_PRE_TOKENIZER = {
    "type": "Sequence",
    "pretokenizers": [
        {
            "type": "Split",
            "pattern": {"String": "ೱ"},
            "behavior": "Removed",
            "invert": False,
        },
        {
            "type": "ByteLevel",
            "add_prefix_space": False,
            "trim_offsets": True,
            "use_regex": True,
        },
    ],
}
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
EXTRA_FIXTURES = (
    ("korean_sentence", "오늘 회의가 끝나면 연락해 주세요."),
    ("josa", "나는 학교에서 친구에게 책을 주었다."),
    ("compatibility_jamo", "ㅏㅑㅓㅕ ㄱㄲㄴ 한글 자모"),
    ("combined_jamo", "각 난"),
    ("repeated_spaces", "반복    공백"),
    ("tab", "앞\t뒤"),
    ("newline", "첫째\n둘째"),
    ("emoji", "안녕🙂👨‍👩‍👧‍👦"),
    ("english", "Hello, world! GPT-2"),
    ("numbers", "2026년 09월 07일 123.45"),
    ("url", "https://example.com/a?b=1&c=한글"),
    ("combined_unicode", "café cafe\u0301 Å A\u030A"),
    ("special_token_only", "<|endoftext|>"),
    ("special_token_embedded", "앞<|endoftext|>뒤"),
    ("split_between", "앞ೱ뒤"),
    ("split_before", "ೱ앞"),
    ("split_after", "뒤ೱ"),
    ("split_repeated", "앞ೱೱ뒤"),
    ("split_spaces", "앞 ೱ 뒤"),
    ("split_english_apostrophe", "canೱ't"),
    ("split_korean", "한ೱ글"),
    ("split_ascii", "aೱb"),
    ("split_emoji", "😀ೱ🙂"),
    ("split_special_internal", "앞ೱ<|endoftext|>ೱ뒤"),
    ("split_special_before", "<|endoftext|>ೱ뒤"),
    ("split_special_after", "앞ೱ<|endoftext|>"),
    ("split_special_pair", "<|endoftext|>ೱ<|sep|>"),
    ("split_only", "ೱ"),
    ("empty", ""),
)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def write_json(path: Path, value: dict[str, Any] | list[dict[str, Any]]) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def source_head() -> str:
    completed = subprocess.run(
        ["git", "-C", str(LLAMA_CPP_SOURCE), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


def assert_source_clean() -> None:
    completed = subprocess.run(
        ["git", "-C", str(LLAMA_CPP_SOURCE), "status", "--short"],
        check=True,
        capture_output=True,
        text=True,
    )
    if completed.stdout:
        raise RuntimeError("llama.cpp source checkout is not clean")


def model_weight_manifest() -> list[dict[str, Any]]:
    paths = [MODEL_DIR / "model.safetensors.index.json", *sorted(MODEL_DIR.glob("model-*.safetensors"))]
    if len(paths) != 4 or not all(path.is_file() for path in paths):
        raise RuntimeError("expected Polyglot safetensors weight files are missing")
    return [
        {
            "filename": path.name,
            "bytes": path.stat().st_size,
            "sha256": sha256_file(path),
        }
        for path in paths
    ]


def assert_inputs() -> dict[str, Any]:
    if source_head() != LLAMA_CPP_SHA:
        raise RuntimeError("llama.cpp source SHA does not match the experimental adapter contract")
    assert_source_clean()
    if not MODEL_DIR.is_dir():
        raise RuntimeError(f"model directory is missing: {MODEL_DIR}")
    tokenizer_path = MODEL_DIR / "tokenizer.json"
    tokenizer_bytes = tokenizer_path.read_bytes()
    if sha256_bytes(tokenizer_bytes) != TOKENIZER_SHA256:
        raise RuntimeError("tokenizer.json SHA-256 does not match the experimental adapter contract")
    tokenizer_data = json.loads(tokenizer_bytes)
    pre_tokenizer = tokenizer_data.get("pre_tokenizer")
    if pre_tokenizer != EXPECTED_PRE_TOKENIZER:
        raise RuntimeError("tokenizer.json pre_tokenizer structure does not match the experimental adapter contract")
    if sha256_bytes(canonical_json(pre_tokenizer)) != PRE_TOKENIZER_SHA256:
        raise RuntimeError("tokenizer.json pre_tokenizer SHA-256 does not match the experimental adapter contract")
    return {
        "tokenizer_json": str(tokenizer_path),
        "tokenizer_sha256": TOKENIZER_SHA256,
        "pre_tokenizer": pre_tokenizer,
        "pre_tokenizer_sha256": PRE_TOKENIZER_SHA256,
    }


def fixture_cases() -> list[dict[str, str]]:
    cases: list[dict[str, str]] = []
    for index, prefix in enumerate(PREFIXES, start=1):
        cases.append({"name": f"prefix_{index:02d}_no_space", "text": prefix})
        cases.append({"name": f"prefix_{index:02d}_trailing_space", "text": prefix + " "})
    cases.extend({"name": name, "text": text} for name, text in EXTRA_FIXTURES)
    if len(cases) > 60:
        raise RuntimeError(f"fixture case limit exceeded: {len(cases)}")
    return cases


def write_hf_fixtures(path: Path) -> dict[str, Any]:
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(
        MODEL_DIR,
        local_files_only=True,
        trust_remote_code=False,
    )
    records = []
    for case in fixture_cases():
        records.append(
            {
                **case,
                "token_ids": tokenizer.encode(case["text"], add_special_tokens=False),
            }
        )
    write_json(path, records)
    return {
        "path": str(path),
        "sha256": sha256_file(path),
        "case_count": len(records),
        "add_special_tokens": False,
        "trust_remote_code": False,
        "local_files_only": True,
    }


def load_converter() -> Any:
    converter_path = LLAMA_CPP_SOURCE / "convert_hf_to_gguf.py"
    spec = importlib.util.spec_from_file_location("saegeul_experimental_hf_to_gguf", converter_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load converter module: {converter_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def run_converter_adapter(output_file: Path, vocab_only: bool) -> None:
    converter = load_converter()

    class ExperimentalGPTNeoXModel(converter.GPTNeoXModel):
        model_arch = converter.GPTNeoXModel.model_arch

        def get_vocab_base_pre(self, tokenizer: Any) -> str:
            return "gpt-2"

    converter.ModelBase._model_classes[converter.ModelType.TEXT]["GPTNeoXForCausalLM"] = ExperimentalGPTNeoXModel
    original_argv = sys.argv
    try:
        arguments = [str(LLAMA_CPP_SOURCE / "convert_hf_to_gguf.py")]
        if vocab_only:
            arguments.append("--vocab-only")
        arguments.extend(["--outtype", "f16", "--outfile", str(output_file), str(MODEL_DIR)])
        sys.argv = arguments
        converter.main()
    finally:
        sys.argv = original_argv


def run_vocab_only_adapter() -> None:
    run_converter_adapter(OUTPUT_FILE, vocab_only=True)


def native_tokenize_fragment(fragment: str) -> dict[str, Any]:
    completed = subprocess.run(
        [
            str(NATIVE_TOKENIZE),
            "-m",
            str(OUTPUT_FILE),
            "--ids",
            "--no-bos",
            "--no-escape",
            "--stdin",
            "--log-disable",
        ],
        input=fragment.encode("utf-8"),
        capture_output=True,
    )
    stdout = completed.stdout.decode("utf-8")
    stderr = completed.stderr.decode("utf-8")
    record: dict[str, Any] = {
        "fragment": fragment,
        "exit_code": completed.returncode,
        "stdout": stdout,
        "stderr": stderr,
    }
    if completed.returncode != 0:
        raise RuntimeError(f"llama-tokenize failed for fragment {fragment!r}: exit {completed.returncode}")
    try:
        token_ids = json.loads(stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"llama-tokenize returned non-JSON IDs for fragment {fragment!r}: {error}") from error
    if not isinstance(token_ids, list) or not all(isinstance(token_id, int) for token_id in token_ids):
        raise RuntimeError(f"llama-tokenize returned invalid token IDs for fragment {fragment!r}")
    record["token_ids"] = token_ids
    return record


def segmented_native_tokenize(text: str) -> dict[str, Any]:
    fragments = text.split("ೱ")
    fragment_records = [native_tokenize_fragment(fragment) for fragment in fragments]
    return {
        "fragments": fragments,
        "fragment_results": fragment_records,
        "token_ids": [
            token_id
            for fragment_record in fragment_records
            for token_id in fragment_record["token_ids"]
        ],
    }


def adapter_sidecar(output_file: Path, output_sha256: str, output_bytes: int, weights: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "status": "unverified_tokenizer_adapter",
        "adapter_contract": "experimental inference adapter; not a gpt2-equivalence or supported-tokenizer declaration",
        "inference_requires_preprocessing": "split U+0CF1, drop delimiters, tokenize fragments independently, concatenate token IDs",
        "delimiter_code_point": "U+0CF1",
        "source_sha": LLAMA_CPP_SHA,
        "model_sha": MODEL_SHA,
        "output_file": str(output_file),
        "output_bytes": output_bytes,
        "output_sha256": output_sha256,
        "input_weight_manifest": weights,
    }


def write_adapter_sidecar(output_file: Path, output_sha256: str, output_bytes: int, weights: list[dict[str, Any]]) -> Path:
    sidecar = output_file.with_suffix(output_file.suffix + ".adapter-metadata.json")
    write_json(sidecar, adapter_sidecar(output_file, output_sha256, output_bytes, weights))
    return sidecar


def fullweights_runner() -> int:
    if F16_OUTPUT_FILE.exists():
        raise RuntimeError(f"refusing to overwrite existing F16 GGUF: {F16_OUTPUT_FILE}")
    if Q4_OUTPUT_FILE.exists():
        raise RuntimeError(f"refusing to overwrite existing Q4_K_M GGUF: {Q4_OUTPUT_FILE}")
    if not QUANTIZE.is_file():
        raise RuntimeError(f"llama-quantize executable is missing: {QUANTIZE}")
    run_id = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    run_dir = (Path(".artifacts/polyglot-benchmark/gguf-adapter-conversion") / run_id).resolve()
    run_dir.mkdir(parents=True, exist_ok=False)
    metadata: dict[str, Any] = {
        "status": "running",
        "run_id": run_id,
        "started_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": {
            "repository": "https://github.com/ggml-org/llama.cpp",
            "source_sha": LLAMA_CPP_SHA,
            "path": str(LLAMA_CPP_SOURCE),
        },
        "model": {
            "repository": "EleutherAI/polyglot-ko-1.3b",
            "model_sha": MODEL_SHA,
            "path": str(MODEL_DIR),
            "trust_remote_code": False,
        },
        "adapter": {
            "scope": "process-local experimental GPTNeoXModel subclass",
            "get_vocab_base_pre": "gpt-2",
            "inference_requires_preprocessing": "split U+0CF1, drop delimiters, tokenize fragments independently, concatenate token IDs",
            "adapter_contract": "experimental inference adapter; not a gpt2-equivalence or supported-tokenizer declaration",
        },
        "stages": [],
    }
    write_json(run_dir / "metadata.json", metadata)
    try:
        metadata["tokenizer_assertions"] = assert_inputs()
        weights_before = model_weight_manifest()
        metadata["input_weight_manifest_before"] = weights_before
        F16_OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
        f16_log = run_dir / "f16-conversion.log"
        f16_stage = {
            "name": "f16_adapter_conversion",
            "command": [
                str(LLAMA_CPP_SOURCE / "convert_hf_to_gguf.py"),
                "--outtype",
                "f16",
                "--outfile",
                str(F16_OUTPUT_FILE),
                str(MODEL_DIR),
            ],
            "log": str(f16_log),
            "exit_code": None,
        }
        metadata["stages"].append(f16_stage)
        write_json(run_dir / "metadata.json", metadata)
        try:
            with f16_log.open("w", encoding="utf-8") as log:
                with contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
                    run_converter_adapter(F16_OUTPUT_FILE, vocab_only=False)
            f16_stage["exit_code"] = 0
        except BaseException as error:
            f16_stage["exit_code"] = 1
            f16_stage["error"] = f"{type(error).__name__}: {error}"
            raise
        if not F16_OUTPUT_FILE.is_file():
            raise RuntimeError("F16 converter completed without an output file")
        f16_stage["output"] = {
            "path": str(F16_OUTPUT_FILE),
            "bytes": F16_OUTPUT_FILE.stat().st_size,
            "sha256": sha256_file(F16_OUTPUT_FILE),
        }
        weights_after_f16 = model_weight_manifest()
        if weights_after_f16 != weights_before:
            raise RuntimeError("input safetensors changed during F16 conversion")
        f16_stage["adapter_sidecar"] = str(
            write_adapter_sidecar(
                F16_OUTPUT_FILE,
                f16_stage["output"]["sha256"],
                f16_stage["output"]["bytes"],
                weights_after_f16,
            )
        )
        write_json(run_dir / "metadata.json", metadata)
        q4_log = run_dir / "q4-k-m-quantize.log"
        q4_stage = {
            "name": "q4_k_m_quantization",
            "command": [str(QUANTIZE), str(F16_OUTPUT_FILE), str(Q4_OUTPUT_FILE), "Q4_K_M", "8"],
            "log": str(q4_log),
            "exit_code": None,
        }
        metadata["stages"].append(q4_stage)
        write_json(run_dir / "metadata.json", metadata)
        with q4_log.open("w", encoding="utf-8") as log:
            completed = subprocess.run(q4_stage["command"], stdout=log, stderr=subprocess.STDOUT)
        q4_stage["exit_code"] = completed.returncode
        if completed.returncode != 0:
            raise RuntimeError(f"llama-quantize failed with exit code {completed.returncode}")
        if not Q4_OUTPUT_FILE.is_file():
            raise RuntimeError("llama-quantize completed without an output file")
        q4_stage["output"] = {
            "path": str(Q4_OUTPUT_FILE),
            "bytes": Q4_OUTPUT_FILE.stat().st_size,
            "sha256": sha256_file(Q4_OUTPUT_FILE),
        }
        weights_after_q4 = model_weight_manifest()
        if weights_after_q4 != weights_before:
            raise RuntimeError("input safetensors changed during Q4_K_M quantization")
        q4_stage["adapter_sidecar"] = str(
            write_adapter_sidecar(
                Q4_OUTPUT_FILE,
                q4_stage["output"]["sha256"],
                q4_stage["output"]["bytes"],
                weights_after_q4,
            )
        )
        metadata["input_weight_manifest_after"] = weights_after_q4
        metadata["status"] = "unverified_tokenizer_adapter"
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(run_dir / "metadata.json", metadata)
        return 0
    except BaseException as error:
        metadata["status"] = "failed"
        metadata["error"] = f"{type(error).__name__}: {error}"
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(run_dir / "metadata.json", metadata)
        raise


def comparison_runner() -> int:
    if not OUTPUT_FILE.is_file():
        raise RuntimeError(f"vocabulary-only GGUF is missing: {OUTPUT_FILE}")
    if not NATIVE_TOKENIZE.is_file():
        raise RuntimeError(f"llama-tokenize executable is missing: {NATIVE_TOKENIZE}")
    run_id = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    run_dir = (ARTIFACT_ROOT / "segmented-comparison" / run_id).resolve()
    run_dir.mkdir(parents=True, exist_ok=False)
    metadata: dict[str, Any] = {
        "status": "unverified_tokenizer_adapter",
        "run_id": run_id,
        "started_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": {
            "repository": "https://github.com/ggml-org/llama.cpp",
            "source_sha": LLAMA_CPP_SHA,
            "path": str(LLAMA_CPP_SOURCE),
        },
        "model": {
            "repository": "EleutherAI/polyglot-ko-1.3b",
            "model_sha": MODEL_SHA,
            "path": str(MODEL_DIR),
            "trust_remote_code": False,
        },
        "vocab_only_gguf": {
            "path": str(OUTPUT_FILE),
            "bytes": OUTPUT_FILE.stat().st_size,
            "sha256": sha256_file(OUTPUT_FILE),
        },
        "native_tokenizer": {
            "path": str(NATIVE_TOKENIZE),
            "arguments": ["--ids", "--no-bos", "--no-escape", "--stdin", "--log-disable"],
            "parse_special": True,
        },
        "required_preprocessing": "split U+0CF1, drop delimiters, tokenize fragments independently, concatenate token IDs",
        "adapter_contract": "experimental inference adapter; not a gpt2-equivalence or supported-tokenizer declaration",
    }
    write_json(run_dir / "metadata.json", metadata)
    try:
        metadata["tokenizer_assertions"] = assert_inputs()
        metadata["hf_fixtures"] = write_hf_fixtures(run_dir / "hf-tokenizer-fixtures.json")
        fixtures = json.loads((run_dir / "hf-tokenizer-fixtures.json").read_text(encoding="utf-8"))
        results = []
        for fixture in fixtures:
            native = segmented_native_tokenize(fixture["text"])
            results.append(
                {
                    "name": fixture["name"],
                    "text": fixture["text"],
                    "expected_hf_token_ids": fixture["token_ids"],
                    "preprocessing_fragments": native["fragments"],
                    "native_fragment_results": native["fragment_results"],
                    "segmented_native_token_ids": native["token_ids"],
                    "match": fixture["token_ids"] == native["token_ids"],
                }
            )
        write_json(run_dir / "segmented-native-comparison.json", results)
        metadata["result_counts"] = {
            "total": len(results),
            "match": sum(result["match"] for result in results),
            "mismatch": sum(not result["match"] for result in results),
        }
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(run_dir / "metadata.json", metadata)
        return 0
    except BaseException as error:
        metadata["error"] = f"{type(error).__name__}: {error}"
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(run_dir / "metadata.json", metadata)
        raise


def runner() -> int:
    if OUTPUT_FILE.exists():
        raise RuntimeError(f"refusing to overwrite existing vocabulary-only GGUF: {OUTPUT_FILE}")
    run_id = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    run_dir = (ARTIFACT_ROOT / run_id).resolve()
    run_dir.mkdir(parents=True, exist_ok=False)
    metadata: dict[str, Any] = {
        "status": "unverified_tokenizer_adapter",
        "run_id": run_id,
        "started_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": {
            "repository": "https://github.com/ggml-org/llama.cpp",
            "source_sha": LLAMA_CPP_SHA,
            "path": str(LLAMA_CPP_SOURCE),
        },
        "model": {
            "repository": "EleutherAI/polyglot-ko-1.3b",
            "model_sha": MODEL_SHA,
            "path": str(MODEL_DIR),
            "trust_remote_code": False,
        },
        "adapter": {
            "scope": "process-local experimental GPTNeoXModel subclass",
            "get_vocab_base_pre": "gpt-2",
            "converter_cli_args": ["--vocab-only", "--outfile", str(OUTPUT_FILE), str(MODEL_DIR)],
        },
    }
    write_json(run_dir / "metadata.json", metadata)
    try:
        metadata["tokenizer_assertions"] = assert_inputs()
        metadata["hf_fixtures"] = write_hf_fixtures(run_dir / "hf-tokenizer-fixtures.json")
        OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
        run_vocab_only_adapter()
        if not OUTPUT_FILE.is_file():
            raise RuntimeError("converter completed without a vocabulary-only GGUF output")
        metadata["vocab_only_gguf"] = {
            "path": str(OUTPUT_FILE),
            "bytes": OUTPUT_FILE.stat().st_size,
            "sha256": sha256_file(OUTPUT_FILE),
        }
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_json(run_dir / "metadata.json", metadata)
        return 0
    except BaseException as error:
        metadata["error"] = f"{type(error).__name__}: {error}"
        metadata["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        metadata["vocab_only_gguf"] = {
            "path": str(OUTPUT_FILE),
            "exists": OUTPUT_FILE.exists(),
        }
        write_json(run_dir / "metadata.json", metadata)
        raise


def bootstrap(compare: bool = False, fullweights: bool = False) -> int:
    python = INSTALL_ROOT / "venv" / "Scripts" / "python.exe"
    if not python.is_file():
        raise RuntimeError(f"isolated Polyglot venv is missing: {python}")
    environment = os.environ.copy()
    environment.update(
        {
            "HF_HOME": str(INSTALL_ROOT / "hf-home"),
            "TMP": str(INSTALL_ROOT / "tmp"),
            "TEMP": str(INSTALL_ROOT / "tmp"),
            "PYTHONUTF8": "1",
        }
    )
    runner_argument = "--fullweights-runner" if fullweights else "--compare-runner" if compare else "--runner"
    completed = subprocess.run([str(python), str(Path(__file__).resolve()), runner_argument], env=environment)
    return completed.returncode


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runner", action="store_true")
    parser.add_argument("--compare", action="store_true")
    parser.add_argument("--compare-runner", action="store_true")
    parser.add_argument("--fullweights", action="store_true")
    parser.add_argument("--fullweights-runner", action="store_true")
    args = parser.parse_args()
    if args.fullweights_runner:
        return fullweights_runner()
    if args.compare_runner:
        return comparison_runner()
    return runner() if args.runner else bootstrap(compare=args.compare, fullweights=args.fullweights)


if __name__ == "__main__":
    raise SystemExit(main())
