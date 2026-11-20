# /// script
# requires-python = ">=3.11"
# dependencies = ["ruamel.yaml>=0.18,<0.20"]
# ///
"""세션 상태 조회의 경계 조건을 임시 원장과 GitHub 응답으로 검증한다."""
import contextlib
import importlib
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

status = importlib.import_module("session-status")


class SessionStatusTest(unittest.TestCase):
    def setUp(self):
        self.artifacts = Path(self.enterContext(TemporaryDirectory()))
        self.enterContext(patch.object(status, "ARTIFACTS", self.artifacts))

    def write(self, name, body):
        (self.artifacts / name).write_text(body, encoding="utf-8")

    def output(self, function):
        with contextlib.redirect_stdout(io.StringIO()) as result:
            function()
        return result.getvalue()

    def test_empty_stories_and_actions(self):
        self.write("sprint-status.yaml", "development_status: {epic-1: in-progress}\naction_items: []\n")
        self.assertEqual(self.output(status.show_sprint), "\n진행·리뷰 스토리\n없음\n\n열린 회고 액션\n없음\n")

    def test_bad_items_leave_other_sections_and_multiline_action_visible(self):
        self.write("sprint-status.yaml", """
development_status: []
action_items:
  - {id: broken, status: open}
  - {id: visible, status: in-progress, action: "설명의 첫 줄
      그리고 끝 줄"}
""")
        result = self.output(status.show_sprint)
        self.assertIn("스토리 상태 확인 불가", result)
        self.assertIn("1번째 항목 확인 불가", result)
        self.assertIn("visible [in-progress] 설명의 첫 줄 그리고 끝 줄", result)

    def test_missing_ambiguous_and_unreadable_specs(self):
        self.write("sprint-status.yaml", """
development_status: {1-1-story: review, 1-2-story: in-progress, 1-3-story: review, 1-4-story: review}
""")
        for name in ("spec-1-1-a.md", "spec-1-1-b.md", "spec-1-2-a.md"):
            self.write(name, "---\nstatus: approved\n---\n")
        self.write("spec-1-3-a.md", "---\nstatus: [\n---\nstatus: wrong\n")
        result = self.output(status.show_sprint)
        self.assertIn("후보 spec-1-1-a.md, spec-1-1-b.md", result)
        self.assertIn("1-2-story [in-progress] · spec-1-2-a.md · approved", result)
        self.assertIn("spec-1-3-a.md · SPEC 상태 확인 불가", result)
        self.assertIn("1-4-story [review] · SPEC 확인 불가 (누락)", result)
        self.assertIn("회고 액션 확인 불가 (action_items)", result)

    def test_attempt_column_and_origin(self):
        self.write("ci-run-verdicts.md", """
| 날짜(UTC) | run/attempt | 후속 |
|---|---|---|
| 2026-11-01 | `123 / 1` | 123/2 |
""")
        runs = [
            {"databaseId": 123, "attempt": 1, "status": "completed", "conclusion": "failure"},
            {"databaseId": 123, "attempt": 2, "status": "in_progress", "conclusion": ""},
            {},
        ]
        with patch.dict("os.environ", {"GH_REPO": "elsewhere/project"}), patch.object(
            status, "query", side_effect=["git@github.com:example/project.git", json.dumps(runs)]
        ) as query:
            result = self.output(status.show_ci)
        args = query.call_args.args
        self.assertEqual(args[args.index("--repo") + 1], "github.com/example/project")
        self.assertIn("123/1 · completed · failure · 원장 기록 있음", result)
        self.assertIn("123/2 · in_progress · 결말 대기 · 원장 기록 없음", result)
        self.assertIn("GitHub 실행 항목 확인 불가", result)

    def test_unreadable_ledger_and_github_failures(self):
        self.write("ci-run-verdicts.md", "표 없음\n")
        self.assertEqual(status.ledger_attempts(), (set(), False))
        self.write("ci-run-verdicts.md", "| 날짜(UTC) | run/attempt |\n|---|---|\n| 2026-11-01 | ? |\n")
        self.assertEqual(status.ledger_attempts(), (set(), False))
        self.write("sprint-status.yaml", "development_status: {}\naction_items: []\n")
        for failure in (FileNotFoundError(), "{}", "null"):
            with self.subTest(response=failure), patch.object(
                status, "query", side_effect=["브랜치", "커밋", "https://github.com/example/project.git", failure]
            ):
                result = self.output(status.main)
                self.assertIn("GitHub 조회 불가", result)
                self.assertIn("열린 회고 액션\n없음", result)
                self.assertIn("브랜치", result)


if __name__ == "__main__":
    unittest.main()
