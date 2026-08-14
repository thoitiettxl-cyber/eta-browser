#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
MODULE_PATH = Path(__file__).with_name("validate-generated-skill.py")
SPEC = importlib.util.spec_from_file_location("validate_generated_skill", MODULE_PATH)
assert SPEC and SPEC.loader
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)

VALID_SKILL = """---
name: fixture-reader
description: Read the public example page through Eta Browser when a repeatable fixture check is requested.
compatibility: Requires a paired Eta Browser, eta_browser_use, and the eta-browser runtime skill.
---

# Fixture Reader

## Inputs

- `expected_heading`: public heading text to verify.

## Preconditions

Start at `https://example.com` with the Eta Browser bridge available.

## Success contract

The requested public heading is present in readable page content.

## Workflow

1. Use `eta_browser_use` serially to inspect page information.
2. Call `observe` before any interaction.
3. Read the page and stop when the expected heading is confirmed.

## Recovery and stop conditions

After navigation or stale targeting, call `observe` again. If the same step fails twice without progress, stop and report the blocker.

## Human handoff

Use `request_help` for any newly encountered login, CAPTCHA, OTP, payment, consent, or user-only step.

## Output contract

Return the observed heading and whether the success contract passed.

## Known limitations

This workflow verifies only public readable content on the example page.
"""


class ValidatorTests(unittest.TestCase):
    def make_skill(self, content: str = VALID_SKILL) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temp = tempfile.TemporaryDirectory()
        skill_dir = Path(temp.name) / "fixture-reader"
        skill_dir.mkdir()
        (skill_dir / "SKILL.md").write_text(content, encoding="utf-8")
        return temp, skill_dir

    def messages(self, skill_dir: Path) -> str:
        return "\n".join(VALIDATOR.validate(skill_dir))

    def test_valid_skill_passes(self) -> None:
        temp, skill_dir = self.make_skill()
        self.addCleanup(temp.cleanup)
        self.assertEqual([], VALIDATOR.validate(skill_dir))

    def test_ephemeral_ref_fails(self) -> None:
        content = VALID_SKILL.replace("Read the page", "Use @e7 and read the page")
        temp, skill_dir = self.make_skill(content)
        self.addCleanup(temp.cleanup)
        self.assertIn("ephemeral @eN ref", self.messages(skill_dir))

    def test_missing_required_section_fails(self) -> None:
        content = VALID_SKILL.split("## Known limitations", 1)[0]
        temp, skill_dir = self.make_skill(content)
        self.addCleanup(temp.cleanup)
        self.assertIn("missing or empty section '## Known limitations'", self.messages(skill_dir))

    def test_alternate_browser_runtime_fails(self) -> None:
        content = VALID_SKILL.replace("Use `eta_browser_use` serially", "Use Playwright, then use `eta_browser_use` serially")
        temp, skill_dir = self.make_skill(content)
        self.addCleanup(temp.cleanup)
        self.assertIn("alternate browser/debugger automation", self.messages(skill_dir))

    def test_extra_file_fails(self) -> None:
        temp, skill_dir = self.make_skill()
        self.addCleanup(temp.cleanup)
        (skill_dir / "notes.md").write_text("extra\n", encoding="utf-8")
        self.assertIn("must contain only SKILL.md", self.messages(skill_dir))

    def test_missing_request_help_fails(self) -> None:
        content = VALID_SKILL.replace("Use `request_help`", "Ask the user in chat")
        temp, skill_dir = self.make_skill(content)
        self.addCleanup(temp.cleanup)
        self.assertIn("Human handoff omits request_help", self.messages(skill_dir))

    def test_discovery_path_fails(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        skill_dir = Path(temp.name) / ".pi" / "skills" / "fixture-reader"
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_text(VALID_SKILL, encoding="utf-8")
        self.assertIn("already inside a Pi discovery path", self.messages(skill_dir))

    def test_credential_material_fails(self) -> None:
        content = VALID_SKILL.replace("public heading text", "Authorization: Bearer abcdefghijk")
        temp, skill_dir = self.make_skill(content)
        self.addCleanup(temp.cleanup)
        self.assertIn("authorization header material", self.messages(skill_dir))


if __name__ == "__main__":
    unittest.main()
