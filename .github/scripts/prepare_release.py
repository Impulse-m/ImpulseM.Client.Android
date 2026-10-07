import os
import re
import subprocess
from pathlib import Path


def prepare_release(ref: str, run_number: int, base_version: str) -> dict[str, str]:
    version_code: int = 10000 + run_number
    if run_number < 1 or version_code > 210000000:
        raise ValueError("The run number is outside the Android version code range")

    if ref.startswith("refs/tags/v"):
        tag: str = ref.removeprefix("refs/tags/")
        if not re.fullmatch(r"v[0-9][0-9A-Za-z.+_-]{0,127}", tag):
            raise ValueError("Release tags must start with v followed by a version number")
        version: str = tag[1:]
        variant: str = "release"
        prerelease: str = "false"
        title: str = f"ImpulseM {version}"
    elif ref == "refs/heads/master":
        if not re.fullmatch(r"[0-9][0-9A-Za-z.+_-]{0,127}", base_version):
            raise ValueError("APP_VERSION_NAME is not a valid release filename component")
        tag = f"beta-{run_number}"
        version = f"{base_version}-beta.{run_number}"
        variant = "debug"
        prerelease = "true"
        title = f"ImpulseM Beta {version}"
    else:
        raise ValueError("Releases can only run from master or a version tag")

    return {
        "tag": tag,
        "version": version,
        "version_code": str(version_code),
        "variant": variant,
        "task": f":TMessagesProj_App:assembleAfat{variant.capitalize()}",
        "prerelease": prerelease,
        "title": title,
        "apk_name": f"ImpulseM-{version}.apk",
    }


def main() -> None:
    properties: dict[str, str] = {}
    for line in Path("gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()

    metadata: dict[str, str] = prepare_release(
        os.environ["GITHUB_REF"],
        int(os.environ["GITHUB_RUN_NUMBER"]),
        properties["APP_VERSION_NAME"],
    )
    metadata["source_commit"] = subprocess.check_output(
        ["git", "rev-parse", "--verify", "HEAD^{commit}"],
        text=True,
    ).strip()
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        for key, value in metadata.items():
            output.write(f"{key}={value}\n")


if __name__ == "__main__":
    main()
