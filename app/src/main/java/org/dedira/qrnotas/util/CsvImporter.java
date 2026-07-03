package org.dedira.qrnotas.util;

import org.dedira.qrnotas.model.CsvStudentRow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses roster CSVs with a header row of name/discipline/class-group columns, in any order. */
public class CsvImporter {

    public static class CsvFormatException extends Exception {
        private static final long serialVersionUID = 1L;

        public CsvFormatException(String message) {
            super(message);
        }
    }

    private CsvImporter() {
    }

    public static List<CsvStudentRow> parse(InputStream in) throws IOException, CsvFormatException {
        List<CsvStudentRow> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new CsvFormatException("The file is empty.");

            List<String> header = splitCsvLine(headerLine);
            int nameCol = -1, disciplineCol = -1, classGroupCol = -1;
            for (int i = 0; i < header.size(); i++) {
                String col = header.get(i).trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
                if (col.equals("name")) nameCol = i;
                else if (col.equals("discipline")) disciplineCol = i;
                else if (col.equals("classgroup") || col.equals("class")) classGroupCol = i;
            }

            if (nameCol == -1 || disciplineCol == -1 || classGroupCol == -1) {
                throw new CsvFormatException("Header must contain \"name\", \"discipline\" and \"classGroup\" columns.");
            }

            int lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue;

                List<String> cells = splitCsvLine(line);
                String name = cellAt(cells, nameCol);
                String disciplineName = cellAt(cells, disciplineCol);
                String classGroupName = cellAt(cells, classGroupCol);
                if (name.isEmpty()) continue;

                rows.add(new CsvStudentRow(lineNumber, name, disciplineName, classGroupName));
            }
        }

        return rows;
    }

    private static String cellAt(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index).trim() : "";
    }

    /** Minimal RFC4180 line splitter: handles quoted fields with embedded commas/escaped quotes. */
    private static List<String> splitCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    result.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        result.add(current.toString());
        return result;
    }
}
