#!/bin/bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORKSPACE="${YSHOP_TEST_WORKSPACE:-$(dirname "$REPO")}"
# This legacy suite creates unpaid business records. Never start or mutate a shared environment
# implicitly. The quality gate uses the isolated read-only page suite instead.
: "${YSHOP_ALLOW_DEV_BUSINESS_SMOKE:?Explicit authorization for legacy business writes required}"
[[ "$YSHOP_ALLOW_DEV_BUSINESS_SMOKE" == "1" ]]
: "${YSHOP_MINI_PROJECT:?Fresh private compiled project required}"
: "${YSHOP_MINI_BUILD_REPORT:?Attributed compilation report required}"
export YSHOP_TEST_WORKSPACE="$WORKSPACE"
export YSHOP_API_PORT="${YSHOP_API_PORT:-48081}"
export YSHOP_AUTOMATION_PORT="${YSHOP_AUTOMATION_PORT:-9420}"
[[ "$YSHOP_API_PORT" =~ ^4808[123]$ ]]
# The legacy baseline page fixture uses seed store 2 and port 48081. Do not silently redirect it.
[[ "$YSHOP_API_PORT" == "48081" ]]

python3 - "$REPO" "$YSHOP_MINI_PROJECT" "$YSHOP_MINI_BUILD_REPORT" <<'CHECK'
import json,pathlib,sys
r=json.loads(pathlib.Path(sys.argv[3]).read_text())
assert r['result']=='PASS' and pathlib.Path(r['sourceRepo']).resolve()==pathlib.Path(sys.argv[1]).resolve()
assert pathlib.Path(r['project']).resolve()==pathlib.Path(sys.argv[2]).resolve()
CHECK
source "$WORKSPACE/.local-dev/tools.sh"
curl --fail --silent --max-time 5 "http://127.0.0.1:$YSHOP_API_PORT/actuator/health" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
CLI="${YSHOP_WECHAT_CLI:-/Applications/wechatwebdevtools.app/Contents/MacOS/cli}"
PROJECT="$YSHOP_MINI_PROJECT"
cleanup() {
  status=$?
  trap - EXIT
  if ! "$CLI" close --project "$PROJECT" >/dev/null 2>&1; then status=1; fi
  if ! "$CLI" open --project "$PROJECT" >/dev/null 2>&1; then status=1; fi
  exit "$status"
}
trap cleanup EXIT
"$CLI" auto --project "$PROJECT" --auto-port "$YSHOP_AUTOMATION_PORT"
node "$REPO/tests/smoke/mini-program.cjs" "$@"
