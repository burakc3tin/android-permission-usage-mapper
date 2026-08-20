<img width="1052" height="527" alt="githubb" src="https://github.com/user-attachments/assets/da7d8b2c-82b4-4bcf-b45a-a61d7b381089" />

# Android Permission Usage Mapper (APUM)

## What it is

APUM is a static analysis tool that answers one question about any Android project:
**does the code actually use the permissions the manifest asks for?**

It reads the manifest and the source files, matches every declared permission against the API calls that really need it, and produces a report of what is used, what is dead weight, and what is missing.

## Why

Android projects accumulate permissions. A library gets added, a feature gets removed, a permission stays behind. The result is an app that asks users for more access than it needs, which hurts install conversion and can trigger Google Play policy reviews. On the other side, a permission that the code uses but the manifest never declares crashes at runtime.

APUM finds both cases before the release, without running the app.

It analyzes Kotlin, Java and Flutter/Dart sources, so it works on native Android projects and on Flutter apps that use `permission_handler`.

## How to run it

Requirements: JDK 17. Nothing else, the Gradle wrapper is included.

### Web mode (recommended)

From the project root:

```
apum-web.bat
```

on Linux or macOS:

```
./apum-web.sh
```

This starts a local server on `127.0.0.1` and opens your browser. In the page:

1. Click the folder field. Your system's native folder dialog opens.
2. Select the Android or Flutter project you want to analyze.
3. Click **Analyze**.

The summary appears on the page and the full report opens in a new tab.

### Command line mode

```
gradlew run --args="C:/projects/my-app"
```

Useful options:

| Option | Meaning |
| --- | --- |
| `--out <dir>` | Where the reports are written, default `<project>/apum-report` |
| `--open` | Open the HTML report when the run finishes |
| `--fail-on <level>` | Exit with code 2 if a finding at `CRITICAL`, `HIGH`, `MEDIUM` or `LOW` exists, useful in CI |
| `--include-tests` | Also scan test sources |
| `--no-html` / `--no-json` | Skip one of the report formats |
| `--quiet` | No console summary |

Command line mode writes a self-contained `permission-map.html` and a `permission-map.json` to disk. Web mode keeps the report in memory.

## What the report shows

### Project overview

Application id, min and target SDK, how many Kotlin, Java and Dart files were scanned, and how long the analysis took.

### Risk score

A grade from A to F with a score out of 100, based on how many risky permissions are declared and how many problems were found.

### Permission states

Every permission falls into exactly one state. The categories never overlap.

| State | Meaning | What to do |
| --- | --- | --- |
| **Declared and used** | In the manifest and backed by real API usage in the code | Nothing, this is correct |
| **Declared, never used** | In the manifest but no usage found anywhere in the code | Remove it from the manifest |
| **Missing from manifest** | The code uses it but the manifest does not declare it | Add it, otherwise this fails at runtime |
| **Requested only** | The runtime request exists but no actual API call was found | Check whether the feature was removed |
| **Weak signal** | Only an indirect hint of usage was found | Verify manually |
| **Alternative covered** | A modern API that does not need this permission already covers the case | Consider dropping the permission |

### Evidence for each permission

For every permission the report lists the exact `file:line` locations that justify it, the code that proves the usage, and the call paths that lead to it. Nothing is a guess you have to trust, you can jump to the line and see it.

Each permission also carries its protection level (normal, dangerous, signature) and a risk level, so you can see which ones matter most.

### Findings

A prioritized list of concrete problems, each with a severity of `CRITICAL`, `HIGH`, `MEDIUM` or `LOW`, an id, a title and the location involved. This is the fix list.

### Filtering

The HTML report has search plus status and risk filters, so on a large project you can go straight to, for example, every dangerous permission that is never used.

## Try it

A sample project is included:

```
gradlew run --args="samples/demo-android-app --open"
```
