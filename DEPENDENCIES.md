# Dependencies

All dependencies are bundled as JARs in the `lib/` directory.
This project does not use Maven or Gradle — compile and run instructions are in the [README](README.md).

## Java compatibility

All current dependencies require **Java 8 or later**.
The Raspberry Pi OS default OpenJDK 11+ satisfies this requirement.

> **Note:** If your target runtime is Java 7, do not use the versions below.
> The last Log4j 2.x release supporting Java 7 is **2.12.4**.

## Bundled libraries

| Artifact | Bundled version | License | Upstream |
|---|---|---|---|
| commons-codec | 1.21.0 | Apache-2.0 | https://commons.apache.org/proper/commons-codec/ |
| log4j-api | 2.25.3 | Apache-2.0 | https://logging.apache.org/log4j/2.x/ |
| log4j-core | 2.25.3 | Apache-2.0 | https://logging.apache.org/log4j/2.x/ |
| jSerialComm | 2.6.2 | Apache-2.0 / LGPL-3.0 (dual) | https://fazecast.github.io/jSerialComm/ |

## Upgrade notes

### log4j 2.16.0 → 2.25.3

Upgraded to address known CVEs in the 2.16.x line and pick up maintenance fixes.
Log4j 2.25.3 requires Java 8+. No API changes affect this project.

### commons-codec 1.14 → 1.21.0

Routine refresh. Only `Base64` is used; the API is stable across these versions.

### jSerialComm 2.6.2 — left as-is pending hardware validation

The current upstream release is newer, but jSerialComm provides native serial port
access to the PLS8-E LTE terminal via Raspberry Pi USB. Upgrading without testing
on the actual Pi + HCP HIT U4 hardware could introduce regressions (native library
changes, timing behaviour, port enumeration differences).

**Recommendation:** upgrade only after smoke-testing on the physical setup.
The bundled 2.6.2 is known to work and has no critical CVEs.
