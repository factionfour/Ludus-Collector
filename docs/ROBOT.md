<div align="center">
<img src="img/ludus-logo.png" alt="Gladius Ludus" width="170">

# Putting Ludus on your robot

**GLADIUS · FTC TEAM 26089**
</div>

---

Start to finish this takes about twenty minutes, most of it the first deploy. You need:

- Your TeamCode project, the one you already deploy with Android Studio.
- A laptop with **Ludus Collector** installed ([COLLECTOR.md](COLLECTOR.md)).
- Your Control Hub, and a way to put one file on it (Android Studio's Device File Explorer, `adb push`, or the Driver Hub's file manager).

**Practice only.** FTC rules do not allow this kind of link in an official match. Everything below is built so the robot does nothing at all until a driver asks for a recording during INIT, and so a failure anywhere leaves the robot driving exactly as it does today.

---

## Step 1 — Copy two files into TeamCode

Copy the whole `robot/io/ludus/` folder from this repo into your TeamCode source folder, so you end up with:

```
TeamCode/src/main/java/
  io/ludus/
    LudusLogger.java      ← the stream. Do not edit this one.
    LudusLink.java        ← finding the laptop + the INIT button.
  org/firstinspires/ftc/teamcode/
    ... your opmodes ...
```

Both files declare `package io.ludus;`, so they must sit in a folder called `io/ludus`. Nothing else in your project needs to move, and neither file imports anything from your own code.

---

## Step 2 — Add the OkHttp dependency

`LudusLogger` streams over a WebSocket using OkHttp, and **the FTC SDK does not bundle it**. Add one line to `TeamCode/build.gradle`, inside the existing `dependencies { }` block:

```gradle
dependencies {
    implementation project(':FtcRobotController')

    // Ludus streams to the Collector over a WebSocket via OkHttp.
    // 3.12.x is the last Java-only line, which suits the Java 8 build.
    implementation 'com.squareup.okhttp3:okhttp:3.12.13'
}
```

Then **Sync Project with Gradle Files** in Android Studio.

**Check:** build the project (Build ▸ Make Project). It should compile with no other changes anywhere. If you see `package okhttp3 does not exist`, the sync has not picked the line up yet.

---

## Step 3 — Tell the robot its team number

Ludus needs one setting: your team number, which is how the Collector knows the broadcast is for it. Create a file called `ludus.properties` containing:

```properties
ludus.team=26089
```

Put it on the Control Hub at:

```
/sdcard/FIRST/ludus.properties
```

With adb, that is:

```bash
adb push ludus.properties /sdcard/FIRST/ludus.properties
```

Replace `26089` with your own number — it must match the team number you put into the Collector's settings. Everything else has a working default; [`ludus.properties.example`](ludus.properties.example) lists the optional lines (fixed laptop address, robot name, and the switch that blocks Ludus entirely).

**Check:** nothing visible yet. A missing or unreadable file is not a crash — the INIT button simply says why it cannot turn on.

---

## Step 4 — Add five lines to an opmode

Start with the opmode you drive most. There are five pieces, and `robot/example/LudusExampleTeleOp.java` is a complete working copy of all of them if you would rather start from a file that already runs.

**One** — a gate, as a field:

```java
private final LudusLink.InitGate ludusGate = new LudusLink.InitGate("GladiusTeleop");
private LudusLogger ludus = null;
```

**Two** — in your INIT loop, pass the button through. In a `LinearOpMode` that is the loop before `waitForStart()`:

```java
while (!isStarted() && !isStopRequested()) {
    ludusGate.update(gamepad1.triangle);
    if (ludusGate.takeJustLinked()) {
        // the laptop answered — flash a light, rumble, however you tell the drivers
    }
    telemetry.addData("Ludus", ludusGate.status());
    telemetry.update();
}
if (isStopRequested()) { ludusGate.cancel(); return; }
```

**Three** — at START, open the session and name what you want recorded:

```java
ludus = ludusGate.open(b -> b
        .channel(ChannelKind.POSE, "pose")
        .channel(ChannelKind.VELOCITY, "velocity")
        .channel(ChannelKind.BATTERY, "battery")
        .channel(ChannelKind.CUSTOM_FLOAT, "shooter_rpm"));
```

`open()` returns `null` when no laptop answered during INIT. That is the ordinary "not recording" case, which is why every call below is guarded.

**Four** — once per control loop, write the values and flush one frame:

```java
if (ludus != null) {
    ludus.channel("pose").write(x, y, headingDeg);
    ludus.channel("velocity").write(vx, vy, omegaDeg);
    ludus.channel("battery").write(voltage);
    ludus.channel("shooter_rpm").write(shooter.getRpm());
    ludus.flush();
}
```

Write whatever you already compute. Read it from the getters your subsystems expose — do not call hardware again just to log it, and do it on the opmode thread, where the rest of your loop runs.

**Five** — when the opmode ends:

```java
if (ludus != null) { ludus.stop(); ludus = null; }
```

`stop()` drains the last events and segments into a final frame before closing. Skipping it costs you the end of the run.

**Check:** build again. Still no behaviour change — none of this runs until Triangle is pressed.

---

## Step 5 — The first recorded run

1. Deploy the code to the robot.
2. Join the laptop to the **robot's WiFi** (the Control Hub network), and open Ludus Collector. The bottom bar should read `Waiting for robot (press Triangle in INIT)`.
3. On the Driver Station, **INIT** your opmode. The telemetry line reads `OFF  (press the Ludus button to turn on)`.
4. **Press Triangle on gamepad 1.** Within a second or two the line becomes `LINKED to 192.168.43.x - records from START`, and the Collector's dot turns green.
5. **Press START** and drive.
6. **Stop.** The run appears at the top of the Collector's list, named `date-opmode-number`.

Open it and you should see green tick lines scrolling with your channel values in them.

---

## Step 6 — Events and segments (worth the extra ten minutes)

Channels tell you what the robot *was*. Events and segments tell you what it *did*, and they are what make two runs comparable.

**An event** is a moment, with an optional payload and a success flag:

```java
Map<String, Object> payload = new HashMap<>();
payload.put("rpm", shooter.getRpm());
payload.put("range_cm", aim.getRange());
ludus.event("shot_fired", hit, payload);
```

Fire them where the decision happens, not in the logging block — the point is the moment.

**A segment** is a named stretch of the run:

```java
ludus.segmentStart("drive_to_the_first_shoot_spot");
// ... the action runs ...
ludus.segmentEnd();                       // or segmentEnd(aborted), or segmentEnd(metrics)
```

In auto, open one per action in your action list and you can line up the same step across ten runs and see which one drifted. Names are sanitised for you.

---

## How it behaves when things go wrong

| Situation | What happens |
|---|---|
| No laptop on the WiFi | Triangle turns Ludus on, it searches, never links. At START `open()` returns null and the run is not recorded. The robot drives normally. |
| Laptop found, then the WiFi drops mid-run | The logger buffers (bounded), then goes quietly dead. Your loop keeps running; frames are dropped, not queued forever. |
| `ludus.properties` missing or malformed | The INIT line says why (`no ludus.team in /sdcard/FIRST/ludus.properties`). Nothing is sent. |
| You want Ludus off for an event | Put `ludus.enabled=false` in the properties file. The button then refuses, with that reason on the Driver Hub. |
| Nobody presses Triangle | Nothing happens at all. No sockets, no threads, no telemetry. |

---

## Troubleshooting

**Triangle does nothing.** Read the telemetry line. `no ludus.team` means the properties file is missing or in the wrong place; `ludus.enabled=false` means it is switched off; `bad /sdcard/...` names the parse error.

**It searches but never links.** The laptop and the robot must be on the *same* network — the Control Hub's own WiFi, not your school's. Check that the team number in the Collector's settings matches `ludus.team` exactly, and that the laptop firewall allows **UDP 8091** and **TCP 8080**. On Windows the first run pops a firewall prompt: say yes, for private networks.

**It links, but the run has no frames.** You are not calling `flush()`, or the whole logging block is behind a condition that is false. The Collector's status line shows the frame count climbing when frames arrive.

**The run cuts off at the end.** `stop()` is not being called on every exit path — including the early return when the opmode is stopped during INIT.

**`IllegalArgumentException: Channel "pose" (POSE) expects 3 value(s)`.** Each `ChannelKind` has a fixed value count: `POSE` and `VELOCITY` take three, `CUSTOM_VEC2` two, most others one. Match the write to the kind you declared.

---

<div align="center">

**[← Back to the README](../README.md)** &nbsp;·&nbsp; **[The laptop app →](COLLECTOR.md)**

</div>
