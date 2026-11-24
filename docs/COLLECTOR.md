<div align="center">
<img src="img/collector-logo.png" alt="Ludus Collector" width="150">

# The Collector, at practice

**GLADIUS · FTC TEAM 26089**
</div>

---

Ludus records what the robot did during a practice run so you can look at it afterwards.
The robot sends its numbers over the robot WiFi to a laptop running **Ludus Collector**.
The Collector keeps every run on the laptop. From there you can read the log, save the
run to a file, or upload it to a Ludus server if your team runs one.

**Practice only.** FTC rules do not allow this kind of link in an official match. Ludus is
off every time the robot starts, and it stays off unless a driver turns it on.

---

## What you need

- A laptop (Mac or Windows) with Ludus Collector installed.
- The laptop joined to the **robot's WiFi** (the Control Hub network).
- Robot code that includes Ludus — see **[ROBOT.md](ROBOT.md)**. Two files in TeamCode,
  your team number in `/sdcard/FIRST/ludus.properties`, five lines in an opmode.

## Install the Collector

Take the installer for your laptop from the **Releases** page of this repo:

- Mac (Apple silicon): `Ludus Collector-<version>-arm64.dmg`
- Windows: `Ludus Collector Setup <version>.exe`

Take the one that matches your laptop. Nothing else needs installing.

The first time it runs, your laptop may ask whether the app can accept network
connections. Say **yes**. The Collector listens on two ports, and both must be allowed
through the laptop's firewall:

| Port | Type | Used for |
|---|---|---|
| 8091 | UDP | the robot finding the laptop |
| 8080 | TCP | the run data |

---

## Record a run

1. **Join the robot WiFi** on the laptop.
2. **Open Ludus Collector.** The status line says it is waiting for the robot
   (bottom bar: `Waiting for robot (press Triangle in INIT)`).
3. **Press INIT** on the Driver Station for the opmode you want.
4. **Press Triangle on gamepad 1.** The robot looks for the laptop.
   - The robot's light flashes green three times.
   - The Collector's dot turns green and the status line says
     `Robot 26089 linked (...) - press START`.
5. **Press START.** The status line says `Recording`, with the opmode name, a frame count and a
   timer. Log lines scroll in the window while the robot runs.
6. **Stop the opmode.** The run is saved on the laptop and appears in the run list at the
   top of the window, named `date-opmode-number`
   (for example `2026-11-21-AllianceRedAuto-5276`).

Ludus turns itself off again at the next INIT, so press Triangle each time you want a run
recorded. Pressing Triangle a second time during INIT turns it back off.

If the robot has not found the laptop by the time you press START, that run is not
recorded. Nothing else changes: the robot drives the same either way.

### What the status line means

The same status is shown at the top of the window and in the bar at the bottom.

| Status | Meaning |
|---|---|
| `Waiting for robot (press Triangle in INIT)` | No robot has asked for the laptop yet. |
| `Robot <team> linked (<address>) - press START` | The robot found the laptop. Ready to record. |
| `Recording` | A run is being recorded now. |
| `Server: Configured` / `Not configured` (right side) | Whether the upload settings are filled in. This does not affect recording. |

---

## Look at a run

Pick a run from the list at the top. You see its summary (opmode, start time, frames) and
its log. Log lines are coloured by type:

- green: one line per robot loop, with every measured value
- amber: events (something happened, such as a shot)
- blue: segments (a named step of the run starting or ending)
- red: the end of the run

## Save a run to a file

Use this to keep a run, send it to someone, or hand it to Claude Code for a review.

1. Pick the run.
2. Click **Save As...**
3. Choose where to put it. It starts in your Downloads folder and remembers the last
   folder you used. The file is named `<run name>.ludus.json`.
4. A note `Saved <file name>` appears next to the buttons. Click **Show** to open the
   folder with the file selected.

**Save As...** works as soon as a run has data, even before it is uploaded, and needs no
server and no internet.

What is in the file:

| Part | Contents |
|---|---|
| `format`, `format_version` | `ludus.session`, version `1` |
| `collector_version`, `exported_at` | which Collector wrote it, and when |
| `session` | run name, team number, opmode, start and end times, frame count |
| `register` | what the robot said about itself and its channels. **The API key is removed.** |
| `frames` | one entry per robot loop: `seq`, `ts_ms`, `tick`, `channels`, plus `events` and `segments` when there are any |
| `logs` | the same log lines the Collector shows |

If saving fails part way (for example the disk is full), the half-written file is removed
and a message tells you what went wrong.

## Upload a run to the server

Uploading puts the run on the Ludus server, where the web site shows replay, charts and
segments.

One-time setup, in **Settings**:

1. Enter the **Server URL**, your **Team Number** and your team's **API Key**.
2. Click **Save**, then **Test Connection**.

Then, for each run:

1. Put the laptop back on a network that has **internet**. The robot WiFi has none.
2. Pick the run and click **Upload**, or click **Upload All** to send every run that has
   not been sent yet.

A run can be uploaded once it has ended. After that its button reads `Uploaded`.

## Delete a run

Pick the run and click **Delete**. This removes it from the laptop only. Files you saved
with **Save As...** and runs already on the server are not touched.

---

## Robot settings

The robot reads `/sdcard/FIRST/ludus.properties` on the Control Hub. Your team number is
required; every other line is optional and changes a default without redeploying code.
[`ludus.properties.example`](ludus.properties.example) is a commented copy, and
[ROBOT.md](ROBOT.md) walks through putting it there.

| Line | Meaning |
|---|---|
| `ludus.enabled=false` | Blocks the Triangle button, so Ludus cannot be turned on. |
| `ludus.host=auto` | `auto` searches the robot WiFi. An address (such as `192.168.43.50`) asks only that laptop. |
| `ludus.port=8080` | The Collector's data port. |
| `ludus.team=26089` | Team number. |
| `ludus.api_key=...` | Only used when runs are uploaded to a Ludus server. |
| `ludus.robot_id=...` | A name for this robot. |

---

## If it does not work

**The Collector stays on "Waiting for robot".**
- Is the laptop on the robot's WiFi, not the school or home WiFi?
- Did you press Triangle on **gamepad 1**, and during **INIT** (not after START)?
- Is the laptop's firewall blocking the Collector? Allow UDP 8091 and TCP 8080.
- Is another program already using port 8080 on the laptop? Close it and reopen the
  Collector.
- Only one laptop should run the Collector on the robot's WiFi.

**It linked, but nothing was recorded.**
- The dot must be green **before** you press START. If you press START first, that run is
  skipped.

**Save As... is greyed out.**
- The run has no data yet. Wait for the first frames, or pick a different run.

**Upload is greyed out.**
- The upload settings are not complete, the run is still recording, or it is already
  uploaded.

**Upload fails.**
- The laptop is probably still on the robot WiFi. Switch to a network with internet and
  try again. **Test Connection** in Settings checks the server URL and key.

---

<div align="center">

**[← Back to the README](../README.md)** &nbsp;·&nbsp; **[Putting Ludus on the robot →](ROBOT.md)**

</div>
