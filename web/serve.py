#!/usr/bin/env python3
"""HTTPS-сервер для локального запуска.

Камера в браузере телефона работает только по https, поэтому сервер
создаёт самоподписанный сертификат. Телефон и компьютер — в одной Wi-Fi сети.
"""
import http.server
import os
import pathlib
import socket
import ssl
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent
CERT_DIR = ROOT / ".cert"
PORT = int(os.environ.get("PORT", 8443))


def local_ip():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def ensure_cert():
    cert, key = CERT_DIR / "cert.pem", CERT_DIR / "key.pem"
    if not cert.exists():
        CERT_DIR.mkdir(exist_ok=True)
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "365",
             "-subj", "/CN=zholsafe.local", "-keyout", str(key), "-out", str(cert)],
            check=True, capture_output=True,
        )
    return cert, key


def main():
    cert, key = ensure_cert()
    os.chdir(ROOT)
    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), http.server.SimpleHTTPRequestHandler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
    print(f"Компьютер: https://localhost:{PORT}")
    print(f"Телефон:   https://{local_ip()}:{PORT}  (браузер предупредит о сертификате — «Всё равно открыть»)")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
