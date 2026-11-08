#!/usr/bin/env bash
# Downloads and unpacks the Gutenprint source into native/gutenprint-src.
set -euo pipefail
cd "$(dirname "$0")"
[ -d gutenprint-src ] && exit 0
for v in 5.3.5 5.3.4; do
  url="https://downloads.sourceforge.net/project/gimp-print/gutenprint-5.3/$v/gutenprint-$v.tar.xz"
  echo "Trying $url"
  if curl -fsSL --retry 3 -o gp.tar.xz "$url"; then
    mkdir -p gutenprint-src && tar -xJf gp.tar.xz -C gutenprint-src --strip-components=1 && rm gp.tar.xz
    echo "$v" > gutenprint-src/.version
    echo "Gutenprint $v unpacked"
    exit 0
  fi
done
echo "Could not download Gutenprint" >&2
exit 1
