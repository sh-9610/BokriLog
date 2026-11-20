# /// script
# requires-python = ">=3.11"
# dependencies = ["ruamel.yaml>=0.18,<0.20"]
# ///
"""현재 체크아웃의 Git, 스프린트와 CI 상태를 조회한다."""
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit

from ruamel.yaml import YAML, YAMLError

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "_bmad-output" / "implementation-artifacts"


def query(*args):
    return subprocess.run(args, cwd=ROOT, capture_output=True, check=True,
                          encoding="utf-8", errors="replace", timeout=30).stdout


def spec_status(number):
    paths = sorted(ARTIFACTS.glob(f"spec-{number}-*.md"))
    if not paths:
        return "SPEC 확인 불가 (누락)"
    if len(paths) > 1:
        return "SPEC 확인 불가 (후보 " + ", ".join(path.name for path in paths) + ")"

    path = paths[0]
    try:
        header = re.match(r"\A---\n(.*?)^---[ \t]*$", path.read_text(encoding="utf-8"), re.M | re.S)
        if header:
            data = YAML(typ="safe").load(header[1])
            if isinstance(data, dict) and isinstance(status := data.get("status"), str) and status.strip():
                return f"{path.name} · {status}"
    except (OSError, ValueError, YAMLError):
        pass
    return f"{path.name} · SPEC 상태 확인 불가"


def show_stories(stories):
    print("\n진행·리뷰 스토리")
    if not isinstance(stories, dict):
        print("스토리 상태 확인 불가 (development_status)")
        return
    rows = []
    for key, status in stories.items():
        if not isinstance(key, str):
            rows.append("스토리 키 확인 불가")
            continue
        number = re.match(r"^(\d+-\d+[a-z]?)-", key)
        if number and status in ("in-progress", "review"):
            rows.append(f"{key} [{status}] · {spec_status(number[1])}")
    print("\n".join(rows) or "없음")


def show_actions(actions):
    print("\n열린 회고 액션")
    if not isinstance(actions, list):
        print("회고 액션 확인 불가 (action_items)")
        return
    rows = []
    for index, item in enumerate(actions, start=1):
        if isinstance(item, dict) and item.get("status") == "done":
            continue
        if (not isinstance(item, dict) or item.get("status") not in ("open", "in-progress")
                or not all(isinstance(item.get(k), str) and item[k].strip() for k in ("id", "action"))):
            rows.append(f"회고 액션 {index}번째 항목 확인 불가")
            continue
        rows.append(f"{item['id']} [{item['status']}] {item['action']}")
    print("\n".join(rows) or "없음")


def show_sprint():
    data = YAML(typ="safe").load((ARTIFACTS / "sprint-status.yaml").read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("sprint-status.yaml")
    show_stories(data.get("development_status"))
    show_actions(data.get("action_items"))


def origin_repo():
    origin = query("git", "remote", "get-url", "origin").strip()
    # git@host:owner/repo와 URL 형식의 HTTPS·SSH remote 모두 gh의 HOST/OWNER/REPO로 전달한다.
    if "://" not in origin:
        scp = re.fullmatch(r"(?:[^@/]+@)?([\w.-]+):([^:]+)", origin)
        if not scp:
            raise ValueError("origin")
        origin = f"ssh://{scp[1]}/{scp[2]}"
    url = urlsplit(origin)
    path = url.path.strip("/").removesuffix(".git")
    if url.scheme not in ("https", "http", "ssh", "git") or not url.hostname or not re.fullmatch(r"[\w.-]+/[\w.-]+", path):
        raise ValueError("origin")
    return f"{url.hostname}/{path}"


def ledger_attempts():
    """판정 표에서 확인한 시도와 전체 행 판독 성공 여부를 반환한다."""
    try:
        lines = (ARTIFACTS / "ci-run-verdicts.md").read_text(encoding="utf-8").splitlines()
    except (OSError, ValueError):
        return set(), False
    attempts = set()
    column = None
    separator = False
    complete = True
    for line in lines:
        if not line.strip().startswith("|"):
            if column is not None:
                break
            continue
        cells = [cell.strip().strip("`").strip() for cell in line.strip().strip("|").split("|")]
        if column is None:
            if "날짜(UTC)" in cells and "run/attempt" in cells:
                column = cells.index("run/attempt")
        elif not separator:
            if not all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells):
                return set(), False
            separator = True
        elif column < len(cells) and (key := re.fullmatch(r"(\d+)\s*/\s*(\d+)", cells[column])):
            attempts.add(f"{key[1]}/{key[2]}")
        else:
            complete = False
    return attempts, separator and complete


def show_ci():
    print("\ndevelop push CI (최근 3건)")
    raw = query("gh", "run", "list", "--repo", origin_repo(), "--workflow", "ci.yml",
                "--branch", "develop", "--event", "push", "--limit", "3",
                "--json", "databaseId,attempt,status,conclusion")
    runs = json.loads(raw)
    if not isinstance(runs, list):
        raise ValueError("GitHub 응답")
    if not runs:
        print("없음")
        return
    attempts, complete = ledger_attempts()
    for run in runs:
        if (not isinstance(run, dict) or not all(type(run.get(k)) is int and run[k] > 0 for k in ("databaseId", "attempt"))
                or not isinstance(run.get("status"), str) or not run["status"]
                or "conclusion" not in run or not isinstance(run["conclusion"], (str, type(None)))):
            print("GitHub 실행 항목 확인 불가")
            continue
        key = f"{run['databaseId']}/{run['attempt']}"
        verdict = "있음" if key in attempts else ("없음" if complete else "확인 불가")
        print(f"{key} · {run['status']} · {run['conclusion'] or '결말 대기'} · 원장 기록 {verdict}")


def main():
    for title, args in [
        ("현재 브랜치·작업 트리", ("--no-optional-locks", "-c", "core.quotepath=false", "status", "--short", "--branch", "--untracked-files=all")),
        ("최근 커밋", ("log", "--oneline", "-5")),
    ]:
        print(title)
        try:
            print(query("git", *args))
        except (OSError, subprocess.SubprocessError):
            print("Git 조회 불가")
    try:
        show_sprint()
    except (OSError, ValueError, KeyError, TypeError, AttributeError, YAMLError):
        print("스프린트 원장 확인 불가 (sprint-status.yaml)")
    try:
        show_ci()
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError):
        print("GitHub 조회 불가 (gh 설치·인증·네트워크 또는 응답 확인 필요)")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
