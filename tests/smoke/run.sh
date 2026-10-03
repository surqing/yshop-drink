#!/bin/bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORKSPACE="$(dirname "$REPO")"
source "$WORKSPACE/.local-dev/tools.sh"
YSHOP_NO_OPEN=1 python3 "$WORKSPACE/.local-dev/dev.py" start
curl --fail --silent http://127.0.0.1:48081/actuator/health | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
CLI=/Applications/wechatwebdevtools.app/Contents/MacOS/cli
PROJECT="$WORKSPACE/.uniapp-dev/project/unpackage/dist/dev/mp-weixin"
# Always return to ordinary developer mode so the automation port is temporary.
cleanup() {
  "$CLI" close --project "$PROJECT" >/dev/null 2>&1 || true
  "$CLI" open --project "$PROJECT" >/dev/null 2>&1 || true
}
trap cleanup EXIT
# Compile into a closed project to avoid racing a previous automation runtime.
"$CLI" close --project "$PROJECT" >/dev/null 2>&1 || true
bash "$WORKSPACE/.uniapp-dev/compile.sh"
"$CLI" close --project "$PROJECT" >/dev/null 2>&1 || true
"$CLI" auto --project "$PROJECT" --auto-port 9420
node "$REPO/tests/smoke/mini-program.cjs" "$@"
