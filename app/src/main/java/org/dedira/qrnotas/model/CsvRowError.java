package org.dedira.qrnotas.model;

/** A CSV row that could not be matched against the database, kept for reporting to the user. */
public class CsvRowError {
    public final int lineNumber;
    public final String reason;

    public CsvRowError(int lineNumber, String reason) {
        this.lineNumber = lineNumber;
        this.reason = reason;
    }
}
