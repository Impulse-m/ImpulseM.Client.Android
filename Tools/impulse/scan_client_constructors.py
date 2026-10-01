"""Build-time guard: every TL class the client instantiates must be understood by the ImpulseM transport.

A class is handled when its constructor id is in layer 229, or in the generated legacy table as an
alias or a supported upgrade (see gen_tl_proto_map.py), or when it is on the explicit allow-list
below with a reason. The check covers every class of TLRPC.java and tgnet/tl/*.java that is
instantiated (`new TL_xxx(` or `TL_xxx::new`) outside org/telegram/tgnet/.

Usage: python Tools/impulse/scan_client_constructors.py [--check]
The exit code is 1 when anything is unhandled. --check prints only the failures (used by the Gradle
task :ImpulseTransport:checkClientConstructors, which :TMessagesProj:preBuild depends on).
"""
import collections
import gzip
import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

TOOLS_DIR: Path = Path(__file__).resolve().parent
REPO_ROOT: Path = TOOLS_DIR.parent.parent
JAVA_ROOT: Path = REPO_ROOT / "TMessagesProj" / "src" / "main" / "java"
TGNET_DIR: Path = JAVA_ROOT / "org" / "telegram" / "tgnet"
SCHEMA_PATH: Path = REPO_ROOT / "TMessagesProj_AppTests" / "tlscheme" / "229.json"
MAPPING_PATH: Path = REPO_ROOT / "ImpulseTransport" / "src" / "main" / "resources" / "impulse" / "tl-proto-229.json.gz"

CLASS_RE: re.Pattern = re.compile(
    r"^\s*(?:public\s+|private\s+|protected\s+)?(?:static\s+)?(?:abstract\s+)?(?:final\s+)?class\s+(\w+)"
    r"(?:<[^>]*>)?(?:\s+extends\s+([\w\.<>, ?]+?))?(?:\s+implements\s+[\w\., ]+)?\s*\{"
)
CONSTRUCTOR_RE: re.Pattern = re.compile(r"public static final int constructor = (-?0x[0-9a-fA-F]+|-?\d+);")
NEW_RE: re.Pattern = re.compile(r"new\s+(?:TLRPC\.)?(?:\w+\.)?(\w+)\s*[(<]")
REF_RE: re.Pattern = re.compile(r"(\w+)::new")

# Classes that are never sent to ImpulseM, or that cannot work there. Each entry is a regular
# expression over the class name plus the reason it is allowed.
SECRET_CHAT: str = "secret chat: end-to-end layer, built and parsed on the device, never an ImpulseM RPC"
LOCAL_ONLY: str = "local-only data: built for the UI or the local database, never serialized to the server"
NO_EQUIVALENT: str = "no layer 229 equivalent: the request returns METHOD_INVALID"

ALLOW_LIST: List[Tuple[str, str]] = [
    # Secret chats.
    (r"TL_decrypted\w*", SECRET_CHAT),
    (r"TL_documentEncrypted", SECRET_CHAT),
    (r"TL_fileEncryptedLocation", SECRET_CHAT),
    (r"TL_messageEncryptedAction", SECRET_CHAT),
    (r"TL_message_secret", SECRET_CHAT),
    (r"TL_messages_sendEncryptedMultiMedia", SECRET_CHAT + " (id cacacaca is client-internal)"),
    # Local-only data.
    (r"TL_fileLocationToBeDeprecated", LOCAL_ONLY + " (image location holder)"),
    (r"TL_fileLocationUnavailable", LOCAL_ONLY + " (image location holder)"),
    (r"TL_fileLocation_layer82", LOCAL_ONLY + " (image location holder)"),
    (r"TL_photoSize_layer127", LOCAL_ONLY + " (thumbnail of a locally built photo)"),
    (r"TL_videoSize_layer127", LOCAL_ONLY + " (thumbnail of a locally built story entry)"),
    (r"TL_messageMediaUnsupported_old", LOCAL_ONLY + " (database migration)"),
    (r"TL_peerNotifySettingsEmpty_layer77", LOCAL_ONLY + " (database migration)"),
    (r"TL_userContact_old2", LOCAL_ONLY + " (phonebook contact shown in the UI)"),
    (r"TL_userForeign_old2", LOCAL_ONLY + " (placeholder user)"),
    (r"TL_userRequest_old2", LOCAL_ONLY + " (placeholder user)"),
    (r"TL_webPageUrlPending", LOCAL_ONLY + " (pending link preview state)"),
    (r"TL_premiumGiftOption", LOCAL_ONLY + " (premium gift UI)"),
    # Requests that cannot be sent. The feature that stops working is in the reason.
    (r"TL_channels_editCreator", NO_EQUIVALENT + " (feature: transfer channel ownership)"),
    (r"TL_messages_getStatsURL", NO_EQUIVALENT + " (feature: statistics page link for a chat)"),
    (r"TL_channels_getFutureCreatorAfterLeave", NO_EQUIVALENT + " (feature: choose the next owner when the owner leaves a channel)"),
    (
        r"TL_messages_reportReaction",
        NO_EQUIVALENT + " (feature: report a reaction; the client id 61422a48 appears in no schema layer"
        " and takes user_id where 229 messages.reportReaction takes reaction_peer)",
    ),
]


def to_u32(value: int) -> int:
    return value & 0xFFFFFFFF


def load_handled_ids() -> Tuple[Set[int], Set[int], Dict[int, str]]:
    """Returns (ids in 229, supported legacy ids, unsupported legacy id to problem)."""
    schema: Dict[str, Any] = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    current: Set[int] = {to_u32(int(c["id"])) for c in schema["constructors"]}
    current |= {to_u32(int(m["id"])) for m in schema["methods"]}
    mapping: Dict[str, Any] = json.loads(gzip.decompress(MAPPING_PATH.read_bytes()).decode("utf-8"))
    supported: Set[int] = set()
    unsupported: Dict[int, str] = {}
    for entry in mapping["legacy"]["constructors"] + mapping["legacy"]["methods"]:
        legacyId: int = to_u32(entry["id"])
        if entry["class"] == "alias" or entry["supported"]:
            supported.add(legacyId)
        else:
            unsupported[legacyId] = entry.get("problem", "unsupported")
    return current, supported, unsupported


def allow_reason(name: str) -> Optional[str]:
    for pattern, reason in ALLOW_LIST:
        if re.fullmatch(pattern, name):
            return reason
    return None


def parse_classes() -> List[Dict[str, Any]]:
    files: List[Path] = [TGNET_DIR / "TLRPC.java"] + sorted((TGNET_DIR / "tl").glob("*.java"))
    classes: List[Dict[str, Any]] = []
    for path in files:
        current: Optional[Dict[str, Any]] = None
        for line in path.read_text(encoding="utf-8", errors="replace").split("\n"):
            match: Optional[re.Match] = CLASS_RE.match(line)
            if match:
                current = {"name": match.group(1), "constructors": [], "file": path.name}
                classes.append(current)
                continue
            if current is None:
                continue
            found: Optional[re.Match] = CONSTRUCTOR_RE.search(line)
            if found:
                raw: str = found.group(1)
                current["constructors"].append(to_u32(int(raw, 16 if "0x" in raw else 10)))
    # Self-check: a constructor id is attributed to the nearest class header above it. A class
    # declaration the header regex misses (for example one spanning several lines) would make the
    # previous class own two ids, so this fails loudly instead of attributing ids to the wrong class.
    for cls in classes:
        if len(cls["constructors"]) > 1:
            raise RuntimeError(
                "class %s in %s has %d constructor ids (%s): a class declaration was probably not recognised"
                % (cls["name"], cls["file"], len(cls["constructors"]), ", ".join("0x%08x" % c for c in cls["constructors"]))
            )
    result: List[Dict[str, Any]] = []
    for cls in classes:
        if cls["constructors"]:
            cls["constructor"] = cls["constructors"][0]
            result.append(cls)
    return result


def count_instantiations() -> "collections.Counter[str]":
    counts: collections.Counter = collections.Counter()
    for path in JAVA_ROOT.rglob("*.java"):
        if TGNET_DIR in path.parents:
            continue
        text: str = path.read_text(encoding="utf-8", errors="replace")
        counts.update(NEW_RE.findall(text))
        counts.update(REF_RE.findall(text))
    return counts


def scan() -> Dict[str, Any]:
    current, supported, unsupported = load_handled_ids()
    counts = count_instantiations()
    unhandled: List[Dict[str, Any]] = []
    allowed: List[str] = []
    checked: int = 0
    for cls in parse_classes():
        name: str = cls["name"]
        if counts[name] == 0:
            continue
        checked += 1
        ident: int = cls["constructor"]
        if ident in current or ident in supported:
            continue
        reason: Optional[str] = allow_reason(name)
        if reason is not None:
            allowed.append(name)
            continue
        unhandled.append({
            "name": name,
            "id": "0x%08x" % ident,
            "uses": counts[name],
            "problem": unsupported.get(ident, "not in layer 229 and not in the legacy table"),
        })
    unhandled.sort(key=lambda e: e["name"])
    stale: List[str] = [pattern for pattern, reason in ALLOW_LIST if not any(re.fullmatch(pattern, n) for n in allowed)]
    return {"checked": checked, "allowed": sorted(set(allowed)), "unhandled": unhandled, "stale": stale}


def main() -> int:
    quiet: bool = "--check" in sys.argv[1:]
    result: Dict[str, Any] = scan()
    if quiet:
        for name in result["stale"]:
            print("stale allow-list entry (no longer needed): %s" % name)
        for entry in result["unhandled"]:
            print("unhandled TL class: %(name)s %(id)s used %(uses)d times: %(problem)s" % entry)
        if result["unhandled"]:
            print("The client uses TL constructors that ImpulseM cannot accept. Add a legacy upgrade or an")
            print("allow-list entry with a reason in Tools/impulse/scan_client_constructors.py.")
        else:
            print("client constructor guard: %d classes checked, 0 unhandled" % result["checked"])
        return 1 if result["unhandled"] or result["stale"] else 0
    print("instantiated classes checked: %d" % result["checked"])
    print("allow-listed: %d" % len(result["allowed"]))
    for name in result["allowed"]:
        print("  %s: %s" % (name, allow_reason(name)))
    for name in result["stale"]:
        print("stale allow-list entry (no longer needed): %s" % name)
    print("unhandled: %d" % len(result["unhandled"]))
    for entry in result["unhandled"]:
        print("  %(name)s %(id)s used %(uses)d times: %(problem)s" % entry)
    return 1 if result["unhandled"] else 0


if __name__ == "__main__":
    sys.exit(main())
