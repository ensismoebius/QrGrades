package org.dedira.qrnotas.model;

public class GoalProgress {
    public String goalName;
    public int targetPoints;
    public int remaining;
    public boolean achieved;

    public GoalProgress(String goalName, int targetPoints, int currentPoints) {
        this.goalName = goalName;
        this.targetPoints = targetPoints;
        this.achieved = currentPoints >= targetPoints;
        this.remaining = this.achieved ? 0 : (targetPoints - currentPoints);
    }
}
