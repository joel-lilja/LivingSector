# Developer setup

The runtime mod is Java. `build.py` runs compilation, checks, installation and packaging; Python is a build tool only.

## Prerequisites

Install **Git**, **Python 3** and **JDK 17**, with `java` and `javac` on your `PATH`.

For a full build, use a Starsector **0.98a-RC8** installation with these mods installed:

| Mod | Version used for validation |
| --- | --- |
| Nexerelin | 0.12.2c |
| LazyLib | 3.0.0 |
| MagicLib | 1.5.6 |
| Console Commands | 4.0.8 |
| LunaLib | 2.0.5 |

Console Commands and LunaLib are optional for players, but their installed jars are required to compile and run the full integration suite. Game and dependency jars are read from your installation and are never bundled with Living Sector. Compilation targets Java 8 bytecode.

## Clone and build

From your game's `mods` directory:

```sh
git clone https://github.com/joel-lilja/LivingSector.git
cd LivingSector
python3 build.py build
```

On Windows, use `py` instead of `python3` if Python is installed through the Windows launcher, for example `py build.py build`.

The build discovers Starsector automatically when the checkout is `Starsector/mods/LivingSector`. It validates a candidate jar and runs unit and integration checks before installing `jars/LivingSector.jar`. Fully close Starsector before installing, then enable Living Sector in the launcher and restart after rebuilding.

For a checkout elsewhere, supply the game installation explicitly:

```sh
python3 build.py integration --game-root "/path/to/Starsector"
```

Alternatively set `STARSECTOR_HOME` to that path. This selects the dependency installation; `build` still writes the jar inside the checkout. To install a checkout located elsewhere, make a package and extract it into the game's `mods` folder.

## Everyday commands

Run these from the checkout root. Each command accepts the same `--game-root` option where game libraries are needed.

| Command | Result |
| --- | --- |
| `python3 build.py test` | Pure Java decision/lifecycle tests and Python build-workflow checks. No game installation needed. |
| `python3 build.py integration` | Unit checks, candidate compilation and integration scenarios against installed libraries. Leaves the installed jar alone. |
| `python3 build.py build` | All checks, then installs the validated jar in the checkout's `jars/` folder. |
| `python3 build.py package` | All checks, then creates `dist/LivingSector-0.3.0.zip` without replacing the installed jar. |

`integration` and `package` can run while the game is open. If installation fails because the jar is locked, close Starsector and rerun `build`; the validated candidate remains under `build/candidate/`.

Reports are written to `build/reports/integration.xml` and `build/reports/luna-integration.xml`. A successful integration run also writes `validated-build.json` with the candidate SHA-256 and dependency paths. Generated jars, reports and archives are ignored by Git. A GitHub source archive needs building before it can be played.

## Working on the mod

Use any Java editor; the repository's build entry point is `build.py`, with no required IDE configuration. The [integration harness guide](../tests/INTEGRATION.md) explains adding scenarios and which game behavior is modeled. Headless checks do not simulate the full game or replace live compatibility testing.

- [Architecture and extension points](ARCHITECTURE.md)
- [Phase A civilian policies](PHASE_A.md)
- [5,000-ship scaling design and benchmark](SCALING.md)
- [Configuration reference](CONFIGURATION.md)
- [Developer testing procedures and results](TESTING.md)
- [Native route test commands](ROUTE_TEST.md)
- [Player commands and optional debug recording](PLAYTEST.md)
- [Debug recorder design](DEBUG_RECORDER_DESIGN.md)

## Scaling benchmark

After a successful integration/package build, run `python3 build.py benchmark`. It checks the candidate hash, then measures admission and maintenance for 100, 1,000 and 5,000 represented ships in solo, pair and five-ship routes. Results go to `build/reports/scaling.json`. The Java workload uses installed Nex routes with fake campaign entities and debug off; it does not measure live AI, rendering, retained engine heap or complete campaign serialization. See [scaling notes](SCALING.md).
