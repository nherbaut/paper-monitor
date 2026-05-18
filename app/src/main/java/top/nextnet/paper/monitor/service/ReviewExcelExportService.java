package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.Review;
import top.nextnet.paper.monitor.model.ReviewSubmission;

@ApplicationScoped
public class ReviewExcelExportService {

    private static final List<String> BASE_HEADERS = List.of("Title", "Authors", "Venue", "Year", "DOI");

    private final ReviewService reviewService;

    public ReviewExcelExportService(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    public byte[] exportSurveyWorkbook(Review review) throws IOException {
        List<Paper> papers = reviewService.papersInLiveScope(review);
        Map<Long, ReviewSubmission> submissionsByPaperId = reviewService.submissionsByPaperId(review);
        Map<String, Object> formSchema = reviewService.formSchema(review);
        Map<String, Object> scales = asObjectMap(formSchema.get("scales"));
        List<Map<String, Object>> topLevelFields = objectMapList(formSchema.get("fields"));

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            WorkbookStyles styles = createStyles(workbook);
            Sheet validationSheet = workbook.createSheet("_validation");
            workbook.setSheetHidden(workbook.getSheetIndex(validationSheet), true);
            ValidationRegistry validationRegistry = new ValidationRegistry(workbook, validationSheet);
            Set<String> usedSheetNames = new LinkedHashSet<>();

            createDimensionSheet(workbook, usedSheetNames, "Survey", topLevelFields, papers, submissionsByPaperId, scales,
                    validationRegistry, styles);
            for (Map<String, Object> field : topLevelFields) {
                createChildSheetsRecursively(workbook, usedSheetNames, field, papers, submissionsByPaperId, scales,
                        validationRegistry, styles);
            }

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void createChildSheetsRecursively(
            XSSFWorkbook workbook,
            Set<String> usedSheetNames,
            Map<String, Object> field,
            List<Paper> papers,
            Map<Long, ReviewSubmission> submissionsByPaperId,
            Map<String, Object> scales,
            ValidationRegistry validationRegistry,
            WorkbookStyles styles
    ) {
        List<Map<String, Object>> subdimensions = objectMapList(field.get("subdimensions"));
        if (subdimensions.isEmpty()) {
            return;
        }
        createDimensionSheet(workbook, usedSheetNames, firstNonBlank(stringValue(field.get("label")), stringValue(field.get("id")),
                        "Dimension"),
                subdimensions, papers, submissionsByPaperId, scales, validationRegistry, styles);
        for (Map<String, Object> subdimension : subdimensions) {
            createChildSheetsRecursively(workbook, usedSheetNames, subdimension, papers, submissionsByPaperId, scales,
                    validationRegistry, styles);
        }
    }

    private void createDimensionSheet(
            XSSFWorkbook workbook,
            Set<String> usedSheetNames,
            String requestedSheetName,
            List<Map<String, Object>> fields,
            List<Paper> papers,
            Map<Long, ReviewSubmission> submissionsByPaperId,
            Map<String, Object> scales,
            ValidationRegistry validationRegistry,
            WorkbookStyles styles
    ) {
        String sheetName = uniqueSheetName(requestedSheetName, usedSheetNames);
        Sheet sheet = workbook.createSheet(sheetName);
        sheet.createFreezePane(0, 1);

        List<FieldColumn> columns = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            columns.add(new FieldColumn(field, collectOptionLabels(field), stringValue(field.get("value_type")),
                    stringValue(field.get("cardinality"))));
        }

        Row headerRow = sheet.createRow(0);
        int columnIndex = 0;
        for (String baseHeader : BASE_HEADERS) {
            Cell cell = headerRow.createCell(columnIndex++);
            cell.setCellValue(baseHeader);
            cell.setCellStyle(styles.header());
        }
        for (FieldColumn column : columns) {
            Cell cell = headerRow.createCell(columnIndex++);
            cell.setCellValue(column.label());
            cell.setCellStyle(styles.header());
        }

        for (int rowIndex = 0; rowIndex < papers.size(); rowIndex++) {
            Paper paper = papers.get(rowIndex);
            ReviewSubmission submission = submissionsByPaperId.get(paper.id);
            Map<String, Object> values = reviewService.submissionValues(submission);
            Row row = sheet.createRow(rowIndex + 1);

            writeTextCell(row, 0, paper.title, styles.wrap());
            writeTextCell(row, 1, paper.authors, styles.wrap());
            writeTextCell(row, 2, paper.publisher, styles.wrap());
            writeTextCell(row, 3, paper.publishedOn == null ? null : String.valueOf(paper.publishedOn.getYear()), styles.wrap());
            writeTextCell(row, 4, deriveDoi(paper), styles.wrap());

            for (int index = 0; index < columns.size(); index++) {
                FieldColumn column = columns.get(index);
                Cell cell = row.createCell(BASE_HEADERS.size() + index);
                writeFieldValue(cell, column, values.get(column.id()), scales, styles.wrap());
            }
        }

        for (int index = 0; index < BASE_HEADERS.size(); index++) {
            sheet.setColumnWidth(index, 20 * 256);
        }
        for (int index = 0; index < columns.size(); index++) {
            sheet.setColumnWidth(BASE_HEADERS.size() + index, 24 * 256);
        }

        int firstDataRow = 1;
        int lastDataRow = Math.max(firstDataRow, papers.size());
        for (int index = 0; index < columns.size(); index++) {
            applyValidation(sheet, firstDataRow, lastDataRow, BASE_HEADERS.size() + index, columns.get(index),
                    validationRegistry, scales);
        }
    }

    private void writeFieldValue(
            Cell cell,
            FieldColumn column,
            Object value,
            Map<String, Object> scales,
            CellStyle style
    ) {
        cell.setCellStyle(style);
        if (value == null) {
            return;
        }
        if ("numeric".equals(column.valueType())) {
            Number number = asNumber(value);
            if (number != null) {
                cell.setCellValue(number.doubleValue());
                return;
            }
        }
        if (!column.optionLabels().isEmpty()) {
            if (value instanceof List<?> rows) {
                List<String> labels = new ArrayList<>();
                for (Object row : rows) {
                    labels.add(displayCategoryValue(column.optionLabels(), row));
                }
                cell.setCellValue(String.join(", ", labels));
                return;
            }
            cell.setCellValue(displayCategoryValue(column.optionLabels(), value));
            return;
        }
        if (looksLikeCriterionScale(value, column, scales)) {
            cell.setCellValue(String.valueOf(value));
            return;
        }
        cell.setCellValue(String.valueOf(value));
    }

    private boolean looksLikeCriterionScale(Object value, FieldColumn column, Map<String, Object> scales) {
        return value != null && column.optionLabels().isEmpty() && !"numeric".equals(column.valueType()) && scales != null;
    }

    private String displayCategoryValue(Map<String, String> optionLabels, Object rawValue) {
        String key = String.valueOf(rawValue);
        return optionLabels.getOrDefault(key, key);
    }

    private void applyValidation(
            Sheet sheet,
            int firstDataRow,
            int lastDataRow,
            int columnIndex,
            FieldColumn column,
            ValidationRegistry validationRegistry,
            Map<String, Object> scales
    ) {
        if (!column.optionLabels().isEmpty()) {
            String listName = validationRegistry.registerAllowedValues(column.id(), new ArrayList<>(column.optionLabels().values()));
            if ("multiple".equals(column.cardinality())) {
                for (int rowIndex = firstDataRow; rowIndex <= lastDataRow; rowIndex++) {
                    String cellRef = new CellReference(rowIndex, columnIndex).formatAsString();
                    String formula = "OR(" + cellRef + "=\"\",LET(items,TRIM(TEXTSPLIT(SUBSTITUTE(" + cellRef
                            + ",\", \",\",\"),\",\")),SUM(--(items=\"\"))=0,SUM(--ISNA(XMATCH(items," + listName + ")))=0))";
                    applyCustomValidation(sheet, rowIndex, rowIndex, columnIndex, formula,
                            "Use a comma-separated list of allowed categories.");
                }
                return;
            }
            applyListValidation(sheet, firstDataRow, lastDataRow, columnIndex, listName);
            return;
        }
        if ("numeric".equals(column.valueType())) {
            for (int rowIndex = firstDataRow; rowIndex <= lastDataRow; rowIndex++) {
                String cellRef = new CellReference(rowIndex, columnIndex).formatAsString();
                applyCustomValidation(sheet, rowIndex, rowIndex, columnIndex,
                        "OR(" + cellRef + "=\"\",ISNUMBER(" + cellRef + "))",
                        "Enter a numeric value.");
            }
        }
    }

    private void applyListValidation(Sheet sheet, int firstRow, int lastRow, int columnIndex, String formula) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createFormulaListConstraint(formula);
        CellRangeAddressList range = new CellRangeAddressList(firstRow, lastRow, columnIndex, columnIndex);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setSuppressDropDownArrow(false);
        validation.setShowErrorBox(true);
        validation.createErrorBox("Invalid value", "Choose one of the allowed categories.");
        sheet.addValidationData(validation);
    }

    private void applyCustomValidation(
            Sheet sheet,
            int firstRow,
            int lastRow,
            int columnIndex,
            String formula,
            String errorMessage
    ) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createCustomConstraint(formula);
        CellRangeAddressList range = new CellRangeAddressList(firstRow, lastRow, columnIndex, columnIndex);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        validation.createErrorBox("Invalid value", errorMessage);
        sheet.addValidationData(validation);
    }

    private Map<String, String> collectOptionLabels(Map<String, Object> field) {
        Map<String, String> labels = new LinkedHashMap<>();
        collectOptionLabelsInto(objectMapList(field.get("values")), labels);
        return labels;
    }

    private void collectOptionLabelsInto(List<Map<String, Object>> options, Map<String, String> labels) {
        for (Map<String, Object> option : options) {
            String optionId = stringValue(option.get("id"));
            if (optionId != null) {
                labels.put(optionId, firstNonBlank(stringValue(option.get("label")), optionId));
            }
            collectOptionLabelsInto(objectMapList(option.get("children")), labels);
        }
    }

    private String deriveDoi(Paper paper) {
        String doi = extractDoi(paper.sourceLink);
        if (doi != null) {
            return doi;
        }
        return extractDoi(paper.openAccessLink);
    }

    private String extractDoi(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String normalized = candidate.trim();
        if (normalized.regionMatches(true, 0, "doi:", 0, 4)) {
            return normalized.substring(4).trim();
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        int doiOrgIndex = lower.indexOf("doi.org/");
        if (doiOrgIndex >= 0) {
            return normalized.substring(doiOrgIndex + "doi.org/".length()).trim();
        }
        return null;
    }

    private String uniqueSheetName(String requested, Set<String> usedNames) {
        String base = sanitizeSheetName(requested);
        String candidate = base;
        int suffix = 2;
        while (usedNames.contains(candidate.toLowerCase(Locale.ROOT))) {
            String extra = "-" + suffix++;
            int maxBaseLength = Math.max(1, 31 - extra.length());
            candidate = base.substring(0, Math.min(base.length(), maxBaseLength)) + extra;
        }
        usedNames.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private String sanitizeSheetName(String requested) {
        String candidate = firstNonBlank(requested, "Sheet")
                .replaceAll("[\\\\/*?:\\[\\]]", " ")
                .trim();
        if (candidate.isBlank()) {
            candidate = "Sheet";
        }
        return candidate.substring(0, Math.min(31, candidate.length()));
    }

    private WorkbookStyles createStyles(XSSFWorkbook workbook) {
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);

        CellStyle header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);

        CellStyle wrap = workbook.createCellStyle();
        wrap.setWrapText(true);
        wrap.setVerticalAlignment(VerticalAlignment.TOP);

        return new WorkbookStyles(header, wrap);
    }

    private void writeTextCell(Row row, int columnIndex, String value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        cell.setCellStyle(style);
        if (value != null) {
            cell.setCellValue(value);
        }
    }

    private void writeTextCell(Row row, int columnIndex, Object value, CellStyle style) {
        writeTextCell(row, columnIndex, value == null ? null : String.valueOf(value), style);
    }

    private Number asNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> cast = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            cast.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return cast;
    }

    private List<Map<String, Object>> objectMapList(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?>) {
                result.add(asObjectMap(row));
            }
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record WorkbookStyles(CellStyle header, CellStyle wrap) {
    }

    private record FieldColumn(Map<String, Object> field, Map<String, String> optionLabels, String valueType, String cardinality) {
        private String id() {
            return String.valueOf(field.get("id"));
        }

        private String label() {
            return field.get("label") == null ? id() : String.valueOf(field.get("label"));
        }
    }

    private static final class ValidationRegistry {
        private final XSSFWorkbook workbook;
        private final Sheet validationSheet;
        private int nextColumnIndex = 0;
        private final Map<String, String> namedRanges = new LinkedHashMap<>();

        private ValidationRegistry(XSSFWorkbook workbook, Sheet validationSheet) {
            this.workbook = workbook;
            this.validationSheet = validationSheet;
        }

        private String registerAllowedValues(String fieldId, List<String> values) {
            return namedRanges.computeIfAbsent(fieldId, (ignored) -> {
                int columnIndex = nextColumnIndex++;
                String rangeName = "validation_" + machineId(fieldId) + "_" + columnIndex;
                for (int rowIndex = 0; rowIndex < values.size(); rowIndex++) {
                    Row row = validationSheet.getRow(rowIndex);
                    if (row == null) {
                        row = validationSheet.createRow(rowIndex);
                    }
                    row.createCell(columnIndex).setCellValue(values.get(rowIndex));
                }
                String columnLetter = CellReference.convertNumToColString(columnIndex);
                Name name = workbook.createName();
                name.setNameName(rangeName);
                name.setRefersToFormula(validationSheet.getSheetName() + "!$" + columnLetter + "$1:$" + columnLetter + "$"
                        + Math.max(1, values.size()));
                return rangeName;
            });
        }

        private String machineId(String value) {
            String normalized = value == null ? "field" : value.trim().toLowerCase(Locale.ROOT);
            normalized = normalized.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
            return normalized.isBlank() ? "field" : normalized;
        }
    }
}
