"""Minimal parser for the generated ImpulseM .proto files."""
import re
from pathlib import Path
from typing import Dict, List, Optional, Tuple

MessageInfo = Dict[str, Dict[str, object]]
Messages = Dict[str, Dict[str, MessageInfo]]
Services = Dict[Tuple[str, str], Dict[str, Tuple[str, str]]]

FIELD_RE: re.Pattern = re.compile(r"^(?:(optional|repeated)\s+)?([A-Za-z_][\w.]*(?:<[^>]*>)?)\s+(\w+)\s*=\s*(\d+)\s*(?:\[[^\]]*\])?\s*;$")
RPC_RE: re.Pattern = re.compile(r"^rpc\s+(\w+)\s*\(\s*(?:stream\s+)?([\w.]+)\s*\)\s*returns\s*\(\s*(?:stream\s+)?([\w.]+)\s*\)\s*(?:;|\{\s*\})$")


def strip_comments(text: str) -> str:
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def new_message(file: str, name: str) -> Dict[str, object]:
    return {"fields": {}, "oneof": {}, "file": file, "name": name}


def parse_proto(path: Path, root: Path) -> Tuple[str, Messages, Services]:
    """Returns (package, {package: {message: {"fields", "oneof", "file", "name"}}}, services)."""
    package: str = ""
    messages: Dict[str, MessageInfo] = {}
    services: Dict[Tuple[str, str], Dict[str, Tuple[str, str]]] = {}
    # Stack of open scopes: ("message", name) | ("oneof", name) | ("service", name)
    stack: List[Tuple[str, str]] = []
    m: Optional[re.Match] = None
    relFile: str = path.relative_to(root).as_posix()
    for rawLine in strip_comments(path.read_text(encoding="utf-8")).split("\n"):
        line: str = rawLine.strip()
        if line.startswith("//"):
            continue
        line = re.sub(r"\s*//.*$", "", line)
        if not line:
            continue
        m = re.match(r"^package\s+([\w.]+)\s*;$", line)
        if m:
            package = m.group(1)
            continue
        if re.match(r"^(syntax|option|import|reserved)\b", line) and line.endswith(";"):
            continue
        m = re.match(r"^message\s+(\w+)\s*\{(.*)\}$", line)
        if m:
            messages[m.group(1)] = new_message(relFile, m.group(1))
            for statement in m.group(2).split(";"):
                statement = statement.strip()
                if not statement:
                    continue
                fm = FIELD_RE.match(statement + ";")
                if not fm:
                    raise ValueError("cannot parse inline field in %s: %s" % (path, line))
                messages[m.group(1)]["fields"][fm.group(3)] = (int(fm.group(4)), fm.group(2), fm.group(1) or "")
            continue
        m = re.match(r"^message\s+(\w+)\s*\{$", line)
        if m:
            name: str = m.group(1)
            messages[name] = new_message(relFile, name)
            stack.append(("message", name))
            continue
        m = re.match(r"^oneof\s+(\w+)\s*\{$", line)
        if m:
            stack.append(("oneof", m.group(1)))
            continue
        m = re.match(r"^service\s+(\w+)\s*\{$", line)
        if m:
            services[(package, m.group(1))] = {}
            stack.append(("service", m.group(1)))
            continue
        if line == "}":
            if not stack:
                raise ValueError("unbalanced } in " + str(path))
            stack.pop()
            continue
        if not stack:
            raise ValueError("unexpected top-level line in %s: %s" % (path, line))
        kind, scopeName = stack[-1]
        if kind == "service":
            m = RPC_RE.match(line)
            if not m:
                raise ValueError("cannot parse rpc in %s: %s" % (path, line))
            services[(package, scopeName)][m.group(1)] = (m.group(2), m.group(3))
            continue
        m = FIELD_RE.match(line)
        if not m:
            raise ValueError("cannot parse line in %s: %s" % (path, line))
        label, fieldType, fieldName, number = m.group(1) or "", m.group(2), m.group(3), int(m.group(4))
        if kind == "oneof":
            owner: str = [n for k, n in stack if k == "message"][-1]
            messages[owner]["oneof"][fieldName] = (number, fieldType)
        elif kind == "message":
            messages[scopeName]["fields"][fieldName] = (number, fieldType, label)
        else:
            raise ValueError("field outside message in %s: %s" % (path, line))
    if stack:
        raise ValueError("unterminated block in " + str(path))
    return package, {package: messages}, services


def parse_dir(root: Path) -> Tuple[Messages, Services]:
    """Parses every .proto in root and root/v1. Messages are merged per package."""
    allMessages: Messages = {}
    allServices: Services = {}
    files: List[Path] = sorted(root.glob("*.proto")) + sorted((root / "v1").glob("*.proto"))
    for f in files:
        package, messages, services = parse_proto(f, root)
        for pkg, msgs in messages.items():
            target = allMessages.setdefault(pkg, {})
            for name, info in msgs.items():
                if name in target:
                    raise ValueError("duplicate message %s.%s" % (pkg, name))
                target[name] = info
        allServices.update(services)
    return allMessages, allServices
