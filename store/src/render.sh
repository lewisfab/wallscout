# Usage: render.sh <page.html> <out.png> <width> <height>
"/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --user-data-dir="$(cygpath -w "$TEMP/fw-chrome")" --window-size="$3,$4" \
  --virtual-time-budget=4000 --screenshot="$(cygpath -aw "$2")" "file:///$(cygpath -am "$1")"
