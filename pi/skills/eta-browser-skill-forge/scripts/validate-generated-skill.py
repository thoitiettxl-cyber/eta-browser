#!/usr/bin/env python3
"""Validate the minimal contract for a Forge-generated Eta Browser skill."""

from __future__ import annotations

import re
import sys
from pathlib import Path

AUTHORITY = "pi/skills/eta-browser-skill-forge/SKILL.md"
REQUIRED_HEADINGS = (
    "Inputs",
    "Preconditions",
    "Success contract",
    "Workflow",
    "Recovery and stop conditions",
    "Human handoff",
    "Output contract",
    "Known limitations",
)
NAME_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
REF_RE = re.compile(r"@e[1-9][0-9]*\b")
FORBIDDEN_PATTERNS = (
    (re.compile(r"\bAuthorization\s*:", re.IGNORECASE), "authorization header material"),
    (re.compile(r"\bCookie\s*:", re.IGNORECASE), "cookie material"),
    (re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]{8,}", re.IGNORECASE), "bearer credential material"),
    (re.compile(r"data:image/[A-Za-z0-9.+-]+;base64,", re.IGNORECASE), "inline screenshot data"),
    (re.compile(r"\b(?:lease_id|request_id|lease-id|request-id)\b", re.IGNORECASE), "transport identifiers"),
    (re.compile(r"(?:~|/root)/\.pi/agent/skills(?:/|\b)"), "automatic installation path"),
    (re.compile(r"\b(?:CDP|playwright|puppeteer|selenium|webdriver)\b|chrome\.debugger|Runtime\.evaluate", re.IGNORECASE), "alternate browser/debugger automation"),
    (re.compile(r"\b(?:XMLHttpRequest|fetch)\s*\(|javascript:", re.IGNORECASE), "browser scripting or direct network code"),
)


def issue(path: Path, message: str, next_action: str) -> str:
    return f"{path}: {message} ({AUTHORITY}). Next: {next_action}."


def parse_frontmatter(text: str, path: Path) -> tuple[dict[str, str], list[str]]:
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, [issue(path, "missing YAML frontmatter", "start SKILL.md with the Forge template frontmatter")]
    try:
        end = next(index for index, line in enumerate(lines[1:], start=1) if line.strip() == "---")
    except StopIteration:
        return {}, [issue(path, "unclosed YAML frontmatter", "add the closing --- delimiter")]

    values: dict[str, str] = {}
    problems: list[str] = []
    for number, raw in enumerate(lines[1:end], start=2):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw != raw.lstrip() or ":" not in raw:
            problems.append(issue(path, f"unsupported frontmatter line {number}", "use only top-level key: value fields from the template"))
            continue
        key, value = raw.split(":", 1)
        values[key.strip()] = value.strip().strip("\"'")
    return values, problems


def sections(text: str) -> dict[str, str]:
    matches = list(re.finditer(r"^##\s+(.+?)\s*$", text, re.MULTILINE))
    result: dict[str, str] = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        result[match.group(1).strip()] = text[match.end():end].strip()
    return result


def validate(skill_dir: Path) -> list[str]:
    problems: list[str] = []
    skill_file = skill_dir / "SKILL.md"
    if re.search(r"/(?:\.pi|\.agents)/skills(?:/|$)", skill_dir.as_posix()):
        problems.append(issue(skill_dir, "generated skill is already inside a Pi discovery path", "move it to a separate review directory before validation"))
    if not skill_file.is_file():
        return [issue(skill_dir, "missing SKILL.md", "create the generated skill from the Forge template")]

    extras = sorted(path.name for path in skill_dir.iterdir() if path.name != "SKILL.md")
    if extras:
        problems.append(issue(skill_dir, f"generated skill must contain only SKILL.md; found {', '.join(extras)}", "remove unrelated files and keep the minimal review artifact"))

    try:
        text = skill_file.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return problems + [issue(skill_file, "SKILL.md is not UTF-8 text", "rewrite it as UTF-8 Markdown")]

    frontmatter, frontmatter_problems = parse_frontmatter(text, skill_file)
    problems.extend(frontmatter_problems)

    name = frontmatter.get("name", "")
    if not NAME_RE.fullmatch(name) or not 1 <= len(name) <= 64:
        problems.append(issue(skill_file, "invalid skill name", "use 1-64 lowercase letters, digits, and single hyphens"))
    elif skill_dir.name != name:
        problems.append(issue(skill_dir, f"directory name does not match {name!r}", "make the directory and frontmatter names match"))

    description = frontmatter.get("description", "")
    if not description or len(description) > 1024:
        problems.append(issue(skill_file, "description is missing or too long", "state the repeated task, target site, and trigger conditions in at most 1024 characters"))

    compatibility = frontmatter.get("compatibility", "")
    if "eta_browser_use" not in compatibility or "eta-browser" not in compatibility:
        problems.append(issue(skill_file, "compatibility omits Eta Browser requirements", "use the compatibility line from the Forge template"))

    body_sections = sections(text)
    for heading in REQUIRED_HEADINGS:
        if not body_sections.get(heading):
            problems.append(issue(skill_file, f"missing or empty section '## {heading}'", "complete the section from the Forge template"))

    workflow = body_sections.get("Workflow", "")
    if workflow and ("eta_browser_use" not in workflow or "observe" not in workflow):
        problems.append(issue(skill_file, "Workflow omits eta_browser_use or current observation", "describe serialized eta_browser_use steps and observe before interaction"))

    recovery = body_sections.get("Recovery and stop conditions", "")
    if recovery:
        has_stop = re.search(r"\b(?:stop|stops|stopping)\b|dừng", recovery, re.IGNORECASE)
        has_two = re.search(r"\b(?:twice|two)\b|\b2\b|hai lần", recovery, re.IGNORECASE)
        if "observe" not in recovery or not has_stop or not has_two:
            problems.append(issue(skill_file, "Recovery lacks re-observation and a two-failure stop rule", "require observe again and stop after the same step fails twice without progress"))

    handoff = body_sections.get("Human handoff", "")
    if handoff and "request_help" not in handoff:
        problems.append(issue(skill_file, "Human handoff omits request_help", "use request_help for user-only steps"))

    if REF_RE.search(text):
        problems.append(issue(skill_file, "contains an ephemeral @eN ref", "describe the semantic target and obtain a current ref from observe at runtime"))

    for pattern, label in FORBIDDEN_PATTERNS:
        if pattern.search(text):
            problems.append(issue(skill_file, f"contains forbidden {label}", "remove it from the generated skill"))

    return problems


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"Usage: {Path(argv[0]).name} <generated-skill-directory>", file=sys.stderr)
        return 2
    skill_dir = Path(argv[1]).expanduser().resolve()
    if not skill_dir.is_dir():
        print(f"{skill_dir}: directory does not exist", file=sys.stderr)
        return 2
    problems = validate(skill_dir)
    if problems:
        for problem in problems:
            print(f"ERROR: {problem}", file=sys.stderr)
        return 1
    print(f"OK: {skill_dir} satisfies the minimal Eta Browser generated-skill contract")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
