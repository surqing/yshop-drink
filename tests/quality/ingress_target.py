"""Fail-closed validation for the disposable synthetic TLS callback endpoint."""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import sys
from urllib.parse import urlsplit

PROXY_IMAGE = "nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94"
MANAGED_INGRESS = "yshop-drink-local-ingress-streamed"
CALLBACK_UPSTREAM_PORT = 48881


def expected_proxy_config(port, platform=None):
    """Return the only Nginx configuration accepted for callback E2E traffic."""
    if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
        raise ValueError("PROXY_PORT_INVALID")
    platform = platform or sys.platform
    repo = Path(__file__).resolve().parents[2]
    config = (repo / "scripts/payment/callback-nginx.conf").read_text()
    upstream = "127.0.0.1" if platform.startswith("linux") else "host.docker.internal"
    marker = "host.docker.internal:48081"
    if config.count(marker) != 3:
        raise ValueError("CALLBACK_PROXY_TEMPLATE_DRIFT")
    config = config.replace(marker, f"{upstream}:{CALLBACK_UPSTREAM_PORT}")
    if platform.startswith("linux"):
        listener = "listen 8443 ssl;"
        if config.count(listener) != 1:
            raise ValueError("CALLBACK_PROXY_TEMPLATE_DRIFT")
        config = config.replace(
            listener,
            f"listen 127.0.0.1:{port} ssl;\n        listen [::1]:{port} ssl;",
        )
    return config.encode()


def _owned_proxy_publishes(owner, port, expected_config):
    try:
        result = subprocess.run(
            ["docker", "inspect", owner], capture_output=True, text=True, timeout=10, check=False)
        if result.returncode != 0:
            return False
        containers = json.loads(result.stdout)
        if not isinstance(containers, list) or len(containers) != 1:
            return False
        container = containers[0]
        config = container.get("Config") or {}
        host = container.get("HostConfig") or {}
        mounts = container.get("Mounts") or []
        state = container.get("State") or {}
        managed = owner == MANAGED_INGRESS
        labels = config.get("Labels") or {}
        expected_label = labels.get("project") == "yshop-drink-phase5h" if managed else labels.get("quality.owner") == owner
        if (config.get("Image") != PROXY_IMAGE or config.get("Entrypoint") != ["nginx"]
                or config.get("Cmd") != ["-c", "/tls/nginx.conf", "-g", "daemon off;"]
                or not expected_label
                or state.get("Running") is not True):
            return False
        if not any(mount.get("Type") == "volume" and mount.get("Destination") == "/tls"
                   and mount.get("RW") is False for mount in mounts):
            return False
        if host.get("NetworkMode") == "host":
            published = not managed and sys.platform.startswith("linux")
        else:
            bindings = (host.get("PortBindings") or {}).get("8443/tcp") or []
            if managed and port != 48443:
                return False
            published = any(binding.get("HostIp") == "127.0.0.1" and binding.get("HostPort") == str(port)
                            for binding in bindings)
        if not published:
            return False
        live = subprocess.run(
            ["docker", "exec", owner, "cat", "/tls/nginx.conf"],
            capture_output=True, timeout=10, check=False)
        return live.returncode == 0 and live.stdout == expected_config
    except (OSError, subprocess.SubprocessError, TypeError, ValueError, AttributeError):
        return False


def is_owned_synthetic_ingress(base_url, ca_file, tls_dir, expected_owner=None):
    try:
        url = urlsplit(base_url or "")
        port = url.port
        tls_root = Path(tls_dir).resolve()
        ca_path = Path(ca_file).resolve()
    except (TypeError, ValueError, OSError):
        return False
    if (url.scheme != "https" or url.hostname != "localhost" or port is None or not 1 <= port <= 65535
            or url.username is not None or url.password is not None or url.path not in ("", "/")
            or url.query or url.fragment or ca_path != tls_root / "ca.pem" or not ca_path.is_file()
            or not isinstance(expected_owner, str)
            or not (re.fullmatch(r"yshop-quality-ingress-[0-9a-f]{12}", expected_owner)
                    or expected_owner == MANAGED_INGRESS)):
        return False
    try:
        server_path = tls_root / "server.pem"
        claim_path = tls_root / "ingress-target.json"
        if not server_path.is_file() or not claim_path.is_file():
            return False
        server_digest = hashlib.sha256(ssl.PEM_cert_to_DER_cert(server_path.read_text())).hexdigest()
        claim = json.loads(claim_path.read_text())
        expected_config = expected_proxy_config(port)
        config_digest = hashlib.sha256(expected_config).hexdigest()
        if (not isinstance(claim, dict)
                or set(claim) != {"owner", "port", "serverCertSha256", "upstreamPort", "configSha256"}
                or claim.get("owner") != expected_owner or claim.get("port") != port
                or claim.get("serverCertSha256") != server_digest
                or claim.get("upstreamPort") != CALLBACK_UPSTREAM_PORT
                or claim.get("configSha256") != config_digest):
            return False
        result = subprocess.run(
            ["openssl", "x509", "-in", str(ca_path), "-noout", "-subject", "-nameopt", "RFC2253"],
            capture_output=True, text=True, timeout=10, check=False)
        subject = result.stdout.strip()
        if not (result.returncode == 0 and subject.startswith("subject=")
                and subject[len("subject="):].lstrip() == "CN=yshop synthetic local CA"):
            return False
        if not _owned_proxy_publishes(expected_owner, port, expected_config):
            return False
        context = ssl.create_default_context(cafile=str(ca_path))
        with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
            with context.wrap_socket(connection, server_hostname="localhost") as tls:
                peer_digest = hashlib.sha256(tls.getpeercert(binary_form=True)).hexdigest()
        return peer_digest == server_digest
    except (OSError, subprocess.SubprocessError):
        return False
    except (TypeError, ValueError, KeyError, ssl.SSLError):
        return False


def main(argv=None):
    """Minimal quiet CLI used by direct Maven/JUnit invocations."""
    args = list(sys.argv[1:] if argv is None else argv)
    if args != ["--check-env"]:
        return 2
    env = os.environ
    return 0 if is_owned_synthetic_ingress(
        env.get("YSHOP_INGRESS_BASE_URL"), env.get("YSHOP_INGRESS_CA"),
        env.get("YSHOP_SYNTHETIC_TLS_DIR"), env.get("YSHOP_INGRESS_OWNER")) else 1


if __name__ == "__main__":
    sys.exit(main())
