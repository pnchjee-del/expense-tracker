package com.expensetracker.app;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PdfReportGenerator {

    public static class PdfResult {
        public final boolean success;
        public final String fileName;
        public final Uri uri;
        public final String message;

        public PdfResult(boolean success, String fileName, Uri uri, String message) {
            this.success = success;
            this.fileName = fileName;
            this.uri = uri;
            this.message = message;
        }
    }

    private static class ExpenseItem {
        String date;
        String category;
        String itemName;
        double quantity;
        String unit;
        double rate;
        double amount;
    }

    public static PdfResult createReport(Context context, String jsonExpenses, String startDate, String endDate) {
        try {
            List<ExpenseItem> items = new ArrayList<>();
            JSONArray arr = new JSONArray(jsonExpenses);
            double grandTotal = 0.0;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ExpenseItem item = new ExpenseItem();
                item.date = obj.optString("date", "");
                item.category = obj.optString("category", "");
                item.itemName = obj.optString("item_name", "");
                item.quantity = obj.optDouble("quantity", 1.0);
                item.unit = obj.optString("unit", "Pcs");
                item.rate = obj.optDouble("rate", 0.0);
                item.amount = obj.optDouble("amount", item.quantity * item.rate);
                items.add(item);
                grandTotal += item.amount;
            }

            final int PAGE_WIDTH = 612;   // Letter width in points
            final int PAGE_HEIGHT = 792;  // Letter height in points
            final int LEFT_MARGIN = 36;
            final int RIGHT_MARGIN = 576;
            final int TABLE_WIDTH = RIGHT_MARGIN - LEFT_MARGIN; // 540

            final int ROW_HEIGHT = 22;
            final int HEADER_HEIGHT = 24;
            final int TOP_TABLE_START_PAGE1 = 110;
            final int TOP_TABLE_START_LATER = 70;
            final int BOTTOM_LIMIT = 730;

            int rowsPerPage1 = (BOTTOM_LIMIT - TOP_TABLE_START_PAGE1 - HEADER_HEIGHT - 35) / ROW_HEIGHT;
            int rowsPerLaterPage = (BOTTOM_LIMIT - TOP_TABLE_START_LATER - HEADER_HEIGHT - 35) / ROW_HEIGHT;
            if (rowsPerPage1 <= 0) rowsPerPage1 = 20;
            if (rowsPerLaterPage <= 0) rowsPerLaterPage = 25;

            int totalPages = 1;
            if (items.size() > rowsPerPage1) {
                int rem = items.size() - rowsPerPage1;
                totalPages = 1 + (int) Math.ceil((double) rem / rowsPerLaterPage);
            }

            PdfDocument document = new PdfDocument();

            Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setColor(Color.parseColor("#1A365D"));
            titlePaint.setTextSize(18);
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subtitlePaint.setColor(Color.parseColor("#4A5568"));
            subtitlePaint.setTextSize(10);
            subtitlePaint.setTypeface(Typeface.DEFAULT);

            Paint tableHeaderBgPaint = new Paint();
            tableHeaderBgPaint.setColor(Color.parseColor("#2B6CB0"));

            Paint tableHeaderTxtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            tableHeaderTxtPaint.setColor(Color.WHITE);
            tableHeaderTxtPaint.setTextSize(9);
            tableHeaderTxtPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            Paint rowBgEven = new Paint();
            rowBgEven.setColor(Color.WHITE);

            Paint rowBgOdd = new Paint();
            rowBgOdd.setColor(Color.parseColor("#F7FAFC"));

            Paint gridLinePaint = new Paint();
            gridLinePaint.setColor(Color.parseColor("#E2E8F0"));
            gridLinePaint.setStrokeWidth(0.5f);

            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.parseColor("#1F2937"));
            textPaint.setTextSize(9);
            textPaint.setTypeface(Typeface.DEFAULT);

            Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            boldPaint.setColor(Color.parseColor("#1A365D"));
            boldPaint.setTextSize(9.5f);
            boldPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            footerPaint.setColor(Color.parseColor("#9CA3AF"));
            footerPaint.setTextSize(8);
            footerPaint.setTextAlign(Paint.Align.CENTER);

            int itemIdx = 0;

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create();
                PdfDocument.Page page = document.startPage(pageInfo);
                Canvas canvas = page.getCanvas();

                int currentY;

                if (pageNum == 1) {
                    canvas.drawText("Detailed Expense Management Report", LEFT_MARGIN, 55, titlePaint);
                    String sub = "Period: " + formatDate(startDate) + " to " + formatDate(endDate);
                    canvas.drawText(sub, LEFT_MARGIN, 75, subtitlePaint);
                    currentY = TOP_TABLE_START_PAGE1;
                } else {
                    canvas.drawText("Detailed Expense Management Report (Cont.)", LEFT_MARGIN, 45, subtitlePaint);
                    currentY = TOP_TABLE_START_LATER;
                }

                // Draw Table Header
                canvas.drawRect(LEFT_MARGIN, currentY, RIGHT_MARGIN, currentY + HEADER_HEIGHT, tableHeaderBgPaint);

                float headerTextY = currentY + 15.5f;
                canvas.drawText("Date", 42, headerTextY, tableHeaderTxtPaint);
                canvas.drawText("Category", 112, headerTextY, tableHeaderTxtPaint);
                canvas.drawText("Item Description", 195, headerTextY, tableHeaderTxtPaint);

                Paint.Align oldAlign = tableHeaderTxtPaint.getTextAlign();
                tableHeaderTxtPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText("Qty", 365, headerTextY, tableHeaderTxtPaint);
                tableHeaderTxtPaint.setTextAlign(oldAlign);

                canvas.drawText("Unit", 382, headerTextY, tableHeaderTxtPaint);

                tableHeaderTxtPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText("Rate", 475, headerTextY, tableHeaderTxtPaint);
                canvas.drawText("Total Price", 568, headerTextY, tableHeaderTxtPaint);
                tableHeaderTxtPaint.setTextAlign(oldAlign);

                currentY += HEADER_HEIGHT;

                int maxRowsThisPage = (pageNum == 1) ? rowsPerPage1 : rowsPerLaterPage;
                int rowsRendered = 0;

                while (itemIdx < items.size() && rowsRendered < maxRowsThisPage) {
                    ExpenseItem exp = items.get(itemIdx);

                    // Row background
                    Paint bgPaint = (rowsRendered % 2 == 0) ? rowBgEven : rowBgOdd;
                    canvas.drawRect(LEFT_MARGIN, currentY, RIGHT_MARGIN, currentY + ROW_HEIGHT, bgPaint);

                    float textY = currentY + 14.5f;

                    // Date
                    canvas.drawText(formatDate(exp.date), 42, textY, textPaint);

                    // Category (capitalized)
                    String catCap = capitalize(exp.category);
                    canvas.drawText(catCap, 112, textY, textPaint);

                    // Item Name (truncated if too long)
                    String itemText = exp.itemName;
                    if (itemText.length() > 24) itemText = itemText.substring(0, 22) + "..";
                    canvas.drawText(itemText, 195, textY, textPaint);

                    // Qty (Right aligned)
                    textPaint.setTextAlign(Paint.Align.RIGHT);
                    String qtyStr = (exp.quantity == (long) exp.quantity) ? String.format(Locale.ENGLISH, "%d", (long) exp.quantity) : String.format(Locale.ENGLISH, "%.2f", exp.quantity);
                    canvas.drawText(qtyStr, 365, textY, textPaint);
                    textPaint.setTextAlign(Paint.Align.LEFT);

                    // Unit
                    canvas.drawText(exp.unit, 382, textY, textPaint);

                    // Rate (Right aligned)
                    textPaint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(String.format(Locale.ENGLISH, "%,.2f", exp.rate), 475, textY, textPaint);

                    // Total (Right aligned)
                    canvas.drawText(String.format(Locale.ENGLISH, "%,.2f", exp.amount), 568, textY, boldPaint);
                    textPaint.setTextAlign(Paint.Align.LEFT);

                    // Bottom grid line
                    canvas.drawLine(LEFT_MARGIN, currentY + ROW_HEIGHT, RIGHT_MARGIN, currentY + ROW_HEIGHT, gridLinePaint);

                    currentY += ROW_HEIGHT;
                    itemIdx++;
                    rowsRendered++;
                }

                // If on final page, draw Grand Total row
                if (pageNum == totalPages) {
                    currentY += 4;
                    // Top bold line
                    Paint totalLinePaint = new Paint();
                    totalLinePaint.setColor(Color.parseColor("#1A365D"));
                    totalLinePaint.setStrokeWidth(1.5f);
                    canvas.drawLine(LEFT_MARGIN, currentY, RIGHT_MARGIN, currentY, totalLinePaint);

                    currentY += 16;
                    boldPaint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText("Grand Total:", 475, currentY, boldPaint);
                    canvas.drawText(String.format(Locale.ENGLISH, "%,.2f", grandTotal), 568, currentY, boldPaint);
                    boldPaint.setTextAlign(Paint.Align.LEFT);
                }

                // Footer
                String footerText = "Expense Control Center   •   Page " + pageNum + " of " + totalPages;
                canvas.drawText(footerText, PAGE_WIDTH / 2f, 765, footerPaint);

                document.finishPage(page);
            }

            String fileName = "Detailed_Report_" + startDate + "_to_" + endDate + ".pdf";
            Uri resultUri = null;
            boolean savedPublicly = false;

            // 1. Primary: Save directly to Android's public Downloads directory using MediaStore on API 29+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                    Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    resultUri = context.getContentResolver().insert(collection, values);

                    if (resultUri != null) {
                        OutputStream os = context.getContentResolver().openOutputStream(resultUri);
                        if (os != null) {
                            document.writeTo(os);
                            os.flush();
                            os.close();
                        }
                        values.clear();
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        context.getContentResolver().update(resultUri, values, null, null);
                        savedPublicly = true;
                    }
                } catch (Exception e) {
                    savedPublicly = false;
                }
            }

            // 2. Secondary: Also save into app's Downloads folder or external files dir
            File localFile = null;
            try {
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = context.getFilesDir();
                localFile = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(localFile);
                document.writeTo(fos);
                fos.flush();
                fos.close();

                if (resultUri == null) {
                    resultUri = Uri.parse("content://" + AppFileProvider.AUTHORITY + "/" + fileName);
                }
            } catch (Exception ignored) {}

            document.close();

            String message = savedPublicly
                    ? "Report saved to Downloads folder as " + fileName
                    : "Report generated successfully (" + fileName + ")";

            return new PdfResult(true, fileName, resultUri, message);

        } catch (Exception e) {
            return new PdfResult(false, "", null, "Error generating PDF: " + e.getMessage());
        }
    }

    private static String formatDate(String ymd) {
        if (ymd == null || ymd.length() != 10) return ymd;
        String[] parts = ymd.split("-");
        if (parts.length != 3) return ymd;
        return parts[2] + "-" + parts[1] + "-" + parts[0];
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] words = text.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) sb.append(w.substring(1).toLowerCase());
                sb.append(" ");
            }
        }
        return sb.toString().trim();
    }
}

