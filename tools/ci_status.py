import json
import subprocess
import sys

RUN = sys.argv[1] if len(sys.argv) > 1 else "latest"


def get(url):
    out = subprocess.run(
        ["curl", "-s", "-m", "25", "-H", "Accept: application/vnd.github+json", url],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return json.loads(out.stdout or "{}")


repo = "DangJiYi/checklist"
runs = get(f"https://api.github.com/repos/{repo}/actions/runs?per_page=5")
runs = runs.get("workflow_runs", [])
if not runs:
    print("no runs")
    sys.exit(0)

run = runs[0] if RUN == "latest" else next((r for r in runs if str(r["id"]) == RUN), runs[0])
print(f"run {run['id']}  {run['status']} / {run['conclusion']}  head={run['head_sha'][:8]}")
print(f"url  {run['html_url']}")
print()

jobs = get(f"https://api.github.com/repos/{repo}/actions/runs/{run['id']}/jobs")
for j in jobs.get("jobs", []):
    print(f"JOB {j['name']}  {j['status']} / {j['conclusion']}")
    for s in j.get("steps", []):
        print(f"  {s['number']:>2}. {s['name']:<42} {s['conclusion']}")
    print()
