#!/bin/bash
# Roda o harness do PkgExtractor (test/PkgTest.java) contra o codigo real.
#
# Existe porque a regra "chave errada bloqueia, chave certa conclui" nao da
# para conferir pela UI: ela precisa de um PKG cifrado de verdade, e o
# comportamento errado (instalar pela metade e reportar sucesso) era
# silencioso. O harness monta um PFS valido, assina a licencia com a chave
# certa e com uma errada, e verifica que nada e gravado antes da recusa.
set -euo pipefail
cd "$(dirname "$0")/.."

WORK="${RPCSV_TEST_WORK:-/tmp/rpcsv-pkgtest}"
CLASSES=$(mktemp -d)
trap 'rm -rf "$CLASSES"' EXIT

echo "==> compila PkgExtractor"
javac -nowarn -d "$CLASSES" src/com/rpcsv/app/PkgExtractor.java

echo "==> compila e roda o harness"
javac -nowarn -cp "$CLASSES" -d "$CLASSES" test/PkgTest.java
java -cp "$CLASSES" -Drpcsv.test.work="$WORK" PkgTest
