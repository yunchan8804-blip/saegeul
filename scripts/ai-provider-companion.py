#!/usr/bin/env python3
"""Advertise an Fcitx Android AI provider on the local network without dependencies.

The provider itself owns OAuth/OIDC and serves the versioned HTTPS manifest. This helper only
publishes its trusted manifest URL through DNS-SD/mDNS; it never reads or proxies API keys.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import shutil
import socket
import ssl
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


SERVICE_TYPE = "_fcitx-ai._tcp.local."
WELL_KNOWN_PATH = "/.well-known/fcitx-ai-provider"
MULTICAST_GROUP = "224.0.0.251"
MULTICAST_PORT = 5353
MAX_MANIFEST_BYTES = 128 * 1024
FORBIDDEN_KEYS = {
    "api_key",
    "client_secret",
    "access_token",
    "refresh_token",
    "authorization",
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def manifest_url(value: str) -> str:
    parsed = urllib.parse.urlsplit(value.strip())
    if parsed.scheme.lower() != "https" or not parsed.hostname:
        raise ValueError("the manifest address must use HTTPS")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("the manifest address cannot contain credentials, query, or fragment")
    path = parsed.path
    if path in ("", "/"):
        path = WELL_KNOWN_PATH
    if path != WELL_KNOWN_PATH:
        raise ValueError(f"the manifest address must use {WELL_KNOWN_PATH}")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))


def reject_credentials(value) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key.lower() in FORBIDDEN_KEYS:
                raise ValueError("the discovery manifest must not contain credentials")
            reject_credentials(child)
    elif isinstance(value, list):
        for child in value:
            reject_credentials(child)


def verify_manifest(url: str) -> dict:
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "User-Agent": "Fcitx5-AI-Companion/1"},
    )
    opener = urllib.request.build_opener(
        NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()),
    )
    try:
        with opener.open(request, timeout=7) as response:
            if response.status != 200:
                raise ValueError(f"the provider returned HTTP {response.status}")
            payload = response.read(MAX_MANIFEST_BYTES + 1)
    except urllib.error.HTTPError as error:
        raise ValueError(f"the provider returned HTTP {error.code}") from error
    except (urllib.error.URLError, TimeoutError) as error:
        raise ValueError(f"could not verify the provider: {getattr(error, 'reason', error)}") from error
    if len(payload) > MAX_MANIFEST_BYTES:
        raise ValueError("the discovery manifest is too large")
    try:
        document = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("the provider did not return a valid JSON manifest") from error
    reject_credentials(document)
    if document.get("protocol_version") != 1:
        raise ValueError("the provider uses an unsupported discovery protocol")
    if "responses" not in document.get("capabilities", []):
        raise ValueError("the provider does not advertise Responses support")
    oauth = document.get("oauth") or {}
    required = (
        "authorization_endpoint",
        "token_endpoint",
        "client_id",
        "scopes",
        "redirect_uri",
    )
    if any(not oauth.get(field) for field in required):
        raise ValueError("the provider OAuth manifest is incomplete")
    models = document.get("models") or {}
    if any(not str(models.get(tier) or "").strip() for tier in ("fast", "balanced", "quality")):
        raise ValueError("the provider model mapping is incomplete")
    return document


def tailscale_manifest_candidates() -> list[str]:
    executable = shutil.which("tailscale")
    if not executable:
        return []
    try:
        result = subprocess.run(
            [executable, "status", "--json"],
            check=True,
            capture_output=True,
            text=True,
            timeout=5,
        )
        status = json.loads(result.stdout)
        dns_name = str((status.get("Self") or {}).get("DNSName") or "").rstrip(".")
        return [f"https://{dns_name}{WELL_KNOWN_PATH}"] if dns_name else []
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
        return []


def find_manifest(explicit: str | None) -> tuple[str, dict]:
    candidates = []
    if explicit:
        candidates.append(explicit)
    if os.environ.get("FCITX_AI_MANIFEST_URL"):
        candidates.append(os.environ["FCITX_AI_MANIFEST_URL"])
    candidates.extend(tailscale_manifest_candidates())
    fqdn = socket.getfqdn().rstrip(".")
    if "." in fqdn:
        candidates.append(f"https://{fqdn}{WELL_KNOWN_PATH}")

    failures = []
    for candidate in dict.fromkeys(candidates):
        try:
            normalized = manifest_url(candidate)
            return normalized, verify_manifest(normalized)
        except ValueError as error:
            failures.append(f"  - {candidate}: {error}")
    if failures:
        detail = "\n".join(failures)
        raise ValueError(f"no usable provider manifest was found:\n{detail}")
    raise ValueError(
        "no provider manifest address was supplied; use --manifest-url or "
        "FCITX_AI_MANIFEST_URL"
    )


def local_ipv4(explicit: str | None) -> str:
    if explicit:
        address = str(ipaddress.IPv4Address(explicit))
        if ipaddress.ip_address(address).is_unspecified:
            raise ValueError("the advertised IPv4 address cannot be unspecified")
        return address
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    finally:
        probe.close()


def dns_name(value: str) -> bytes:
    output = bytearray()
    for label in value.rstrip(".").split("."):
        encoded = label.encode("utf-8")
        if not encoded or len(encoded) > 63:
            raise ValueError(f"invalid DNS-SD label: {label!r}")
        output.append(len(encoded))
        output.extend(encoded)
    output.append(0)
    return bytes(output)


def record(name: str, kind: int, klass: int, ttl: int, data: bytes) -> bytes:
    return dns_name(name) + struct.pack("!HHIH", kind, klass, ttl, len(data)) + data


def response_packet(instance: str, hostname: str, address: str, port: int, url: str, ttl: int) -> bytes:
    txt_items = [f"manifest={url}".encode(), b"version=1"]
    if any(len(item) > 255 for item in txt_items):
        raise ValueError("the manifest URL is too long for DNS-SD")
    txt = b"".join(bytes([len(item)]) + item for item in txt_items)
    records = (
        record(SERVICE_TYPE, 12, 1, ttl, dns_name(instance))
        + record(instance, 33, 0x8001, ttl, struct.pack("!HHH", 0, 0, port) + dns_name(hostname))
        + record(instance, 16, 0x8001, ttl, txt)
        + record(hostname, 1, 0x8001, ttl, socket.inet_aton(address))
    )
    return struct.pack("!HHHHHH", 0, 0x8400, 0, 4, 0, 0) + records


def advertise(name: str, address: str, port: int, url: str) -> None:
    safe_host = "".join(
        character.lower() if character.isascii() and character.isalnum() else "-"
        for character in socket.gethostname()
    )
    safe_host = safe_host.strip("-")[:50] or "fcitx-ai"
    hostname = f"{safe_host}.local."
    instance = f"{name}.{SERVICE_TYPE}"
    packet = response_packet(instance, hostname, address, port, url, 120)
    goodbye = response_packet(instance, hostname, address, port, url, 0)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", MULTICAST_PORT))
    except OSError:
        sock.bind(("", 0))
    membership = socket.inet_aton(MULTICAST_GROUP) + socket.inet_aton(address)
    try:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
    except OSError:
        pass
    try:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(address))
    except OSError:
        pass
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    sock.settimeout(1)

    print(f"AI connection helper: {name}")
    print(f"Secure manifest: {url}")
    print(f"Wi-Fi address: {address}")
    print("On Android, open AI provider > Find my computer automatically.")
    print("Press Ctrl+C to stop.")
    try:
        last_announcement = 0.0
        while True:
            now = time.monotonic()
            if now - last_announcement >= 5:
                sock.sendto(packet, (MULTICAST_GROUP, MULTICAST_PORT))
                last_announcement = now
            try:
                sock.recvfrom(9000)
                sock.sendto(packet, (MULTICAST_GROUP, MULTICAST_PORT))
            except socket.timeout:
                pass
    except KeyboardInterrupt:
        for _ in range(2):
            sock.sendto(goodbye, (MULTICAST_GROUP, MULTICAST_PORT))
    finally:
        sock.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Advertise a verified Fcitx Android AI provider on the current Wi-Fi"
    )
    parser.add_argument("--manifest-url", help="HTTPS provider origin or well-known manifest URL")
    parser.add_argument("--name", default=socket.gethostname(), help="computer name shown on Android")
    parser.add_argument("--address", help="local IPv4 address to advertise")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        url, document = find_manifest(args.manifest_url)
        address = local_ipv4(args.address)
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port or 443
        name = args.name.strip().replace(".", "-")
        name = name.encode("utf-8")[:50].decode("utf-8", errors="ignore")
        if not name:
            raise ValueError("computer name is empty")
        print(f"Verified provider: {document.get('display_name', document.get('provider_id', 'AI'))}")
        advertise(name, address, port, url)
        return 0
    except ValueError as error:
        print(f"Cannot start AI connection helper: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
