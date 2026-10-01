"""Generates the TL layer 229 to ImpulseM proto mapping.

Usage: python Tools/impulse/gen_tl_proto_map.py
"""
import gzip
import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

import proto_parser

TOOLS_DIR: Path = Path(__file__).resolve().parent
REPO_ROOT: Path = TOOLS_DIR.parent.parent
SCHEMA_PATH: Path = REPO_ROOT / "TMessagesProj_AppTests" / "tlscheme" / "229.json"
PROTOS_DIR: Path = TOOLS_DIR / "protos"
OUT_JSON: Path = REPO_ROOT / "ImpulseTransport" / "src" / "main" / "resources" / "impulse" / "tl-proto-229.json.gz"
OUT_MD: Path = TOOLS_DIR / "coverage-229.md"

LAYER: int = 229
EXCLUDED_CONSTRUCTORS: List[str] = ["boolFalse", "boolTrue", "true", "vector"]
CAPTURE_NAMES: List[str] = ["session_token", "refresh_token", "livekit_url", "livekit_token"]
PRIMITIVES: List[str] = ["int", "long", "double", "string", "bytes", "int128", "int256"]
PRIMITIVE_LIST_ELEMS: Dict[str, str] = {
    "int": "IntResponse",
    "long": "LongResponse",
    "double": "DoubleResponse",
    "string": "StringResponse",
    "bytes": "BytesResponse",
}

Param = Dict[str, Any]


def pascal(s: str) -> str:
    out: List[str] = []
    upNext: bool = True
    for ch in s:
        if ch == "_" or ch == ".":
            upNext = True
            continue
        out.append(ch.upper() if upNext else ch)
        upNext = False
    return "".join(out)


def snake(s: str) -> str:
    out: List[str] = []
    for i, ch in enumerate(s):
        if ch.isupper() and i > 0 and not s[i - 1].isupper():
            out.append("_")
        out.append(ch.lower())
    return "".join(out).replace(".", "_")


def type_message(tlType: str) -> str:
    return pascal(tlType.replace(".", "_"))


def parse_tl_type(tlType: str, where: str) -> Dict[str, Any]:
    """Parses a TL param type into a kind dict (with optional flag)."""
    if tlType.startswith("!") or "%" in tlType or tlType.startswith("vector<"):
        raise ValueError("unsupported TL type %r at %s" % (tlType, where))
    if tlType == "#":
        return {"kind": "flags"}
    m: Optional[re.Match] = re.match(r"^(flags2?)\.(\d+)\?(.+)$", tlType)
    if m:
        inner: Dict[str, Any] = parse_tl_type(m.group(3), where)
        inner["flag"] = [m.group(1), int(m.group(2))]
        return inner
    if tlType == "true":
        return {"kind": "true"}
    if tlType == "Bool":
        return {"kind": "Bool"}
    if tlType in PRIMITIVES:
        return {"kind": tlType}
    m = re.match(r"^Vector<(.+)>$", tlType)
    if m:
        return {"kind": "vector", "elem": parse_tl_type(m.group(1), where)}
    return {"kind": "object", "type": tlType}


def map_params(
    tlParams: List[Dict[str, str]],
    message: Dict[str, Any],
    where: str,
    ignored: List[str],
    allowCapture: bool,
    overlayFields: Set[Tuple[str, str, str]],
) -> Tuple[List[Param], List[Dict[str, Any]]]:
    fields: Dict[str, Tuple[int, str, str]] = message["fields"]
    tlNames: List[str] = [p["name"] for p in tlParams]
    parsed: List[Param] = []
    for p in tlParams:
        entry: Param = parse_tl_type(p["type"], where + "." + p["name"])
        entry["name"] = p["name"]
        parsed.append(entry)
    for entry in parsed:
        if entry["kind"] == "flags":
            continue
        name: str = entry["name"]
        if name in fields:
            entry["field"] = fields[name][0]
        elif name.startswith("has_") and entry["kind"] in ("true", "Bool") and name[4:] in tlNames:
            entry["derived_from"] = name[4:]
        else:
            raise ValueError("no proto field for %s.%s (message fields: %s)" % (where, name, sorted(fields)))
    capture: List[Dict[str, Any]] = []
    for fieldName, info in fields.items():
        if fieldName not in tlNames:
            # Only fields listed in overlays.json may exceed the TL params.
            if (message["file"], message["name"], fieldName) not in overlayFields:
                raise ValueError("unexpected extra proto field %s in %s" % (fieldName, where))
            if allowCapture:
                if fieldName not in CAPTURE_NAMES:
                    raise ValueError("overlay field %s in response message %s is not a capture field" % (fieldName, where))
                capture.append({"field": info[0], "name": fieldName})
            else:
                ignored.append("%s: `%s` = field %d" % (where, fieldName, info[0]))
    capture.sort(key=lambda c: c["field"])
    return parsed, capture


def result_of(
    tlResult: str,
    rpcReturn: str,
    where: str,
) -> Dict[str, Any]:
    if rpcReturn == "BoolResponse":
        if tlResult != "Bool":
            raise ValueError("%s: BoolResponse for TL result %s" % (where, tlResult))
        return {"kind": "bool"}
    if rpcReturn == "GetContactSignUpNotificationResponse":
        return {"kind": "bool_overlay"}
    if rpcReturn == "IntResponse" or rpcReturn == "LongResponse":
        kind: str = "int" if rpcReturn == "IntResponse" else "long"
        if tlResult != kind:
            raise ValueError("%s: %s for TL result %s" % (where, rpcReturn, tlResult))
        return {"kind": kind}
    m: Optional[re.Match] = re.match(r"^Vector<(.+)>$", tlResult)
    if m:
        elem: Dict[str, Any] = parse_tl_type(m.group(1), where)
        if elem["kind"] == "object":
            expected: str = "v1." + type_message(elem["type"]) + "List"
        elif elem["kind"] in PRIMITIVE_LIST_ELEMS:
            expected = PRIMITIVE_LIST_ELEMS[elem["kind"]] + "List"
        else:
            raise ValueError("%s: unsupported list elem %s" % (where, elem))
        if rpcReturn != expected:
            raise ValueError("%s: expected return %s, got %s" % (where, expected, rpcReturn))
        return {"kind": "list", "elem": elem}
    expected = "v1." + type_message(tlResult)
    if rpcReturn != expected:
        raise ValueError("%s: expected return %s, got %s" % (where, expected, rpcReturn))
    return {"kind": "object", "type": tlResult}


def load_overlay_fields(path: Path) -> Set[Tuple[str, str, str]]:
    """Returns (file, message, field) for every kind=field overlay entry."""
    doc: Dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return {(e["file"], e["message"], e["name"]) for e in doc["entries"] if e["kind"] == "field"}


def build(schema: Dict[str, Any], protosDir: Path = PROTOS_DIR) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    messages, services = proto_parser.parse_dir(protosDir)
    v1: Dict[str, Any] = messages["v1"]

    byType: Dict[str, List[Dict[str, Any]]] = {}
    for c in schema["constructors"]:
        if c["predicate"] in EXCLUDED_CONSTRUCTORS:
            continue
        byType.setdefault(c["type"], []).append(c)

    overlayFields: Set[Tuple[str, str, str]] = load_overlay_fields(protosDir / "overlays.json")
    ignoredExtensions: List[str] = []
    constructors: List[Dict[str, Any]] = []
    types: List[Dict[str, Any]] = []
    for tlType, ctors in byType.items():
        typeMsg: str = type_message(tlType)
        polymorphic: bool = len(ctors) > 1
        arms: Dict[str, int] = {}
        for c in ctors:
            where: str = "constructor " + c["predicate"]
            if polymorphic:
                armMsg: str = pascal(c["predicate"])
                if armMsg == typeMsg:
                    armMsg += "Value"
                if typeMsg not in v1:
                    raise ValueError("%s: type message %s missing" % (where, typeMsg))
                oneof: Dict[str, Tuple[int, str]] = v1[typeMsg]["oneof"]
                key: str = snake(c["predicate"])
                if key not in oneof:
                    raise ValueError("%s: no oneof arm %s in %s" % (where, key, typeMsg))
                armNum, armType = oneof[key]
                if armType != armMsg:
                    raise ValueError("%s: arm type %s != %s" % (where, armType, armMsg))
                arms[str(armNum)] = int(c["id"])
                protoName: str = armMsg
                arm: Optional[int] = armNum
            else:
                protoName = typeMsg
                arm = None
            if protoName not in v1:
                raise ValueError("%s: message %s missing" % (where, protoName))
            params, capture = map_params(c["params"], v1[protoName], where, ignoredExtensions, True, overlayFields)
            constructors.append({
                "id": int(c["id"]),
                "predicate": c["predicate"],
                "type": tlType,
                "proto": protoName,
                "arm": arm,
                "params": params,
                "capture": capture,
            })
        types.append({
            "name": tlType,
            "proto": typeMsg,
            "polymorphic": polymorphic,
            "arms": arms,
        })

    methods: List[Dict[str, Any]] = []
    excludedMethods: List[str] = []
    for m in schema["methods"]:
        name: str = m["method"]
        if "." not in name:
            excludedMethods.append(name)
            continue
        ns, rest = name.split(".", 1)
        package: str = "v1." + ns
        service: str = pascal(ns)
        rpcName: str = pascal(rest)
        where = "method " + name
        if package not in messages:
            raise ValueError("%s: package %s missing" % (where, package))
        reqName: str = rpcName + "Request"
        if reqName not in messages[package]:
            raise ValueError("%s: request %s.%s missing" % (where, package, reqName))
        rpcs: Dict[str, Tuple[str, str]] = services.get((package, service), {})
        if rpcName not in rpcs:
            raise ValueError("%s: rpc %s.%s/%s missing" % (where, package, service, rpcName))
        declaredReq, declaredRes = rpcs[rpcName]
        if declaredReq != reqName:
            raise ValueError("%s: rpc request %s != %s" % (where, declaredReq, reqName))
        params, capture = map_params(m["params"], messages[package][reqName], where, ignoredExtensions, False, overlayFields)
        if capture:
            raise ValueError("%s: unexpected capture in request" % where)
        methods.append({
            "id": int(m["id"]),
            "method": name,
            "path": "/%s.%s/%s" % (package, service, rpcName),
            "params": params,
            "result": result_of(m["type"], declaredRes, where),
        })

    mapping: Dict[str, Any] = {
        "layer": LAYER,
        "constructors": constructors,
        "types": types,
        "methods": methods,
    }
    extra: Dict[str, Any] = {"excludedMethods": excludedMethods, "ignoredExtensions": ignoredExtensions}
    return mapping, extra


def write_report(
    schema: Dict[str, Any],
    mapping: Dict[str, Any],
    extra: Dict[str, Any],
    out: Path,
) -> None:
    lines: List[str] = ["# TL layer %d to ImpulseM proto coverage" % LAYER, ""]
    lines.append("- Schema constructors: %d" % len(schema["constructors"]))
    lines.append("- Mapped constructors: %d" % len(mapping["constructors"]))
    lines.append("- Mapped types: %d (polymorphic: %d)" % (len(mapping["types"]), len([t for t in mapping["types"] if t["polymorphic"]])))
    lines.append("- Schema methods: %d" % len(schema["methods"]))
    lines.append("- Mapped methods: %d" % len(mapping["methods"]))
    lines.append("")
    lines.append("## Excluded constructors")
    lines.append("")
    for n in EXCLUDED_CONSTRUCTORS:
        lines.append("- `%s`" % n)
    lines.append("")
    lines.append("## Excluded methods (no namespace)")
    lines.append("")
    for n in extra["excludedMethods"]:
        lines.append("- `%s`" % n)
    lines.append("")
    lines.append("## Derived params (`derived_from`)")
    lines.append("")
    derived: List[str] = []
    for c in mapping["constructors"]:
        for p in c["params"]:
            if "derived_from" in p:
                derived.append("- constructor `%s`: `%s` derived from `%s`" % (c["predicate"], p["name"], p["derived_from"]))
    for m in mapping["methods"]:
        for p in m["params"]:
            if "derived_from" in p:
                derived.append("- method `%s`: `%s` derived from `%s`" % (m["method"], p["name"], p["derived_from"]))
    lines.extend(derived if derived else ["(none)"])
    lines.append("")
    lines.append("## Captures")
    lines.append("")
    captures: List[str] = []
    for c in mapping["constructors"]:
        for cap in c["capture"]:
            captures.append("- `%s` (%s): `%s` = field %d" % (c["predicate"], c["proto"], cap["name"], cap["field"]))
    lines.extend(captures if captures else ["(none)"])
    lines.append("")
    lines.append("## Extension fields ignored (not TL params, not captured)")
    lines.append("")
    lines.extend(["- " + e for e in extra["ignoredExtensions"]] if extra["ignoredExtensions"] else ["(none)"])
    lines.append("")
    lines.append("## Result kinds")
    lines.append("")
    kinds: Dict[str, int] = {}
    for m in mapping["methods"]:
        kinds[m["result"]["kind"]] = kinds.get(m["result"]["kind"], 0) + 1
    for k in sorted(kinds):
        lines.append("- %s: %d" % (k, kinds[k]))
    lines.append("")
    out.write_text("\n".join(lines), encoding="utf-8", newline="\n")


def generate(outJson: Path = OUT_JSON, outMd: Path = OUT_MD) -> Dict[str, Any]:
    schema: Dict[str, Any] = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    mapping, extra = build(schema)
    data: bytes = json.dumps(mapping, sort_keys=True, separators=(",", ":")).encode("utf-8")
    outJson.parent.mkdir(parents=True, exist_ok=True)
    outJson.write_bytes(gzip.compress(data, compresslevel=9, mtime=0))
    outMd.parent.mkdir(parents=True, exist_ok=True)
    write_report(schema, mapping, extra, outMd)
    return mapping


def main() -> None:
    mapping: Dict[str, Any] = generate()
    print("constructors: %d" % len(mapping["constructors"]))
    print("types: %d" % len(mapping["types"]))
    print("methods: %d" % len(mapping["methods"]))
    print("wrote %s" % OUT_JSON)
    print("wrote %s" % OUT_MD)


if __name__ == "__main__":
    main()
