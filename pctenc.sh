#!/bin/bash
# Percent-encode stdin for a URL query value.
exec python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read()),end="")'
