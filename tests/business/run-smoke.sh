#!/bin/bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORKSPACE="$(dirname "$REPO")"
source "$WORKSPACE/.local-dev/tools.sh"
export YSHOP_API_PORT="${YSHOP_API_PORT:-48081}"
[[ "$YSHOP_API_PORT" == 48081 || "$YSHOP_API_PORT" == 48082 ]]
curl --fail --silent "http://127.0.0.1:$YSHOP_API_PORT/actuator/health" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"]=="UP"'
CLI=/Applications/wechatwebdevtools.app/Contents/MacOS/cli
PROJECT="$WORKSPACE/.uniapp-dev/project/unpackage/dist/dev/mp-weixin"
cleanup() { "$CLI" close --project "$PROJECT" >/dev/null 2>&1 || true; "$CLI" open --project "$PROJECT" >/dev/null 2>&1 || true; }
trap cleanup EXIT
"$CLI" close --project "$PROJECT" >/dev/null 2>&1 || true
bash "$WORKSPACE/.uniapp-dev/compile.sh"
"$CLI" close --project "$PROJECT" >/dev/null 2>&1 || true
"$CLI" auto --project "$PROJECT" --auto-port 9420
node "$REPO/tests/business/mini-program.cjs"
