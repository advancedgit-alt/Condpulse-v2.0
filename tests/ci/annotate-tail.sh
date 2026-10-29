#!/usr/bin/env bash
# Publica as últimas linhas de um log de etapa como uma única anotação de erro do GitHub Actions.
# Uso: annotate-tail.sh <arquivo> <título> [linhas]. Somente CI; o log completo continua no Actions.
set -u
file=$1; title=$2; lines=${3:-60}
[ -f "$file" ] || exit 0
message=$(tail -n "$lines" "$file" | cut -c1-400 | sed -e 's/%/%25/g' -e 's/\r/%0D/g' | awk '{printf "%s%%0A", $0}')
printf '::error title=%s::%s\n' "$title" "$message"
