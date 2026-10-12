#!/bin/sh
# Wraps app.html (the page body, also published as-is on claude.ai) into a standalone index.html.
set -e
cd "$(dirname "$0")"
{
  printf '<!doctype html>\n<html lang="fr">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n</head>\n<body>\n'
  cat app.html
  printf '</body>\n</html>\n'
} > index.html
