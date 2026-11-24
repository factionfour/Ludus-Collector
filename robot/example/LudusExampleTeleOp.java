package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;

import io.ludus.LudusLink;
import io.ludus.LudusLogger;
import io.ludus.LudusLogger.ChannelKind;

import java.util.HashMap;
import java.util.Map;

/**
 * The smallest opmode that records a run with Ludus.
 *
 * Copy this next to your own opmodes, run it on the practice field, and you
 * should see frames arriving in the Collector. Then take the five marked
 * pieces and put them in the opmode you actually drive.
 *
 * PRACTICE ONLY — see docs/ROBOT.md.
 */
@TeleOp(name = "Ludus Example", group = "Ludus")
public class LudusExampleTeleOp extends LinearOpMode {

    // (1) One gate per opmode run.
    private final LudusLink.InitGate ludusGate = new LudusLink.InitGate("LudusExampleTeleOp");

    private LudusLogger ludus = null;   // null whenever this run is not being recorded

    @Override
    public void runOpMode() {
        DcMotor left = hardwareMap.get(DcMotor.class, "left_drive");
        DcMotor right = hardwareMap.get(DcMotor.class, "right_drive");

        // (2) INIT loop: Triangle turns Ludus on and the robot looks for the laptop.
        while (!isStarted() && !isStopRequested()) {
            ludusGate.update(gamepad1.triangle);

            if (ludusGate.takeJustLinked()) {
                // The laptop answered. Flash a light here if you have one.
                gamepad1.rumble(200);
            }

            telemetry.addData("Ludus", ludusGate.status());
            telemetry.addLine("Triangle during INIT = record this run");
            telemetry.update();
        }

        if (isStopRequested()) {
            ludusGate.cancel();   // tell the laptop the robot is not coming
            return;
        }

        // (3) START: open the session and name the channels. Returns null when
        //     no laptop was found during INIT — that is the normal "not
        //     recording" case, not an error.
        ludus = ludusGate.open(b -> b
                .channel(ChannelKind.BATTERY, "battery")
                .channel(ChannelKind.DRIVE_POWER, "left_power")
                .channel(ChannelKind.DRIVE_POWER, "right_power")
                .channel(ChannelKind.CUSTOM_FLOAT, "loop_ms"));

        long lastLoopNs = System.nanoTime();
        boolean lastA = false;

        while (opModeIsActive()) {
            double drive = -gamepad1.left_stick_y;
            double turn = gamepad1.right_stick_x;
            double leftPower = Math.max(-1, Math.min(1, drive + turn));
            double rightPower = Math.max(-1, Math.min(1, drive - turn));
            left.setPower(leftPower);
            right.setPower(rightPower);

            long nowNs = System.nanoTime();
            double loopMs = (nowNs - lastLoopNs) / 1_000_000.0;
            lastLoopNs = nowNs;

            // (4) Once per loop: write the channels, then flush one frame.
            //     Every Ludus call is guarded by the null check, so the opmode
            //     runs exactly the same when nobody is recording.
            if (ludus != null) {
                ludus.channel("battery").write(
                        hardwareMap.voltageSensor.iterator().next().getVoltage());
                ludus.channel("left_power").write(leftPower);
                ludus.channel("right_power").write(rightPower);
                ludus.channel("loop_ms").write(loopMs);

                if (gamepad1.a && !lastA) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("where", "driver pressed A");
                    ludus.event("marker", true, payload);
                }

                ludus.flush();
            }

            lastA = gamepad1.a;

            telemetry.addData("Ludus", ludus == null ? "not recording" : "recording");
            telemetry.update();
        }

        // (5) Always stop the logger: this drains the last events and segments.
        if (ludus != null) {
            ludus.stop();
            ludus = null;
        }
    }
}
