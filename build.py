#!/usr/bin/env python3
"""Build with the user's installed API. Never copy or redistribute game libraries."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent


def run(command):
    subprocess.run([str(arg) for arg in command], check=True, cwd=ROOT)


def compile_sources(sources, destination, classpath=None):
    destination.mkdir(parents=True, exist_ok=True)
    # javac argument files keep long source lists portable on Windows; paths may contain spaces.
    arguments = ["--release", "8", "-encoding", "UTF-8", "-d", str(destination)]
    if classpath:
        arguments.extend(["-classpath", classpath])
    arguments.extend(str(path) for path in sources)
    with tempfile.NamedTemporaryFile(mode="w", suffix=".args", encoding="utf-8", delete=False) as handle:
        argfile = Path(handle.name)
        for argument in arguments:
            handle.write('"' + argument.replace('\\', '/').replace('"', '\\"') + '"\n')
    try:
        run(["javac", "@" + str(argfile)])
    finally:
        argfile.unlink()


def test():
    destination = ROOT / "build" / "tests"
    if destination.exists():
        shutil.rmtree(destination)
    sources = sorted((ROOT / "src" / "livingsector" / "model").glob("*.java"))
    sources += sorted((ROOT / "src" / "livingsector" / "traffic").glob("*.java"))
    sources += sorted((ROOT / "tests" / "livingsector").glob("*.java"))
    compile_sources(sources, destination)
    run(["java", "-ea", "-cp", destination, "livingsector.TrafficTests"])


def build(game_root):
    core = game_root / "starsector-core"
    if not (core / "starfarer.api.jar").is_file():
        raise ValueError("Pass --game-root pointing to a Starsector installation (containing starsector-core)")
    destination = ROOT / "build" / "classes"
    if destination.exists():
        shutil.rmtree(destination)
    classpath = os.pathsep.join(str(path) for path in sorted(core.glob("*.jar")))
    compile_sources(sorted((ROOT / "src").rglob("*.java")), destination, classpath)
    contract_tests = ROOT / "build" / "campaign-tests"
    if contract_tests.exists():
        shutil.rmtree(contract_tests)
    test_classpath = str(destination) + os.pathsep + classpath
    compile_sources(sorted((ROOT / "tests" / "campaign").rglob("*.java")), contract_tests, test_classpath)
    run(["java", "-ea", "-cp", str(contract_tests) + os.pathsep + test_classpath,
         "livingsector.campaign.JourneyTests"])
    jar = ROOT / "jars" / "LivingSector.jar"
    jar.parent.mkdir(exist_ok=True)
    # Write atomically, so a failed build does not replace the last good jar.
    temporary = jar.with_suffix(".tmp")
    with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Title: Living Sector\r\n\r\n")
        for path in sorted(destination.rglob("*.class")):
            archive.write(path, path.relative_to(destination).as_posix())
    temporary.replace(jar)
    print("Built", jar)
    return jar


def package(jar):
    import json
    version = json.loads((ROOT / "mod_info.json").read_text(encoding="utf-8"))["version"]
    output = ROOT / "dist" / ("LivingSector-" + version + ".zip")
    output.parent.mkdir(exist_ok=True)
    files = [ROOT / "mod_info.json", ROOT / "README.md", ROOT / "LICENSE", ROOT / "build.py", jar]
    for directory in ("data", "docs", "src", "tests"):
        files += sorted(path for path in (ROOT / directory).rglob("*") if path.is_file())
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, "LivingSector/" + path.relative_to(ROOT).as_posix())
    print("Packaged", output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("test", "build", "package"), nargs="?", default="build")
    parser.add_argument("--game-root", type=Path,
                        default=Path(os.environ.get("STARSECTOR_HOME", str(ROOT.parents[1]))))
    args = parser.parse_args()
    for tool in ("java", "javac"):
        if not shutil.which(tool):
            parser.error("Install JDK 17 and put " + tool + " on PATH")
    test()
    if args.command != "test":
        jar = build(args.game_root.resolve())
        if args.command == "package":
            package(jar)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        print("Build failed:", error, file=sys.stderr)
        sys.exit(1)
