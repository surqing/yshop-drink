"""Fail-closed validation for the disposable synthetic TLS callback endpoint."""
import hashlib
import json
from pathlib import Path
import re
import socket
import ssl
import subprocess
import sys
from urllib.parse import urlsplit

PROXY_IMAGE = "nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94"


def _owned_proxy_publishes(owner, port):
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
        state = container.get("State") or {}
        if (config.get("Image") != PROXY_IMAGE or config.get("Entrypoint") != ["nginx"]
                or config.get("Cmd") != ["-c", "/tls/nginx.conf", "-g", "daemon off;"]
                or (config.get("Labels") or {}).get("quality.owner") != owner
                or state.get("Running") is not True):
            return False
        if host.get("NetworkMode") == "host":
            return sys.platform.startswith("linux")
        bindings = (host.get("PortBindings") or {}).get("8443/tcp") or []
        return any(binding.get("HostIp") == "127.0.0.1" and binding.get("HostPort") == str(port)
                   for binding in bindings)
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
            or not re.fullmatch(r"yshop-quality-ingress-[0-9a-f]{12}", expected_owner)):
        return False
    try:
        server_path = tls_root / "server.pem"
        claim_path = tls_root / "ingress-target.json"
        if not server_path.is_file() or not claim_path.is_file():
            return False
        server_digest = hashlib.sha256(ssl.PEM_cert_to_DER_cert(server_path.read_text())).hexdigest()
        claim = json.loads(claim_path.read_text())
        if (not isinstance(claim, dict) or set(claim) != {"owner", "port", "serverCertSha256"}
                or claim.get("owner") != expected_owner or claim.get("port") != port
                or claim.get("serverCertSha256") != server_digest):
            return False
        result = subprocess.run(
            ["openssl", "x509", "-in", str(ca_path), "-noout", "-subject", "-nameopt", "RFC2253"],
            capture_output=True, text=True, timeout=10, check=False)
        subject = result.stdout.strip()
        if not (result.returncode == 0 and subject.startswith("subject=")
                and subject[len("subject="):].lstrip() == "CN=yshop synthetic local CA"):
            return False
        if not _owned_proxy_publishes(expected_owner, port):
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
