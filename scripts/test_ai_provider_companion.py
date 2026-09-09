# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("ai-provider-companion.py")
SPEC = importlib.util.spec_from_file_location("ai_provider_companion", MODULE_PATH)
companion = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = companion
SPEC.loader.exec_module(companion)


class LocalOAuthStateTest(unittest.TestCase):
    def authorization_query(self, verifier: str) -> dict[str, list[str]]:
        return {
            "response_type": ["code"],
            "client_id": [companion.OAUTH_CLIENT_ID],
            "redirect_uri": [companion.DEFAULT_REDIRECT_URI],
            "code_challenge_method": ["S256"],
            "code_challenge": [companion.pkce_challenge(verifier)],
            "state": ["phone-state"],
            "scope": [companion.OAUTH_SCOPES],
        }

    def test_multiple_allowed_redirect_uris(self):
        oauth = companion.LocalOAuthState(
            companion.DEFAULT_REDIRECT_URI,
            allowed_redirect_uris=companion.ALLOWED_REDIRECT_URIS,
        )
        for redirect_uri in companion.ALLOWED_REDIRECT_URIS:
            verifier = "x" * 64
            query = self.authorization_query(verifier)
            query["redirect_uri"] = [redirect_uri]
            request_id = oauth.begin_authorization(query)
            redirect = oauth.finish_authorization(request_id, True)
            self.assertTrue(redirect.startswith(redirect_uri))
            parsed = companion.urllib.parse.urlsplit(redirect)
            code = companion.urllib.parse.parse_qs(parsed.query)["code"][0]
            tokens = oauth.exchange(
                {
                    "grant_type": ["authorization_code"],
                    "client_id": [companion.OAUTH_CLIENT_ID],
                    "redirect_uri": [redirect_uri],
                    "code": [code],
                    "code_verifier": [verifier],
                }
            )
            self.assertTrue(oauth.accepts(f"Bearer {tokens['access_token']}"))

    def test_pkce_authorization_code_refresh_and_revoke(self):
        oauth = companion.LocalOAuthState(companion.DEFAULT_REDIRECT_URI)
        verifier = "v" * 64
        request_id = oauth.begin_authorization(self.authorization_query(verifier))
        redirect = oauth.finish_authorization(request_id, True)
        query = companion.urllib.parse.parse_qs(companion.urllib.parse.urlsplit(redirect).query)

        tokens = oauth.exchange(
            {
                "grant_type": ["authorization_code"],
                "client_id": [companion.OAUTH_CLIENT_ID],
                "redirect_uri": [companion.DEFAULT_REDIRECT_URI],
                "code": [query["code"][0]],
                "code_verifier": [verifier],
            }
        )

        self.assertTrue(oauth.accepts(f"Bearer {tokens['access_token']}"))
        refreshed = oauth.exchange(
            {
                "grant_type": ["refresh_token"],
                "client_id": [companion.OAUTH_CLIENT_ID],
                "refresh_token": [tokens["refresh_token"]],
            }
        )
        self.assertNotEqual(tokens["refresh_token"], refreshed["refresh_token"])
        oauth.revoke(refreshed["access_token"])
        self.assertFalse(oauth.accepts(f"Bearer {refreshed['access_token']}"))

    def test_wrong_pkce_verifier_fails_closed(self):
        oauth = companion.LocalOAuthState(companion.DEFAULT_REDIRECT_URI)
        request_id = oauth.begin_authorization(self.authorization_query("a" * 64))
        redirect = oauth.finish_authorization(request_id, True)
        code = companion.urllib.parse.parse_qs(
            companion.urllib.parse.urlsplit(redirect).query
        )["code"][0]
        form = {
            "grant_type": ["authorization_code"],
            "client_id": [companion.OAUTH_CLIENT_ID],
            "redirect_uri": [companion.DEFAULT_REDIRECT_URI],
            "code": [code],
            "code_verifier": ["b" * 64],
        }
        with self.assertRaisesRegex(ValueError, "invalid_grant"):
            oauth.exchange(form)
        with self.assertRaisesRegex(ValueError, "invalid_grant"):
            oauth.exchange(form)

    def test_companion_grants_survive_restart_in_encrypted_store(self):
        transform = lambda data: bytes(value ^ 0xA5 for value in data)
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            companion, "protect_local_data", side_effect=transform
        ), mock.patch.object(companion, "unprotect_local_data", side_effect=transform):
            state_path = Path(directory) / "oauth.bin"
            oauth = companion.LocalOAuthState(companion.DEFAULT_REDIRECT_URI, state_path)
            verifier = "p" * 64
            request_id = oauth.begin_authorization(self.authorization_query(verifier))
            redirect = oauth.finish_authorization(request_id, True)
            code = companion.urllib.parse.parse_qs(
                companion.urllib.parse.urlsplit(redirect).query
            )["code"][0]
            tokens = oauth.exchange(
                {
                    "grant_type": ["authorization_code"],
                    "client_id": [companion.OAUTH_CLIENT_ID],
                    "redirect_uri": [companion.DEFAULT_REDIRECT_URI],
                    "code": [code],
                    "code_verifier": [verifier],
                }
            )

            restarted = companion.LocalOAuthState(companion.DEFAULT_REDIRECT_URI, state_path)

            self.assertTrue(restarted.accepts(f"Bearer {tokens['access_token']}"))
            self.assertNotIn(tokens["access_token"].encode(), state_path.read_bytes())
            refreshed = restarted.exchange(
                {
                    "grant_type": ["refresh_token"],
                    "client_id": [companion.OAUTH_CLIENT_ID],
                    "refresh_token": [tokens["refresh_token"]],
                }
            )
            self.assertTrue(restarted.accepts(f"Bearer {refreshed['access_token']}"))


class CliBoundaryTest(unittest.TestCase):
    def test_public_manifest_verification_retries_transient_tunnel_propagation(self):
        failures = iter(
            (
                ValueError("the provider returned HTTP 404"),
                ValueError("the provider returned HTTP 502"),
                {"protocol_version": 1},
            )
        )
        delays = []

        def verify(_url):
            result = next(failures)
            if isinstance(result, Exception):
                raise result
            return result

        manifest = companion.verify_public_manifest_with_retry(
            "https://computer.example/.well-known/saegeul-ai-provider",
            attempts=3,
            initial_delay_seconds=0.25,
            verify=verify,
            sleep=delays.append,
        )

        self.assertEqual({"protocol_version": 1}, manifest)
        self.assertEqual([0.25, 0.5], delays)

    def test_public_manifest_verification_fails_fast_on_invalid_contract(self):
        delays = []
        with self.assertRaisesRegex(ValueError, "unsupported discovery protocol"):
            companion.verify_public_manifest_with_retry(
                "https://computer.example/.well-known/saegeul-ai-provider",
                verify=lambda _url: (_ for _ in ()).throw(
                    ValueError("the provider uses an unsupported discovery protocol")
                ),
                sleep=delays.append,
            )
        self.assertEqual([], delays)

    def test_public_origin_is_https_origin_only(self):
        self.assertEqual(
            "https://keyboard.example:9443",
            companion.public_origin(" https://keyboard.example:9443/ "),
        )
        for value in (
            "http://keyboard.example",
            "https://user:secret@keyboard.example",
            "https://keyboard.example/proxy",
            "https://keyboard.example?token=secret",
            "https://keyboard.example/#fragment",
            "https://keyboard.example:invalid",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                companion.public_origin(value)

    def test_cli_environment_drops_all_api_and_oauth_overrides(self):
        keys = (
            "OPENAI_API_KEY",
            "CODEX_API_KEY",
            "CODEX_ACCESS_TOKEN",
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN",
            "CLAUDE_CODE_OAUTH_TOKEN",
        )
        with mock.patch.dict(os.environ, {key: "secret" for key in keys}):
            environment = companion.cli_environment()
        for key in keys:
            self.assertNotIn(key, environment)

    def test_suggestion_output_is_strict_and_normalized(self):
        self.assertEqual(
            '{"suggestions":["첫째","둘째"]}',
            companion.normalize_suggestions(
                '```json\n{"suggestions": [" 첫째 ", "둘째"]}\n```',
                expected_suggestions=2,
            ),
        )
        # A trailing stray character (observed from the agy text renderer) after valid JSON must
        # still parse via outermost-object extraction, not fail as invalid JSON.
        self.assertEqual(
            '{"suggestions":["{\\"nodes\\":[]}"]}',
            companion.normalize_suggestions(
                '{"suggestions": ["{\\"nodes\\":[]}"]}\\',
                expected_suggestions=1,
            ),
        )
        # A model that returns the single result object directly (no "suggestions" wrapper) is
        # wrapped into one suggestion so a graph-enrichment reply is not rejected.
        graph = '{"nodes":[{"id":"회의","tags":["업무"],"w":2.0}],"edges":[],"topics":[]}'
        wrapped = companion.normalize_suggestions(graph, expected_suggestions=1)
        self.assertEqual([graph], json.loads(wrapped)["suggestions"])
        # But a bare object still fails when the action expected more than one suggestion.
        with self.assertRaises(RuntimeError):
            companion.normalize_suggestions(graph, expected_suggestions=3)
        # Leading prose before the JSON object is tolerated too.
        self.assertEqual(
            '{"suggestions":["가"]}',
            companion.normalize_suggestions(
                '다음은 결과입니다: {"suggestions": ["가"]} 이상입니다.',
                expected_suggestions=1,
            ),
        )
        with self.assertRaises(RuntimeError):
            companion.normalize_suggestions("not json")
        with self.assertRaises(RuntimeError):
            companion.normalize_suggestions('{"suggestions": []}')
        with self.assertRaises(RuntimeError):
            companion.normalize_suggestions(
                '{"suggestions": ["첫째", "둘째"]}',
                expected_suggestions=3,
            )
        with self.assertRaises(RuntimeError):
            companion.normalize_suggestions(
                '{"suggestions": ["같음", "같음", "다름"]}',
                expected_suggestions=3,
            )

    def test_suggestion_count_uses_structured_schema_with_instruction_fallback(self):
        structured = {
            "text": {
                "format": {
                    "schema": {
                        "properties": {
                            "suggestions": {"minItems": 3, "maxItems": 3}
                        }
                    }
                }
            }
        }
        self.assertEqual(
            3,
            companion.requested_suggestion_count(structured, "irrelevant"),
        )
        self.assertEqual(
            1,
            companion.requested_suggestion_count(
                {},
                "Return exactly 1 suggestion(s). Do not use Markdown.",
            ),
        )
        with self.assertRaises(ValueError):
            companion.requested_suggestion_count({}, "Return some suggestions")

    def test_continuation_abstention_contract_requires_marker_and_exact_schema(self):
        request = {
            "text": {
                "format": {
                    "name": companion.CONTINUATION_ABSTENTION_FORMAT,
                    "schema": {
                        "properties": {
                            "suggestions": {"minItems": 0, "maxItems": 3}
                        }
                    },
                }
            }
        }
        self.assertEqual(
            (3, True),
            companion.requested_suggestion_contract(request, "Return exactly 3 suggestion(s)."),
        )

        for minimum, maximum in ((1, 3), (0, 2), (0, "3")):
            invalid = json.loads(json.dumps(request))
            invalid["text"]["format"]["schema"]["properties"]["suggestions"] = {
                "minItems": minimum,
                "maxItems": maximum,
            }
            with self.subTest(minimum=minimum, maximum=maximum), self.assertRaises(ValueError):
                companion.requested_suggestion_contract(invalid, "Return exactly 3 suggestion(s).")

        legacy = json.loads(json.dumps(request))
        legacy["text"]["format"].pop("name")
        with self.assertRaises(ValueError):
            companion.requested_suggestion_contract(legacy, "Return exactly 3 suggestion(s).")

        exact_legacy = {
            "text": {
                "format": {
                    "schema": {
                        "properties": {
                            "suggestions": {"minItems": 2, "maxItems": 2}
                        }
                    }
                }
            }
        }
        self.assertEqual(
            (2, False),
            companion.requested_suggestion_contract(exact_legacy, "irrelevant"),
        )

    def test_continuation_abstention_normalization_allows_only_valid_zero_to_three_array(self):
        for output, expected in (
            ('{"suggestions": []}', '{"suggestions":[]}'),
            ('{"suggestions": ["하나"]}', '{"suggestions":["하나"]}'),
            ('{"suggestions": ["하나", "둘"]}', '{"suggestions":["하나","둘"]}'),
            ('{"suggestions": ["하나", "둘", "셋"]}', '{"suggestions":["하나","둘","셋"]}'),
        ):
            with self.subTest(output=output):
                self.assertEqual(
                    expected,
                    companion.normalize_suggestions(
                        output,
                        expected_suggestions=3,
                        allow_partial_suggestions=True,
                    ),
                )
        for output in (
            '설명 {"suggestions": []} 끝',
            '```json\n{"suggestions": []}\n```',
            '{"suggestions": []',
            '{"suggestions": "not an array"}',
            '{"suggestions": [""]}',
            '{"suggestions": [null]}',
            '{"suggestions": [1]}',
            '{"suggestions": [" "]}',
            '{"suggestions": ["같음", "같음"]}',
            '{"suggestions": ["하나", "둘", "셋", "넷"]}',
            '{"nodes": [], "edges": []}',
        ):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                companion.normalize_suggestions(
                    output,
                    expected_suggestions=3,
                    allow_partial_suggestions=True,
                )

    def test_continuation_abstention_prompt_removes_exact_count_force(self):
        prompt = companion.cli_prompt(
            "Return exactly 3 suggestion(s). Do not use Markdown.",
            "내가 뭘",
            allow_partial_suggestions=True,
        )
        self.assertNotIn("Return exactly 3 suggestion(s).", prompt)
        self.assertIn("Return zero to three suggestion(s).", prompt)
        self.assertIn("An empty suggestions array is valid", prompt)

    def test_gateway_continuation_abstention_routes_contract_and_rejects_malformed_formats(self):
        runner = mock.Mock()
        runner.available = {"fast"}
        runner.generate.return_value = '{"suggestions":[]}'
        gateway = mock.Mock()
        gateway.oauth.accepts.return_value = True
        gateway.runner = runner
        handler = object.__new__(companion.CliGatewayRequestHandler)
        handler.server = type("GatewayServer", (), {"gateway": gateway})()
        handler.headers = {"Authorization": "Bearer test"}
        responses = []
        handler.send_json = lambda status, payload: responses.append((status, payload))

        request = {
            "model": "fast",
            "instructions": "Return exactly 3 suggestion(s).",
            "input": "내가 뭘",
            "store": False,
            "text": {
                "format": {
                    "name": companion.CONTINUATION_ABSTENTION_FORMAT,
                    "schema": {
                        "properties": {
                            "suggestions": {"minItems": 0, "maxItems": 3}
                        }
                    },
                }
            },
        }
        handler.run_responses(json.dumps(request).encode("utf-8"))
        self.assertEqual(200, responses[-1][0])
        runner.generate.assert_called_once_with(
            "fast", request["instructions"], request["input"], 3, True
        )

        for invalid_format in (None, [], "invalid"):
            runner.reset_mock()
            malformed = json.loads(json.dumps(request))
            malformed["text"]["format"] = invalid_format
            handler.run_responses(json.dumps(malformed).encode("utf-8"))
            self.assertEqual(400, responses[-1][0])
            runner.generate.assert_not_called()

        legacy = json.loads(json.dumps(request))
        legacy["text"]["format"].pop("name")
        legacy["text"]["format"]["schema"]["properties"]["suggestions"] = {
            "minItems": 1,
            "maxItems": 1,
        }
        handler.run_responses(json.dumps(legacy).encode("utf-8"))
        self.assertEqual(200, responses[-1][0])
        runner.generate.assert_called_once_with(
            "fast", legacy["instructions"], legacy["input"], 1, False
        )

    def test_manifest_routes_tiers_without_exposing_cli_credentials(self):
        runner = mock.Mock()
        runner.model_mapping.return_value = {
            "fast": "codex",
            "balanced": "claude",
            "quality": "codex",
        }
        gateway = companion.CliGateway(
            "https://computer.example:8840",
            runner,
            companion.DEFAULT_REDIRECT_URI,
            "Computer AI",
        )
        manifest = gateway.manifest()
        encoded = json.dumps(manifest)

        self.assertEqual("codex", manifest["models"]["fast"])
        self.assertEqual("claude", manifest["models"]["balanced"])
        self.assertEqual(["responses", "continuation_abstention"], manifest["capabilities"])
        self.assertNotIn("access_token", encoded)
        self.assertNotIn("api_key", encoded)
        self.assertNotIn("client_secret", encoded)

    @mock.patch.object(companion.shutil, "which", return_value="tailscale.exe")
    @mock.patch.object(companion.subprocess, "run")
    def test_tailscale_json_is_decoded_as_utf8_on_korean_windows(self, run, _which):
        payload = {
            "Self": {"DNSName": "alpaca-home.example.ts.net."},
            "Peer": {"phone": {"HostName": "윤찬 폰"}},
        }
        run.return_value = subprocess.CompletedProcess(
            ["tailscale"], 0, json.dumps(payload, ensure_ascii=False).encode("utf-8"), b""
        )

        self.assertEqual("alpaca-home.example.ts.net", companion.tailscale_dns_name())

    def test_posix_crypt_roundtrip_and_tamper_resistance(self):
        sample = b"secret-companion-token-12345:refresh-state"
        encrypted = companion._crypt_posix_local_data(sample, protect=True)
        self.assertTrue(encrypted.startswith(b"SG01"))
        decrypted = companion._crypt_posix_local_data(encrypted, protect=False)
        self.assertEqual(sample, decrypted)

        # Tampering with ciphertext fails integrity check
        tampered = bytearray(encrypted)
        tampered[-1] ^= 0x01
        with self.assertRaises(ValueError):
            companion._crypt_posix_local_data(bytes(tampered), protect=False)

    def test_agy_model_mapping_prioritizes_gemini_flash_for_fast_tier(self):
        with tempfile.TemporaryDirectory() as sandbox:
            runner = companion.CliBackendRunner.__new__(companion.CliBackendRunner)
            runner.sandbox_dir = Path(sandbox)
            runner.codex = "codex.cmd"
            runner.claude = "claude.exe"
            runner.agy = "agy.exe"
            runner.available = {companion.CliBackendRunner.MODEL_AGY, companion.CliBackendRunner.MODEL_CLAUDE}
            mapping = runner.model_mapping()
            self.assertEqual(companion.CliBackendRunner.MODEL_AGY, mapping["fast"])
            self.assertEqual(companion.CliBackendRunner.MODEL_CLAUDE, mapping["balanced"])
            self.assertEqual(companion.CliBackendRunner.MODEL_AGY, mapping["quality"])

    def test_agy_generate_dispatches_properly(self):
        with tempfile.TemporaryDirectory() as sandbox:
            runner = companion.CliBackendRunner.__new__(companion.CliBackendRunner)
            runner.sandbox_dir = Path(sandbox)
            runner.codex = None
            runner.claude = None
            runner.agy = "agy.exe"
            runner.available = {companion.CliBackendRunner.MODEL_AGY}
            runner._slot = companion.threading.BoundedSemaphore(1)

            with mock.patch.object(runner, "_run_agy", return_value='{"suggestions": ["오늘 점심 뭐 먹을래?"]}'):
                result = runner.generate(
                    companion.CliBackendRunner.MODEL_AGY,
                    "Return exactly 1 suggestion(s).",
                    "오눌 점심 머먹을래?",
                    1,
                )
                self.assertEqual('{"suggestions":["오늘 점심 뭐 먹을래?"]}', result)

    def test_agy_generate_allows_an_empty_continuation_abstention_response(self):
        with tempfile.TemporaryDirectory() as sandbox:
            runner = companion.CliBackendRunner.__new__(companion.CliBackendRunner)
            runner.sandbox_dir = Path(sandbox)
            runner.codex = None
            runner.claude = None
            runner.agy = "agy.exe"
            runner.available = {companion.CliBackendRunner.MODEL_AGY}
            runner._slot = companion.threading.BoundedSemaphore(1)
            prompts = []

            def run_agy(prompt):
                prompts.append(prompt)
                return '{"suggestions": []}'

            with mock.patch.object(runner, "_run_agy", side_effect=run_agy):
                result = runner.generate(
                    companion.CliBackendRunner.MODEL_AGY,
                    "Return exactly 3 suggestion(s).",
                    "내가 뭘",
                    3,
                    allow_partial_suggestions=True,
                )

            self.assertEqual('{"suggestions":[]}', result)
            self.assertNotIn("Return exactly 3 suggestion(s).", prompts[0])
            self.assertIn("Return zero to three suggestion(s).", prompts[0])

    def test_run_agy_uses_default_model_and_effort(self):
        with tempfile.TemporaryDirectory() as sandbox:
            runner = companion.CliBackendRunner.__new__(companion.CliBackendRunner)
            runner.sandbox_dir = Path(sandbox)
            runner.agy = "agy.exe"
            runner.agy_model = companion.DEFAULT_AGY_MODEL
            runner.agy_effort = companion.DEFAULT_AGY_EFFORT

            completed = subprocess.CompletedProcess(args=[], returncode=0, stdout="hello", stderr="")
            with mock.patch.object(companion, "run_quiet", return_value=completed) as mocked:
                result = runner._run_agy("prompt text")
                self.assertEqual("hello", result)
                command = mocked.call_args.args[0]
                self.assertEqual(1, command.count("--dangerously-skip-permissions"))
                self.assertIn("--model", command)
                self.assertIn("--effort", command)
                self.assertEqual("gemini-3.8-flash-high", command[command.index("--model") + 1])
                self.assertEqual("high", command[command.index("--effort") + 1])
                self.assertEqual("prompt text", command[command.index("-p") + 1])
                self.assertEqual(Path(sandbox), mocked.call_args.kwargs["cwd"])
                self.assertEqual(companion.CLI_TIMEOUT_SECONDS, mocked.call_args.kwargs["timeout"])

    def test_run_codex_uses_yolo_without_read_only_conflicts(self):
        with tempfile.TemporaryDirectory() as sandbox:
            runner = companion.CliBackendRunner.__new__(companion.CliBackendRunner)
            runner.sandbox_dir = Path(sandbox)
            runner.codex = "codex.cmd"

            completed = subprocess.CompletedProcess(args=[], returncode=0, stdout="hello", stderr="")
            with mock.patch.object(companion, "run_quiet", return_value=completed) as mocked:
                result = runner._run_codex("prompt text")

            self.assertEqual("hello", result)
            command = mocked.call_args.args[0]
            self.assertEqual(1, command.count("--yolo"))
            self.assertNotIn("--sandbox", command)
            self.assertNotIn("read-only", command)
            self.assertNotIn('approval_policy="never"', command)
            self.assertIn("--ephemeral", command)
            self.assertIn("--skip-git-repo-check", command)
            self.assertIn("--ignore-user-config", command)
            self.assertIn("--ignore-rules", command)
            self.assertIn('web_search="disabled"', command)
            self.assertEqual("never", command[command.index("--color") + 1])
            self.assertEqual(str(Path(sandbox)), command[command.index("-C") + 1])
            self.assertEqual("-", command[-1])
            self.assertEqual("prompt text", mocked.call_args.kwargs["input_text"])
            self.assertEqual(companion.CLI_TIMEOUT_SECONDS, mocked.call_args.kwargs["timeout"])

    def test_run_claude_uses_skip_permissions_without_safe_mode_conflicts(self):
        with tempfile.TemporaryDirectory() as sandbox:
            runner = companion.CliBackendRunner.__new__(companion.CliBackendRunner)
            runner.sandbox_dir = Path(sandbox)
            runner.claude = "claude.exe"

            completed = subprocess.CompletedProcess(
                args=[], returncode=0, stdout='{"is_error": false, "subtype": "success", "result": "hello"}', stderr=""
            )
            with mock.patch.object(companion, "run_quiet", return_value=completed) as mocked:
                result = runner._run_claude("prompt text")

            self.assertEqual("hello", result)
            command = mocked.call_args.args[0]
            self.assertEqual(1, command.count("--dangerously-skip-permissions"))
            self.assertNotIn("--safe-mode", command)
            self.assertNotIn("--tools", command)
            self.assertNotIn("--permission-mode", command)
            self.assertNotIn("dontAsk", command)
            self.assertIn("-p", command)
            self.assertIn("--no-session-persistence", command)
            self.assertEqual("json", command[command.index("--output-format") + 1])
            self.assertEqual(Path(sandbox), mocked.call_args.kwargs["cwd"])
            self.assertEqual("prompt text", mocked.call_args.kwargs["input_text"])
            self.assertEqual(companion.CLI_TIMEOUT_SECONDS, mocked.call_args.kwargs["timeout"])

    def test_run_agy_uses_configured_model_and_effort(self):
        with tempfile.TemporaryDirectory() as sandbox:
            runner = companion.CliBackendRunner.__new__(companion.CliBackendRunner)
            runner.sandbox_dir = Path(sandbox)
            runner.agy = "agy.exe"
            runner.agy_model = "gemini-3.8-flash-low"
            runner.agy_effort = "low"

            completed = subprocess.CompletedProcess(args=[], returncode=0, stdout="hello", stderr="")
            with mock.patch.object(companion, "run_quiet", return_value=completed) as mocked:
                runner._run_agy("prompt text")
                command = mocked.call_args.args[0]
                self.assertEqual("gemini-3.8-flash-low", command[command.index("--model") + 1])
                self.assertEqual("low", command[command.index("--effort") + 1])

    def test_cli_backend_runner_init_accepts_agy_model_and_effort(self):
        with tempfile.TemporaryDirectory() as sandbox:
            with mock.patch.object(companion, "find_executable", return_value=None), mock.patch.object(
                companion.CliBackendRunner, "_detect_available", return_value={companion.CliBackendRunner.MODEL_AGY}
            ):
                runner = companion.CliBackendRunner(
                    Path(sandbox), agy_model="gemini-3.8-flash-low", agy_effort="low"
                )
                self.assertEqual("gemini-3.8-flash-low", runner.agy_model)
                self.assertEqual("low", runner.agy_effort)

            with mock.patch.object(companion, "find_executable", return_value=None), mock.patch.object(
                companion.CliBackendRunner, "_detect_available", return_value={companion.CliBackendRunner.MODEL_AGY}
            ):
                runner = companion.CliBackendRunner(Path(sandbox))
                self.assertEqual(companion.DEFAULT_AGY_MODEL, runner.agy_model)
                self.assertEqual(companion.DEFAULT_AGY_EFFORT, runner.agy_effort)


class ParseArgsAgyOptionsTest(unittest.TestCase):
    def parse(self, argv: list[str]) -> "companion.argparse.Namespace":
        with mock.patch.object(sys, "argv", ["ai-provider-companion.py", *argv]):
            return companion.parse_args()

    def test_agy_model_and_effort_defaults(self):
        args = self.parse([])
        self.assertEqual(companion.DEFAULT_AGY_MODEL, args.agy_model)
        self.assertEqual(companion.DEFAULT_AGY_EFFORT, args.agy_effort)

    def test_agy_model_and_effort_can_be_overridden(self):
        args = self.parse(["--agy-model", "gemini-3.8-flash-low", "--agy-effort", "low"])
        self.assertEqual("gemini-3.8-flash-low", args.agy_model)
        self.assertEqual("low", args.agy_effort)

    def test_invalid_agy_effort_is_rejected(self):
        with self.assertRaises(SystemExit):
            with mock.patch.object(sys, "stderr"):
                self.parse(["--agy-effort", "ultra"])


if __name__ == "__main__":
    unittest.main()
