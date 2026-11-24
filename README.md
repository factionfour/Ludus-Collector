<div align="center">

<img src="docs/img/ludus-logo.png" alt="Gladius Ludus" width="300">

# LUDUS

### Practice telemetry for FTC robots

### **GLADIUS** &nbsp;&bull;&nbsp; **FTC TEAM 26089**

<br>

![Robot side](https://img.shields.io/badge/ROBOT-2_Java_files-C9A227?style=for-the-badge&labelColor=111111)
![Laptop side](https://img.shields.io/badge/LAPTOP-Mac_%7C_Windows-C9A227?style=for-the-badge&labelColor=111111)
![Server](https://img.shields.io/badge/SERVER-not_required-555555?style=for-the-badge&labelColor=111111)
![Practice only](https://img.shields.io/badge/PRACTICE-ONLY-8B1A1A?style=for-the-badge&labelColor=111111)

<br>

**Every subsystem, measured once per loop, straight off the robot — on your laptop before the drive team leaves the field.**

</div>

---

## What this is

Your robot already knows everything: where it thinks it is, what the shooter is doing, why the trigger did not fire. Almost all of it is thrown away every loop. Ludus keeps it.

Press **Triangle** on gamepad 1 during INIT. The robot finds your laptop on the robot WiFi, and from START every control loop is streamed across and written down — channels, events, and named segments of an auto. Stop the opmode and the run is sitting in a list, ready to read, save, or compare.

```
   ROBOT                        LAPTOP
   ┌──────────────┐             ┌────────────────────┐
   │ LudusLogger  │  UDP 8091   │  Ludus Collector   │
   │ LudusLink    │ ──find────▶ │  (this repo)       │
   │              │ ◀──here──── │                    │
   │ your opmode  │  TCP 8080   │  run list + log    │
   │  .flush()    │ ══frames══▶ │  Save As... → JSON │
   └──────────────┘             └────────────────────┘
        no internet, no server, nothing to sign up for
```

## What this is not

- **Not for competition.** FTC rules do not allow this kind of link in an official match. Ludus is off at every INIT and stays off unless a driver turns it on, so a robot carrying this code behaves exactly like one that does not.
- **Not a dashboard.** It is not trying to replace FTC Dashboard for live tuning. Ludus is about the run you *just did* — the whole thing, every loop, kept.
- **Not a service.** Nothing here talks to the internet. The Collector keeps runs on your laptop and writes plain JSON files.

---

## Download

Installers live on the **[Releases page](https://github.com/factionfour/Ludus-Collector/releases/latest)** — GitHub will not take a file this size inside the repo itself, so that is where they are.

| | |
|---|---|
| **macOS, Apple silicon** (M1 and later) | `Ludus Collector-1.2.0-arm64.dmg` |
| **macOS, Intel** | `Ludus Collector-1.2.0.dmg` |
| **Windows** | `Ludus Collector Setup 1.2.0.exe` |

---

## Start here

| | |
|---|---|
| 🤖 **Put it on the robot** | **[docs/ROBOT.md](docs/ROBOT.md)** — two files, one Gradle line, one properties line, five lines in your opmode |
| 💻 **Run the laptop app** | **[docs/COLLECTOR.md](docs/COLLECTOR.md)** — record, read, save, troubleshoot |

**The short version:** copy `robot/io/ludus/` into your TeamCode, add `implementation 'com.squareup.okhttp3:okhttp:3.12.13'` to `TeamCode/build.gradle`, put your team number in `/sdcard/FIRST/ludus.properties`, add five lines to an opmode, install the Collector, join the robot WiFi, press Triangle.

---

## What a run gives you

<table>
<tr><td width="33%" valign="top">

**Channels**
one value per loop

pose · velocity · drive power · battery · RPM · servo positions · anything you write

</td><td width="33%" valign="top">

**Events**
a thing that happened

`shot_fired` · `drive_arrived` · your own, with a payload and a success flag

</td><td width="33%" valign="top">

**Segments**
a named step

one per auto action, so you can compare the same step across ten runs

</td></tr>
</table>

Click **Save As...** on any run and you get a single `.ludus.json` file — the metadata, every decoded frame, and the log lines. No server, no upload. Hand it to a teammate, keep it next to the match video, or drop it into Claude Code with your robot code open and ask why the fourth shot keeps missing.

---

## What is in here

```
robot/
  io/ludus/
    LudusLogger.java   the stream: channels, events, segments, msgpack over WebSocket
    LudusLink.java     finding the laptop, and the INIT button that turns it on
  example/
    LudusExampleTeleOp.java   the smallest opmode that records a run

docs/
  ROBOT.md             step by step, on the robot
  COLLECTOR.md         step by step, on the laptop
  ludus.properties.example
```

The Collector app itself is distributed as an installer on the
[Releases page](https://github.com/factionfour/Ludus-Collector/releases/latest) —
its source is not part of this repo.

---

<div align="center">

<img src="docs/img/collector-logo.png" alt="Ludus Collector" width="120">

**GLADIUS · FTC 26089**

*Built for our own practice field. Shared because every team throws this data away.*

</div>
