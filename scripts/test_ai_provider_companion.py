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


if __name__ == "__main__":
    unittest.main()
