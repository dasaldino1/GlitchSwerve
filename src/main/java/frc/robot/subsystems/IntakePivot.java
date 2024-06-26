package frc.robot.subsystems;

import static edu.wpi.first.units.MutableMeasure.mutable;
import static edu.wpi.first.units.Units.Radians;
import static edu.wpi.first.units.Units.RadiansPerSecond;
import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;
import static frc.robot.utilities.SparkConfigurator.getSparkMax;

import com.revrobotics.CANSparkBase;
import com.revrobotics.CANSparkBase.IdleMode;
import com.revrobotics.CANSparkLowLevel;
import com.revrobotics.CANSparkMax;
import edu.wpi.first.math.controller.ArmFeedforward;
import edu.wpi.first.math.controller.ProfiledPIDController;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.math.trajectory.TrapezoidProfile.Constraints;
import edu.wpi.first.units.Angle;
import edu.wpi.first.units.MutableMeasure;
import edu.wpi.first.units.Time;
import edu.wpi.first.units.Velocity;
import edu.wpi.first.units.Voltage;
import edu.wpi.first.wpilibj.Encoder;
import edu.wpi.first.wpilibj.shuffleboard.Shuffleboard;
import edu.wpi.first.wpilibj.shuffleboard.ShuffleboardTab;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import frc.robot.Constants.kIntake.kPivot;
import frc.robot.Constants.kIntake.kPivot.IntakePosition;
import frc.robot.commands.SysIdRoutines.SysIdType;
import frc.robot.utilities.Characterizable;
import frc.robot.utilities.SparkConfigurator.LogData;
import frc.robot.utilities.SparkConfigurator.Sensors;
import java.util.Set;
import java.util.function.DoubleSupplier;
import monologue.Annotations.Log;
import monologue.Logged;

public class IntakePivot extends SubsystemBase implements Characterizable, Logged {

  private final CANSparkMax pivotMotor;
  private final Encoder pivotEncoder;

  // Controls
  private final ArmFeedforward pivotFF;
  private final ProfiledPIDController profiledPIDController;
  private final TrapezoidProfile.Constraints constraints =
      new Constraints(kPivot.maxVel, kPivot.maxAccel);
  private final TrapezoidProfile.State goal;
  private final TrapezoidProfile.State currentSetpoint;
  private IntakePosition goalPosition = IntakePosition.HOME;

  // Shuffleboard
  private ShuffleboardTab tab = Shuffleboard.getTab("Intake Pivot");

  public IntakePivot() {
    // Motor Configs
    pivotMotor =
        getSparkMax(
            kPivot.pivotMotorID,
            CANSparkLowLevel.MotorType.kBrushless,
            false,
            Set.of(Sensors.ABSOLUTE),
            Set.of(LogData.POSITION, LogData.VELOCITY, LogData.VOLTAGE));
    pivotMotor.setIdleMode(CANSparkBase.IdleMode.kBrake);
    pivotMotor.burnFlash();

    // Encoder Configs
    pivotEncoder = new Encoder(kPivot.portA, kPivot.portB);
    pivotEncoder.setReverseDirection(kPivot.invertedEncoder);
    resetEncoder();

    // Feedforward Configs
    pivotFF = new ArmFeedforward(kPivot.kS, kPivot.kG, kPivot.kV, kPivot.kA);

    pivotEncoder.setDistancePerPulse(2 * Math.PI / (kPivot.pulsesPerRevolution * kPivot.gearRatio));
    pivotEncoder.reset();

    profiledPIDController = new ProfiledPIDController(kPivot.kP, kPivot.kI, kPivot.kD, constraints);
    profiledPIDController.reset(getPivotAngle());
    goal = new TrapezoidProfile.State(kPivot.intakeRadiansHome, 0);
    profiledPIDController.setGoal(goal);
    currentSetpoint = profiledPIDController.getSetpoint();
    profiledPIDController.disableContinuousInput();

    // Button to Reset Encoder
    tab.add("Reset Intake Pivot Encoder", resetEncoder());
    tab.addString("Intake Position", () -> goalPosition.name());
  }

  // MAIN CONTROLS -------------------------------
  public Command setIntakePosition(IntakePosition intakePosition) {
    return this.runOnce(() -> goalPosition = intakePosition)
        .andThen(setIntakePivotPos(intakePosition.angle));
  }

  public Command setIntakePivotPos(double posRad) {
    return this.run(
            () -> {
              pivotMotor.setVoltage(calculateVoltage(posRad));
            })
        .finallyDo(() -> pivotMotor.setVoltage(0));
  }

  public Command setVoltageTest(DoubleSupplier volts) {
    return this.startEnd(
        () -> pivotMotor.setVoltage(volts.getAsDouble()), () -> pivotMotor.setVoltage(0));
  }

  // ---------- Public interface methods ----------
  @Log.NT
  public double getPivotAngle() {
    return getRawEncoder() + kPivot.encoderOffset;
  }

  public void reset() {
    profiledPIDController.reset(getPivotAngle(), 0);
  }

  @Log.NT
  public double getPivotVelocity() {
    return pivotEncoder.getRate();
  }

  @Log.NT
  public double getAppliedVoltage() {
    return pivotMotor.getAppliedOutput() * pivotMotor.getBusVoltage();
  }

  @Log.NT
  public double getCurrent() {
    return pivotMotor.getOutputCurrent();
  }

  public void setBrakeMode(boolean on) {
    if (on) {
      pivotMotor.setIdleMode(IdleMode.kBrake);
    } else {
      pivotMotor.setIdleMode(IdleMode.kCoast);
    }
  }

  @Log.NT
  public double getSetpointAngle() {
    return currentSetpoint.position;
  }

  @Log.NT
  public double getSetpointVelocity() {
    return currentSetpoint.velocity;
  }

  @Log.NT
  public double getGoalAngle() {
    return profiledPIDController.getGoal().position;
  }

  @Log.NT
  public double getGoalVelocity() {
    return profiledPIDController.getGoal().velocity;
  }

  public IntakePosition getGoalPosition() {
    return this.goalPosition;
  }

  private double calculateVoltage(double angle) {
    // Set appropriate goal
    profiledPIDController.setGoal(angle);

    // Update measurements and get feedback voltage
    double feedbackVoltage = profiledPIDController.calculate(getPivotAngle());

    // Get setpoint from profile
    var nextSetpoint = profiledPIDController.getSetpoint();

    // Calculate acceleration
    var accel = (nextSetpoint.velocity - currentSetpoint.velocity) / 0.02;

    log("Accel", accel);
    log("Next setpoint velocity", nextSetpoint.velocity);
    log("Current Setpoint velocity", currentSetpoint.velocity);

    // Calculate voltages
    double feedForwardVoltage =
        pivotFF.calculate(nextSetpoint.position + kPivot.cogOffset, nextSetpoint.velocity, accel);

    // Log Values
    this.log("FeedbackVoltage", feedbackVoltage);
    this.log("Feedforward voltage", feedForwardVoltage);

    currentSetpoint.position = nextSetpoint.position;
    currentSetpoint.velocity = nextSetpoint.velocity;

    return feedForwardVoltage + feedbackVoltage;
  }

  public boolean isHome() {
    return ((Math.abs(profiledPIDController.getGoal().position - getPivotAngle())) < 0.12)
        && (profiledPIDController.getGoal().position == kPivot.IntakePosition.HOME.angle);
  }

  public boolean isAtPosition(IntakePosition position) {
    return position.angle == getSetpointAngle();
  }

  // Private hardware
  private double getRawEncoder() {
    return pivotEncoder.getDistance();
  }

  // Reset Encoder
  public Command resetEncoder() {
    return this.runOnce(() -> pivotEncoder.reset()).ignoringDisable(true);
  }

  // Return SysId Routine
  public SysIdRoutine getRoutine(SysIdType type) {
    // Mutable holder for unit-safe voltage values, persisted to avoid reallocation.
    MutableMeasure<Voltage> appliedVoltage = mutable(Volts.of(0));
    // Mutable holder for unit-safe angular distance values, persisted to avoid reallocation.
    MutableMeasure<Angle> angle = mutable(Radians.of(0));
    // Mutable holder for unit-safe angular velocity values, persisted to avoid reallocation.
    MutableMeasure<Velocity<Angle>> velocity = mutable(RadiansPerSecond.of(0));
    MutableMeasure<Voltage> stepVoltage = mutable(Volts.of(4));
    MutableMeasure<Time> timeout = mutable(Seconds.of(5));
    return new SysIdRoutine(
        new SysIdRoutine.Config(null, stepVoltage, timeout),
        new SysIdRoutine.Mechanism(
            (volts) -> {
              pivotMotor.setVoltage(volts.magnitude());
            },
            (log) -> {
              log.motor("intakePivotMotor")
                  .voltage(
                      appliedVoltage.mut_replace(
                          pivotMotor.getBusVoltage() * pivotMotor.getAppliedOutput(), Volts))
                  .angularPosition(angle.mut_replace(pivotEncoder.getDistance() * Math.PI, Radians))
                  .angularVelocity(velocity.mut_replace(pivotEncoder.getRate(), RadiansPerSecond));
            },
            this));
  }
}
