#!/usr/bin/env bash
# Analiza el vídeo de YouTube de una canción y guarda sus tiempos en el servidor.
# Uso: ./analyze.sh "Mama Said" [--dry-run]   ·   ./analyze.sh --list
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -x .venv/bin/python ]]; then
	python3 -m venv .venv
	PIP_CERT=/etc/ssl/certs/ca-certificates.crt .venv/bin/pip install -q -r requirements.txt
fi
export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
export no_proxy="localhost,127.0.0.1${no_proxy:+,$no_proxy}"
exec .venv/bin/python kompaser_analyze.py "$@"
