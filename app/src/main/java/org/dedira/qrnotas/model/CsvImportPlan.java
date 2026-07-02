package org.dedira.qrnotas.model;

import java.util.ArrayList;
import java.util.List;

/** Result of matching parsed CSV rows against the database, ready for a confirmation dialog. */
public class CsvImportPlan {
    public final List<ResolvedCsvRow> resolved = new ArrayList<>();
    public final List<CsvRowError> errors = new ArrayList<>();

    public int newStudentCount() {
        int count = 0;
        for (ResolvedCsvRow row : resolved) if (row.isNewStudent) count++;
        return count;
    }

    public int matchedStudentCount() {
        return resolved.size() - newStudentCount();
    }
}
