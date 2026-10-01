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
HISTORY_DIR: Path = REPO_ROOT / "TMessagesProj_AppTests" / "tlscheme"

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


def param_shape(entry: Param) -> Dict[str, Any]:
    """The layout-relevant part of a param: everything except proto field numbers."""
    shape: Dict[str, Any] = {"kind": entry["kind"]}
    if "type" in entry:
        shape["type"] = entry["type"]
    if "elem" in entry:
        shape["elem"] = param_shape(entry["elem"])
    if "flag" in entry:
        shape["flag"] = list(entry["flag"])
    if "name" in entry:
        shape["name"] = entry["name"]
    return shape


def parse_legacy_params(
    tlParams: List[Dict[str, str]],
    where: str,
) -> List[Param]:
    parsed: List[Param] = []
    for p in tlParams:
        entry: Param = parse_tl_type(p["type"], where + "." + p["name"])
        entry["name"] = p["name"]
        parsed.append(entry)
    return parsed


def kinds_compatible(
    legacy: Param,
    current: Param,
) -> Optional[str]:
    """None when the upgrader can convert legacy into current, else the reason it cannot."""
    legacyKind: str = legacy["kind"]
    currentKind: str = current["kind"]
    if legacyKind == currentKind:
        if legacyKind == "object" and legacy["type"] != current["type"]:
            return "object type %s -> %s" % (legacy["type"], current["type"])
        if legacyKind == "vector":
            return kinds_compatible(legacy["elem"], current["elem"])
        return None
    if legacyKind == "int" and currentKind in ("double", "long"):
        return None
    return "kind %s -> %s" % (legacyKind, currentKind)


def vector_adapter_ok(
    legacy: Param,
    current: Param,
) -> bool:
    """Vector<int> to Vector<InputMessage>: ids are wrapped as inputMessageID."""
    return (
        legacy["kind"] == "vector"
        and current["kind"] == "vector"
        and legacy["elem"]["kind"] == "int"
        and current["elem"]["kind"] == "object"
        and current["elem"]["type"] == "InputMessage"
    )


def unknown_object_type(
    param: Param,
    knownTypes: Set[str],
) -> Optional[str]:
    """The first object type used by a legacy param (nested elements included) that has no current constructor."""
    if param["kind"] == "object" and param["type"] not in knownTypes:
        return param["type"]
    if param["kind"] == "vector":
        return unknown_object_type(param["elem"], knownTypes)
    return None


def object_to_vector_ok(
    legacy: Param,
    current: Param,
) -> bool:
    """T to Vector<T>: the single object becomes a one-element vector (account.createTheme settings)."""
    return (
        legacy["kind"] == "object"
        and current["kind"] == "vector"
        and current["elem"]["kind"] == "object"
        and current["elem"]["type"] == legacy["type"]
    )


def classify_params(
    legacyParams: List[Param],
    currentParams: List[Param],
    zeroParamTypes: Set[str],
    knownTypes: Optional[Set[str]] = None,
) -> Tuple[str, Optional[str]]:
    """Returns (class, problem): class is alias or upgrade, problem is None when the upgrader supports it."""
    identical: bool = [param_shape(p) for p in legacyParams] == [param_shape(p) for p in currentParams]
    problem: Optional[str] = None
    if knownTypes is not None:
        for p in legacyParams:
            missing: Optional[str] = unknown_object_type(p, knownTypes)
            if missing is not None:
                return ("alias" if identical else "upgrade"), "param %s: type %s no longer exists" % (p["name"], missing)
    legacyByName: Dict[str, Param] = {p["name"]: p for p in legacyParams if p["kind"] != "flags"}
    for cur in currentParams:
        if cur["kind"] == "flags":
            continue
        old: Optional[Param] = legacyByName.get(cur["name"])
        if old is None:
            if "flag" not in cur and cur["kind"] == "object" and cur["type"] not in zeroParamTypes:
                problem = "param %s: no zero-param constructor of %s" % (cur["name"], cur["type"])
                break
            continue
        if vector_adapter_ok(old, cur) or object_to_vector_ok(old, cur):
            continue
        reason: Optional[str] = kinds_compatible(old, cur)
        if reason is not None:
            problem = "param %s: %s" % (cur["name"], reason)
            break
    return ("alias" if identical else "upgrade"), problem


def legacy_param(entry: Param) -> Param:
    """A param as stored in the legacy table: layout only."""
    out: Param = {"kind": entry["kind"], "name": entry["name"]}
    if "type" in entry:
        out["type"] = entry["type"]
    if "elem" in entry:
        elem: Param = dict(entry["elem"])
        elem.setdefault("name", "")
        out["elem"] = legacy_param(elem)
    if "flag" in entry:
        out["flag"] = entry["flag"]
    return out


def load_history() -> List[Tuple[int, Dict[str, Any]]]:
    layers: List[Tuple[int, Dict[str, Any]]] = []
    for path in HISTORY_DIR.glob("*.json"):
        if path.stem.isdigit() and int(path.stem) < LAYER:
            layers.append((int(path.stem), json.loads(path.read_text(encoding="utf-8"))))
    layers.sort(key=lambda item: item[0])
    return layers


def build_legacy(
    schema: Dict[str, Any],
    mapping: Dict[str, Any],
    history: List[Tuple[int, Dict[str, Any]]],
) -> Dict[str, Any]:
    """Legacy ids (not in the current layer) whose predicate or method name still exists in it."""
    currentCtors: Dict[str, Dict[str, Any]] = {c["predicate"]: c for c in mapping["constructors"]}
    currentMethods: Dict[str, Dict[str, Any]] = {m["method"]: m for m in mapping["methods"]}
    currentIds: Set[int] = {int(c["id"]) for c in schema["constructors"]} | {int(m["id"]) for m in schema["methods"]}
    zeroParamTypes: Set[str] = {c["type"] for c in mapping["constructors"] if not c["params"]}
    knownTypes: Set[str] = {c["type"] for c in mapping["constructors"]}
    ctorEntries: Dict[int, Dict[str, Any]] = {}
    methodEntries: Dict[int, Dict[str, Any]] = {}
    skipped: int = 0
    for layer, doc in history:
        for item in doc["constructors"]:
            name: str = item["predicate"]
            target: Optional[Dict[str, Any]] = currentCtors.get(name)
            oldId: int = int(item["id"])
            if target is None or oldId in currentIds or item["type"] != target["type"]:
                continue
            try:
                params: List[Param] = parse_legacy_params(item["params"], "constructor %s layer %d" % (name, layer))
            except ValueError:
                skipped += 1
                continue
            cls, problem = classify_params(params, target["params"], zeroParamTypes, knownTypes)
            ctorEntries[oldId] = legacy_entry(oldId, name, layer, params, target["id"], cls, problem)
        for item in doc["methods"]:
            name = item["method"]
            methodTarget: Optional[Dict[str, Any]] = currentMethods.get(name)
            oldId = int(item["id"])
            if methodTarget is None or oldId in currentIds:
                continue
            try:
                params = parse_legacy_params(item["params"], "method %s layer %d" % (name, layer))
            except ValueError:
                skipped += 1
                continue
            cls, problem = classify_params(params, methodTarget["params"], zeroParamTypes, knownTypes)
            methodEntries[oldId] = legacy_entry(oldId, name, layer, params, methodTarget["id"], cls, problem)
    return {
        "constructors": [ctorEntries[k] for k in sorted(ctorEntries)],
        "methods": [methodEntries[k] for k in sorted(methodEntries)],
        "skipped": skipped,
    }


def legacy_entry(
    oldId: int,
    name: str,
    layer: int,
    params: List[Param],
    targetId: int,
    cls: str,
    problem: Optional[str],
) -> Dict[str, Any]:
    entry: Dict[str, Any] = {
        "id": oldId,
        "name": name,
        "layer": layer,
        "params": [legacy_param(p) for p in params],
        "target_id": targetId,
        "class": cls,
        "supported": problem is None,
    }
    if problem is not None:
        entry["problem"] = problem
    return entry


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
    legacy: Dict[str, Any] = mapping.get("legacy", {"constructors": [], "methods": []})
    legacyAll: List[Dict[str, Any]] = legacy["constructors"] + legacy["methods"]
    lines.append("- Legacy constructors: %d, legacy methods: %d" % (len(legacy["constructors"]), len(legacy["methods"])))
    lines.append("- Legacy aliases: %d, upgrades: %d (unsupported: %d)" % (
        len([e for e in legacyAll if e["class"] == "alias"]),
        len([e for e in legacyAll if e["class"] == "upgrade"]),
        len([e for e in legacyAll if not e["supported"]]),
    ))
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
    mapping["legacy"] = build_legacy(schema, mapping, load_history())
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
