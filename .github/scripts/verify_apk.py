import sys
from pathlib import Path
from zipfile import ZipFile


def verify_native_libraries(path: Path, variant: str) -> None:
    if variant not in {"debug", "release"}:
        raise ValueError("Unsupported APK variant")
    expected_abis: set[str] = {"arm64-v8a", "x86_64"}
    if variant == "release":
        expected_abis.add("armeabi-v7a")
    required_libraries: set[str] = {
        "libtmessages.49.so",
        "liblkjingle_peerconnection_so.so",
        "liblivekit_uniffi.so",
        "libgojni.so",
    }
    with ZipFile(path) as package:
        entries: set[str] = set(package.namelist())
    actual_abis: set[str] = {
        name.split("/")[1] for name in entries
        if name.startswith("lib/") and name.endswith(".so")
    }
    if actual_abis != expected_abis:
        raise ValueError(f"Unexpected APK architectures: {sorted(actual_abis)}")
    for abi in expected_abis:
        for library in required_libraries:
            if f"lib/{abi}/{library}" not in entries:
                raise ValueError(f"APK is missing {abi}/{library}")


if __name__ == "__main__":
    verify_native_libraries(Path(sys.argv[1]), sys.argv[2])
