package frc.robot.commands;

import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.interpolation.InterpolatingDoubleTreeMap;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Command.InterruptionBehavior;
import edu.wpi.first.wpilibj2.command.Commands;
import frc.robot.Constants.kIntake;
import frc.robot.Constants.kIntake.kPivot.IntakePosition;
import frc.robot.Constants.kIntakeShooter.kHandOff;
import frc.robot.Constants.kIntakeShooter.kShootAmp;
import frc.robot.Constants.kIntakeShooter.kShootSpeaker;
import frc.robot.Constants.kShooter.kPivot.ShooterPosition;
import frc.robot.subsystems.HandoffRollers;
import frc.robot.subsystems.IntakePivot;
import frc.robot.subsystems.IntakeRollers;
import frc.robot.subsystems.ShooterFlywheels;
import frc.robot.subsystems.ShooterPivot;

public class IntakeShooter {

  private final HandoffRollers handoffRollers;
  private final IntakePivot intakePivot;
  private final IntakeRollers intakeRollers;
  private final ShooterFlywheels shooterFlywheels;
  private final ShooterPivot shooterPivot;

  private final InterpolatingDoubleTreeMap lookupTable = new InterpolatingDoubleTreeMap();

  public IntakeShooter(
      HandoffRollers handoffRollers,
      IntakePivot intakePivot,
      IntakeRollers intakeRollers,
      ShooterFlywheels shooterFlywheels,
      ShooterPivot shooterPivot) {
    this.handoffRollers = handoffRollers;
    this.intakePivot = intakePivot;
    this.intakeRollers = intakeRollers;
    this.shooterFlywheels = shooterFlywheels;
    this.shooterPivot = shooterPivot;

    populateLookupTable();
  }

  private void populateLookupTable() {
    // Distance, Angle (degrees)
    lookupTable.put(1.0, 30.0);
  }

  public Command intakeProcess() {
    return Commands.waitSeconds(kIntake.kRollers.intakeDeployWait)
        .andThen(intakeRollers.intake())
        .deadlineWith(intakePivot.setIntakePosition(IntakePosition.DEPLOYED));
  }

  public Command autoIntake() {
    return Commands.waitSeconds(kIntake.kRollers.intakeDeployWait)
        .andThen(intakeRollers.intake())
        .deadlineWith(intakePivot.setIntakePosition(IntakePosition.DEPLOYED))
        .andThen(
            intakeRollers
                .index()
                .alongWith(
                    intakePivot.setIntakePosition(IntakePosition.HOME).until(intakePivot::isHome)))
        .andThen(handOff());
  }

  public Command autoShoot() {
    return handoffRollers.feedShooterCommand().deadlineWith(intakeRollers.outtakeCommand());
  }

  public Command shootSpeaker() {
    return shooterFlywheels
        .setShooterSpeed(kShootSpeaker.shootVelocity)
        .raceWith(
            Commands.waitSeconds(kShootSpeaker.delay)
                .until(shooterFlywheels::atVelocitySetpoint)
                .andThen(
                    handoffRollers
                        .feedShooterCommand()
                        .deadlineWith(intakeRollers.outtakeCommand())));
  }

  public Command handOff() {
    return intakeRollers
        .outtakeCommand()
        .raceWith(handoffRollers.intakeCommand())
        .withInterruptBehavior(InterruptionBehavior.kCancelIncoming)
        .withTimeout(kHandOff.timeout);
  }

  public Command pivotAmp() {
    return shooterPivot
        .goToPositionCommand(ShooterPosition.AMP)
        .deadlineWith(
            Commands.waitSeconds(0.04)
                .andThen(
                    intakeRollers.startEnd(
                        () -> intakeRollers.runRollers(-1.6), () -> intakeRollers.runRollers(0))));
  }

  public Command shootAmp() {
    return shooterFlywheels
        .shootVoltage(kShootAmp.shootVoltage)
        .raceWith(
            Commands.waitSeconds(kShootAmp.delay).andThen(handoffRollers.feedShooterCommand()))
        .andThen(shooterPivot.goToPositionCommand(ShooterPosition.HOME));
  }

  public Command angleShooterBasedOnDistance(double distance) {
    return shooterPivot.goToAngleCommand(Rotation2d.fromDegrees(lookupTable.get(distance)));
  }

  public Command unjamNote() {
    return intakeRollers
        .unjamIntake()
        .deadlineWith(handoffRollers.outtakeCommand())
        .andThen(
            Commands.idle()
                .until(() -> intakePivot.isAtPosition(IntakePosition.EJECT))
                .andThen(intakeRollers.eject())
                .deadlineWith(intakePivot.setIntakePosition(IntakePosition.EJECT)));
  }

  public Command sourceIntake() {
    return shooterFlywheels
        .shootVoltage(-4)
        .deadlineWith(
            handoffRollers
                .startEnd(() -> handoffRollers.setVoltage(-3), () -> handoffRollers.setVoltage(0))
                .until(handoffRollers::getLowerSensor));
  }
}
