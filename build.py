#!/usr/bin/env python3
"""Build with the user's installed API. Never copy or redistribute game libraries."""
import argparse
import csv
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent


def mod_id_from_info(text):
    # Starsector accepts comments/trailing commas. Ignore nested dependency IDs.
    tokens = re.findall(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/|[{}\[\]:]', text, re.DOTALL)
    tokens = [token for token in tokens if not token.startswith(("//", "/*"))]
    depth = 0
    for index, token in enumerate(tokens):
        if token in ("{", "["):
            depth += 1
        elif token in ("}", "]"):
            depth -= 1
        elif depth == 1 and token == '"id"' and tokens[index + 1:index + 2] == [":"]:
            return json.loads(tokens[index + 2])
    return None


def run(command):
    subprocess.run([str(arg) for arg in command], check=True, cwd=ROOT, timeout=180)


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
    run([sys.executable, "-B", ROOT / "tests" / "build_workflow_test.py"])
    destination = ROOT / "build" / "tests"
    if destination.exists():
        shutil.rmtree(destination)
    sources = sorted((ROOT / "src" / "livingsector" / "model").glob("*.java"))
    sources += sorted((ROOT / "src" / "livingsector" / "traffic").glob("*.java"))
    sources += sorted((ROOT / "tests" / "livingsector").glob("*.java"))
    compile_sources(sources, destination)
    run(["java", "-ea", "-cp", destination, "livingsector.TrafficTests"])


def integration(game_root):
    """Compile and validate a candidate without touching the installed mod jar."""
    core = game_root / "starsector-core"
    if not (core / "starfarer.api.jar").is_file():
        raise ValueError("Pass --game-root pointing to a Starsector installation (containing starsector-core)")
    destination = ROOT / "build" / "classes"
    if destination.exists():
        shutil.rmtree(destination)
    libraries = sorted(core.glob("*.jar"))
    luna_libraries = []
    # Compile against installed dependencies, never copy them into our jar or package.
    for mod_id in ("nexerelin", "lw_console", "lw_lazylib", "MagicLib", "lunalib"):
        candidates = []
        for directory in (game_root / "mods").iterdir():
            info = directory / "mod_info.json"
            if info.is_file() and mod_id_from_info(info.read_text(encoding="utf-8-sig")) == mod_id:
                candidates.append(directory)
        if not candidates:
            raise ValueError("Build requires installed mod " + mod_id + " (Console Commands and LunaLib are optional at runtime)")
        # Versioned folders are common in this installation; use the newest installed copy.
        candidates.sort(key=lambda path: tuple(int(part) for part in re.findall(r"\d+", path.name)))
        selected = candidates[-1]
        print("Build dependency:", selected.name, flush=True)
        dependency = sorted((selected / "jars").rglob("*.jar"))
        if mod_id == "lunalib":
            luna_libraries = dependency
        else:
            libraries += dependency
    classpath = os.pathsep.join(str(path) for path in libraries)
    luna_classpath = os.pathsep.join(str(path) for path in luna_libraries)
    compile_sources(sorted((ROOT / "src").rglob("*.java")), destination, classpath + os.pathsep + luna_classpath)
    # Tests load the candidate jar itself, so an omitted production class cannot be
    # supplied accidentally by the compiler's loose class output.
    jar = ROOT / "build" / "candidate" / "LivingSector.jar"
    jar.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Title: Living Sector\r\n\r\n")
        for path in sorted(destination.rglob("*.class")):
            archive.write(path, path.relative_to(destination).as_posix())
    check_artifact(jar)
    contract_tests = ROOT / "build" / "campaign-tests"
    if contract_tests.exists():
        shutil.rmtree(contract_tests)
    test_classpath = str(jar) + os.pathsep + classpath
    compile_sources(sorted((ROOT / "tests" / "campaign").rglob("*.java")), contract_tests, test_classpath + os.pathsep + luna_classpath)
    run(["java", "-ea", "-cp", str(contract_tests) + os.pathsep + test_classpath,
         "livingsector.campaign.JourneyTests"])
    report = ROOT / "build" / "reports" / "integration.xml"
    # Installed XStream predates Java modules. These openings are for data-only
    # serializer tests on JDK 17; it does not change the game's launch options.
    run(["java", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
         "--add-opens=java.base/java.text=ALL-UNNAMED", "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED", "-ea", "-cp",
         str(contract_tests) + os.pathsep + test_classpath, "livingsector.campaign.IntegrationSuite", report])
    luna_report = report.with_name("luna-integration.xml")
    run(["java", "-ea", "-cp", str(contract_tests) + os.pathsep + test_classpath + os.pathsep + luna_classpath,
         "livingsector.campaign.IntegrationSuite", luna_report, "--luna"])
    report.with_name("validated-build.json").write_text(json.dumps({
        "status": "passed", "game_root": str(game_root), "java_target": 8,
        "dependencies": [str(path) for path in libraries],
        "optional_test_dependencies": [str(path) for path in luna_libraries],
        "candidate_jar": str(jar), "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "scenario_report": str(report),
        "luna_scenario_report": str(luna_report),
    }, indent=2) + "\n", encoding="utf-8")
    print("Validated candidate:", jar, flush=True)
    return jar


def check_artifact(jar):
    with zipfile.ZipFile(jar) as archive:
        if archive.testzip() is not None:
            raise ValueError("Candidate jar failed its integrity check")
        names = set(archive.namelist())
        for name in names:
            if name == "META-INF/MANIFEST.MF":
                continue
            if not name.startswith("livingsector/") or not name.endswith(".class"):
                raise ValueError("Unexpected packaged file: " + name)
            if int.from_bytes(archive.read(name)[6:8], "big") != 52:
                raise ValueError("Expected Java 8 bytecode: " + name)
        with (ROOT / "data" / "console" / "commands.csv").open(encoding="utf-8", newline="") as stream:
            commands = list(csv.DictReader(stream))
        info = json.loads((ROOT / "mod_info.json").read_text(encoding="utf-8"))
        entrypoints = [info["modPlugin"]] + [command["class"] for command in commands]
        for classname in entrypoints:
            if classname.replace(".", "/") + ".class" not in names:
                raise ValueError("Missing configured entry point: " + classname)
        for source in (ROOT / "tests").rglob("*.java"):
            if any(name.rsplit("/", 1)[-1] == source.stem + ".class" for name in names):
                raise ValueError("Test code leaked into candidate jar: " + source.name)


def install(jar):
    installed = ROOT / "jars" / "LivingSector.jar"
    installed.parent.mkdir(exist_ok=True)
    temporary = installed.with_suffix(".tmp")
    shutil.copyfile(jar, temporary)
    try:
        temporary.replace(installed)
    except PermissionError as ex:
        temporary.unlink(missing_ok=True)
        raise ValueError("Tests passed, but the installed jar is locked. Fully quit Starsector and rerun build. "
                         "The validated candidate remains at " + str(jar)) from ex
    print("Installed", installed, flush=True)


def package(jar):
    version = json.loads((ROOT / "mod_info.json").read_text(encoding="utf-8"))["version"]
    output = ROOT / "dist" / ("LivingSector-" + version + ".zip")
    output.parent.mkdir(exist_ok=True)
    files = [ROOT / "mod_info.json", ROOT / "README.md", ROOT / "LICENSE", ROOT / "build.py", jar]
    for directory in ("data", "docs", "src", "tests"):
        files += sorted(path for path in (ROOT / directory).rglob("*") if path.is_file())
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            relative = "jars/LivingSector.jar" if path == jar else path.relative_to(ROOT).as_posix()
            archive.write(path, "LivingSector/" + relative)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None or archive.read("LivingSector/jars/LivingSector.jar") != jar.read_bytes():
            raise ValueError("Package verification failed")
    print("Packaged", output)


def benchmark():
    """Profile the last validated candidate with the matching compiled campaign fixtures."""
    report_path = ROOT / "build" / "reports" / "validated-build.json"
    if not report_path.exists():
        raise ValueError("Run integration or package successfully before benchmark")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    jar = Path(report["candidate_jar"])
    if report["status"] != "passed" or hashlib.sha256(jar.read_bytes()).hexdigest() != report["sha256"]:
        raise ValueError("Candidate does not match validated build; rerun integration")
    classpath = os.pathsep.join([str(ROOT / "build" / "campaign-tests"), str(jar)] + report["dependencies"])
    destination = ROOT / "build" / "benchmark"
    compile_sources(sorted((ROOT / "tests" / "benchmarks").rglob("*.java")), destination, classpath)
    output = ROOT / "build" / "reports" / "scaling.json"
    run(["java", "-Xmx1024m", "-cp", str(destination) + os.pathsep + classpath,
         "livingsector.campaign.ScalingBenchmark", output])
    results = json.loads(output.read_text(encoding="utf-8"))
    results["candidateSha256"] = report["sha256"]
    output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print("Headless scaling report (not live FPS/heap):", output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("test", "integration", "build", "package", "benchmark"), nargs="?", default="build")
    parser.add_argument("--game-root", type=Path,
                        default=Path(os.environ.get("STARSECTOR_HOME", str(ROOT.parents[1]))))
    args = parser.parse_args()
    for tool in ("java", "javac"):
        if not shutil.which(tool):
            parser.error("Install JDK 17 and put " + tool + " on PATH")
    if args.command == "benchmark":
        benchmark()
        return
    if args.command != "test":
        reports = ROOT / "build" / "reports"
        for name in ("integration.xml", "luna-integration.xml", "validated-build.json"):
            (reports / name).unlink(missing_ok=True)
    test()
    if args.command != "test":
        jar = integration(args.game_root.resolve())
        if args.command == "build":
            install(jar)
        if args.command == "package":
            package(jar)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        if isinstance(error, subprocess.CalledProcessError):
            detail = Path(error.cmd[0]).name + " exited with status " + str(error.returncode) + "; see diagnostics above"
        elif isinstance(error, subprocess.TimeoutExpired):
            detail = "command exceeded its " + str(error.timeout) + " second limit"
        else:
            detail = str(error)
        print("Build failed:", detail, file=sys.stderr)
        sys.exit(1)
