#!/bin/bash
# Build RPCSV.apk (hand-rolled: aapt2 + javac + d8 + zipalign + apksigner)
set -euo pipefail
cd "$(dirname "$0")"

SDK=/opt/android-sdk
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-34/android.jar"
OUT="$PWD/build"
SRC="$PWD/src"
RENDER=/root/rpcsv/renderer
ENGINE_APK=/tmp/opencode/current_official.apk

KS="$PWD/keystore.jks"
# A senha da chave de assinatura nao fica no fonte: quem assina este APK pode
# publicar um "update" que o Android aceita como legitimo. Vem do ambiente
# (RPCSV_KEYPASS) e o keystore e gitignored.
KEYPASS="${RPCSV_KEYPASS:?defina RPCSV_KEYPASS com a senha do keystore.jks}"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/assets" "$OUT/dex" "$OUT/stubs-classes"

echo "==> assets (renderer)"
cp -r "$RENDER"/. "$OUT/assets/"

# Update checker: o owner do GitHub entra aqui, para nao ficar hardcoded no
# fonte. Sem OWNER no update.conf o placeholder permanece e o app so mostra
# "sem repositorio de updates" (build local continua funcionando).
UPD_CONF="$PWD/update.conf"
UPD_OWNER=""
UPD_REPO="RPCSV"
if [ -f "$UPD_CONF" ]; then
  UPD_OWNER=$(sed -n 's/^OWNER=//p' "$UPD_CONF" | tr -d '[:space:]')
  UPD_REPO=$(sed -n 's/^REPO=//p' "$UPD_CONF" | tr -d '[:space:]')
  [ -z "$UPD_REPO" ] && UPD_REPO="RPCSV"
fi
if [ -n "$UPD_OWNER" ]; then
  sed -i "s/__RPCSV_UPDATE_OWNER__/$UPD_OWNER/g; s/const UPDATE_REPO = 'RPCSV';/const UPDATE_REPO = '$UPD_REPO';/g" \
    "$OUT/assets/js/rpcsv_android.js"
  echo "    update repo: $UPD_OWNER/$UPD_REPO"
else
  echo "    update repo: (nao configurado - OWNER vazio em update.conf)"
fi

echo "==> assets (config.yml template da engine)"
mkdir -p "$OUT/assets/templates"
cp "$PWD/src/templates/config.yml" "$OUT/assets/templates/config.yml"

echo "==> assets (engine usu)"
python3 - "$ENGINE_APK" "$OUT/assets" <<'EOF'
import sys, zipfile, os
apk, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    for n in z.namelist():
        if n.startswith('assets/') and n != 'assets/':
            t = os.path.join(dest, os.path.relpath(n, 'assets'))
            os.makedirs(os.path.dirname(t), exist_ok=True)
            with open(t, 'wb') as f:
                f.write(z.read(n))
print('assets engine ok')
EOF

echo "==> icons"
# A marca vem do genbrand.py (gerada do zero, sem pegar arte pronta de
# nenhum Vita3K). Rodar o antigo makeicon.py aqui sobrescrevia o
# ic_launcher.png com um placeholder de tela+bolhas a cada build, e era
# exatamente por isso que o icone do launcher nunca mudava.
# O gerador leva ~6 min, entao so roda de verdade quando os arquivos
# faltam ou quando RPCSV_REGEN_BRAND=1.
if [ "${RPCSV_REGEN_BRAND:-0}" = "1" ]; then
    python3 "$PWD/genbrand.py"
elif [ ! -f "$PWD/res/mipmap-xhdpi/ic_launcher.png" ] \
  || [ ! -f "$PWD/res/mipmap-xhdpi/ic_launcher_foreground.png" ] \
  || [ ! -f "$PWD/res/drawable-nodpi/rpcsv_mark.png" ] \
  || [ ! -f /root/rpcsv/renderer/brand/rpcsv-mark.png ] \
  || [ ! -f /root/rpcsv/renderer/brand/boot.wav ]; then
    echo "    arte ausente, gerando"
    python3 "$PWD/genbrand.py"
else
    echo "    arte ja gerada (RPCSV_REGEN_BRAND=1 para refazer)"
fi

echo "==> aapt2 compile"
aapt2 compile --dir "$PWD/res" -o "$OUT/res.zip"

echo "==> aapt2 link"
aapt2 link -o "$OUT/app.raw.apk" \
  -I "$AJ" \
  -R "$OUT/res.zip" \
  --manifest "$PWD/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -A "$OUT/assets" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "==> libs nativas"
python3 - "$ENGINE_APK" "$OUT" <<'EOF'
import sys, zipfile, os
apk, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    for n in z.namelist():
        if n.startswith('lib/') and n.endswith('.so'):
            t = os.path.join(out, n)
            os.makedirs(os.path.dirname(t), exist_ok=True)
            with open(t, 'wb') as f:
                f.write(z.read(n))
print('libs extraidas')
EOF

echo "==> javac (stubs de compilacao)"
find "$PWD/engine-stubs" -name '*.java' > "$OUT/stubs.txt"
javac -source 8 -target 8 -bootclasspath "$AJ" \
  -d "$OUT/stubs-classes" @"$OUT/stubs.txt"

echo "==> javac (app)"
find "$SRC" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -bootclasspath "$AJ" \
  -cp "$OUT/stubs-classes" \
  -d "$OUT/classes" @"$OUT/sources.txt" "$OUT/gen/com/rpcsv/app/R.java"

echo "==> d8 (app)"
"$BT/d8" --lib "$AJ" --classpath "$OUT/stubs-classes" --release --min-api 26 \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "==> classes2.dex (engine)"
python3 - "$ENGINE_APK" "$OUT/dex" <<'EOF'
import sys, zipfile, os
apk, dex_dir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    with open(os.path.join(dex_dir, 'classes2.dex'), 'wb') as f:
        f.write(z.read('classes.dex'))
EOF

echo "==> package dex"
(cd "$OUT/dex" && zip -q "../app.raw.apk" classes.dex classes2.dex)

echo "==> package libs"
(cd "$OUT" && zip -q -y -r app.raw.apk lib)

echo "==> zipalign"
zipalign -f 4 "$OUT/app.raw.apk" "$OUT/app.align.apk"

echo "==> keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias rpcsv -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KEYPASS" -keypass "$KEYPASS" \
    -dname "CN=RPCSV,O=RPCSV,C=BR" -noprompt
fi

echo "==> apksigner"
apksigner sign --ks "$KS" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out "$OUT/RPCSV.apk" "$OUT/app.align.apk"

echo "==> OK"
ls -lh "$OUT/RPCSV.apk"