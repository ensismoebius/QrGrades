package org.dedira.qrnotas.model;

public class PointsHistory {
    public String id;
    public String enrollmentId;
    public int pointsDelta;
    public String note;
    public long createdAt;

    // Populated only when the entry is loaded for a multi-discipline view, to label which discipline it belongs to.
    public String disciplineName;
}
