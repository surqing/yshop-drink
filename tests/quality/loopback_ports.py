"""Backend ports below default Linux/macOS ephemeral ranges used by Docker mappings."""
import secrets
import socket

def candidate():
    return 10000 + secrets.randbelow(20000)

def backend_pair():
    for _ in range(100):
        port=candidate()
        with socket.socket() as first, socket.socket() as second:
            try:
                first.bind(('127.0.0.1',port))
                second.bind(('127.0.0.1',port+1))
            except OSError:
                continue
            return port
    raise RuntimeError('OWNED_BACKEND_PORTS_UNAVAILABLE')
