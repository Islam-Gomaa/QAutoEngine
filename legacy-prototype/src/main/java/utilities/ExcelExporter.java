package utilities;

import models.LinkResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.util.List;

public class ExcelExporter {

    public static void export(List<LinkResult> results) {

        try (Workbook workbook = new XSSFWorkbook()) {

            Sheet sheet = workbook.createSheet("Results");

            String[] headers = {"Text", "URL", "Status", "Time(ms)", "Result"};

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int rowNum = 1;
            for (LinkResult r : results) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(r.text);
                row.createCell(1).setCellValue(r.url);
                row.createCell(2).setCellValue(r.status);
                row.createCell(3).setCellValue(r.timeMs);
                row.createCell(4).setCellValue(r.result);
            }

            FileOutputStream fileOut = new FileOutputStream("report.xlsx");
            workbook.write(fileOut);

            System.out.println("📊 Excel report generated");

        } catch (Exception e) {
            System.out.println("❌ Failed to export Excel");
        }
    }
}