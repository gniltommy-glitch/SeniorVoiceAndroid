#!/bin/zsh
cd -- "${0:A:h}/.." || exit 1
python3 server/run.py --doctor
python3 server/run.py --smoke
read -r '?Press Enter to close...'
