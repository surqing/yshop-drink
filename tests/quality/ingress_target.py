"""Fail-closed validation for the disposable synthetic TLS callback endpoint."""
from pathlib import Path
import subprocess
from urllib.parse import urlsplit


def is_owned_synthetic_ingress(base_url, ca_file, tls_dir):
    try:
        url = urlsplit(base_url or "")
        port = url.port
        tls_root = Path(tls_dir).resolve()
        ca_path = Path(ca_file).resolve()
    except (TypeError, ValueError, OSError):
        return False
    if (url.scheme != "https" or url.hostname != "localhost" or port is None or not 1 <= port <= 65535
            or url.username is not None or url.password is not None or url.path not in ("", "/")
            or url.query or url.fragment or ca_path != tls_root / "ca.pem" or not ca_path.is_file()):
        return False
    try:
        result = subprocess.run(
            ["openssl", "x509", "-in", str(ca_path), "-noout", "-subject", "-nameopt", "RFC2253"],
            capture_output=True, text=True, timeout=10, check=False)
    except (OSError, subprocess.SubprocessError):
        return False
    subject = result.stdout.strip()
    return result.returncode == 0 and subject.startswith("subject=") and subject[len("subject="):].lstrip() == "CN=yshop synthetic local CA"
