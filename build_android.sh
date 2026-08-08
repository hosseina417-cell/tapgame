#!/usr/bin/env bash
set -euo pipefail
python3 -m py_compile arbitrage_core.py main.py kivy_app.py web_app.py
python3 -m unittest discover -s tests -v
python3 -m pip install --user --break-system-packages buildozer cython==0.29.36
export PATH="$HOME/.local/bin:$PATH"
export PIP_BREAK_SYSTEM_PACKAGES=1
buildozer android debug
ls -lh bin/*.apk
