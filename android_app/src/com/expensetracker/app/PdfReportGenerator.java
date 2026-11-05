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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private static class ItemSummary {
        String itemName;
        String category;
        String unit;
        double quantity;
        double amount;
        Map<String, Double> units = new LinkedHashMap<>();
    }

    public static PdfResult createReport(Context context, String jsonExpenses, String startDate, String endDate) {
        return createReport(context, jsonExpenses, startDate, endDate, false);
    }

    public static PdfResult createReport(Context context, String jsonExpenses, String startDate, String endDate, boolean includeItemwise) {
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

            // Aggregate item-wise summary strictly per single item
            List<ItemSummary> summaryList = new ArrayList<>();
            if (includeItemwise && !items.isEmpty()) {
                Map<String, ItemSummary> summaryMap = new LinkedHashMap<>();
                for (ExpenseItem item : items) {
                    String key = item.itemName.toLowerCase(Locale.ENGLISH).trim();
                    ItemSummary sum = summaryMap.get(key);
                    if (sum == null) {
                        sum = new ItemSummary();
                        sum.itemName = item.itemName.trim();
                        sum.category = item.category;
                        sum.amount = 0.0;
                        summaryMap.put(key, sum);
                    }
                    sum.amount += item.amount;
                    Double prevQ = sum.units.get(item.unit);
                    sum.units.put(item.unit, (prevQ == null ? 0.0 : prevQ) + item.quantity);
                }
                for (ItemSummary s : summaryMap.values()) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, Double> e : s.units.entrySet()) {
                        if (sb.length() > 0) sb.append(", ");
                        double q = e.getValue();
                        String qStr = (q == (long) q) ? String.valueOf((long) q) : String.format(Locale.ENGLISH, "%.2f", q);
                        sb.append(qStr).append(" ").append(e.getKey());
                    }
                    s.unit = sb.toString();
                }
                summaryList.addAll(summaryMap.values());
                Collections.sort(summaryList, new Comparator<ItemSummary>() {
                    @Override
                    public int compare(ItemSummary a, ItemSummary b) {
                        return Double.compare(b.amount, a.amount);
                    }
                });
            }

            final int PAGE_WIDTH = 612;   // Letter width in points
            final int PAGE_HEIGHT = 792;  // Letter height in points
            final int LEFT_MARGIN = 36;
            final int RIGHT_MARGIN = 576;

            final int ROW_HEIGHT = 22;
            final int HEADER_HEIGHT = 24;
            final int TOP_TABLE_START_PAGE1 = 110;
            final int TOP_TABLE_START_LATER = 70;
            final int BOTTOM_LIMIT = 730;

            // 1. Simulation pass to calculate totalPages
            int simPage = 1;
            int simY = TOP_TABLE_START_PAGE1 + HEADER_HEIGHT;
            for (int i = 0; i < items.size(); i++) {
                if (simY + ROW_HEIGHT > BOTTOM_LIMIT) {
                    simPage++;
                    simY = TOP_TABLE_START_LATER + HEADER_HEIGHT;
                }
                simY += ROW_HEIGHT;
            }
            if (simY + 24 > BOTTOM_LIMIT) {
                simPage++;
                simY = TOP_TABLE_START_LATER;
            }
            simY += 24;

            if (includeItemwise && !summaryList.isEmpty()) {
                simY += 15;
                if (simY + 24 + HEADER_HEIGHT + ROW_HEIGHT * 2 > BOTTOM_LIMIT) {
                    simPage++;
                    simY = TOP_TABLE_START_LATER;
                }
                simY += 24 + HEADER_HEIGHT;
                for (int i = 0; i < summaryList.size(); i++) {
                    if (simY + ROW_HEIGHT > BOTTOM_LIMIT) {
                        simPage++;
                        simY = TOP_TABLE_START_LATER + HEADER_HEIGHT;
                    }
                    simY += ROW_HEIGHT;
                }
            }
            final int totalPages = Math.max(1, simPage);

            PdfDocument document = new PdfDocument();

            Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setColor(Color.parseColor("#1A365D"));
            titlePaint.setTextSize(18);
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subtitlePaint.setColor(Color.parseColor("#4A5568"));
            subtitlePaint.setTextSize(10);
            subtitlePaint.setTypeface(Typeface.DEFAULT);

            Paint sectionHeadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            sectionHeadingPaint.setColor(Color.parseColor("#1A365D"));
            sectionHeadingPaint.setTextSize(13);
            sectionHeadingPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            // Main table header paint (Blue)
            Paint tableHeaderBgPaint = new Paint();
            tableHeaderBgPaint.setColor(Color.parseColor("#2B6CB0"));

            // Summary table header paint (Teal)
            Paint summaryHeaderBgPaint = new Paint();
            summaryHeaderBgPaint.setColor(Color.parseColor("#0F766E"));

            Paint tableHeaderTxtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            tableHeaderTxtPaint.setColor(Color.WHITE);
            tableHeaderTxtPaint.setTextSize(9);
            tableHeaderTxtPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            Paint rowBgEven = new Paint();
            rowBgEven.setColor(Color.WHITE);

            Paint rowBgOdd = new Paint();
            rowBgOdd.setColor(Color.parseColor("#F7FAFC"));

            Paint summaryRowBgOdd = new Paint();
            summaryRowBgOdd.setColor(Color.parseColor("#F0FDFA"));

            Paint gridLinePaint = new Paint();
            gridLinePaint.setColor(Color.parseColor("#E2E8F0"));
            gridLinePaint.setStrokeWidth(0.5f);

            Paint summaryGridLinePaint = new Paint();
            summaryGridLinePaint.setColor(Color.parseColor("#CCFBF1"));
            summaryGridLinePaint.setStrokeWidth(0.5f);

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

            // 2. Render actual pages
            int curPage = 1;
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, curPage).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            // Page 1 Title
            canvas.drawText("Detailed Expense Management Report", LEFT_MARGIN, 55, titlePaint);
            String sub = "Period: " + formatDate(startDate) + " to " + formatDate(endDate);
            canvas.drawText(sub, LEFT_MARGIN, 75, subtitlePaint);

            int currentY = TOP_TABLE_START_PAGE1;

            // Draw Initial Main Table Header
            drawMainTableHeader(canvas, currentY, HEADER_HEIGHT, LEFT_MARGIN, RIGHT_MARGIN, tableHeaderBgPaint, tableHeaderTxtPaint);
            currentY += HEADER_HEIGHT;

            // Render detailed items
            for (int i = 0; i < items.size(); i++) {
                if (currentY + ROW_HEIGHT > BOTTOM_LIMIT) {
                    drawFooter(canvas, curPage, totalPages, PAGE_WIDTH, footerPaint);
                    document.finishPage(page);

                    curPage++;
                    pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, curPage).create();
                    page = document.startPage(pageInfo);
                    canvas = page.getCanvas();

                    canvas.drawText("Detailed Expense Management Report (Cont.)", LEFT_MARGIN, 45, subtitlePaint);
                    currentY = TOP_TABLE_START_LATER;
                    drawMainTableHeader(canvas, currentY, HEADER_HEIGHT, LEFT_MARGIN, RIGHT_MARGIN, tableHeaderBgPaint, tableHeaderTxtPaint);
                    currentY += HEADER_HEIGHT;
                }

                ExpenseItem exp = items.get(i);
                Paint bgPaint = (i % 2 == 0) ? rowBgEven : rowBgOdd;
                canvas.drawRect(LEFT_MARGIN, currentY, RIGHT_MARGIN, currentY + ROW_HEIGHT, bgPaint);

                float textY = currentY + 14.5f;
                canvas.drawText(formatDate(exp.date), 42, textY, textPaint);
                canvas.drawText(capitalize(exp.category), 112, textY, textPaint);

                String itemText = exp.itemName;
                if (itemText.length() > 24) itemText = itemText.substring(0, 22) + "..";
                canvas.drawText(itemText, 195, textY, textPaint);

                textPaint.setTextAlign(Paint.Align.RIGHT);
                String qtyStr = (exp.quantity == (long) exp.quantity) ? String.format(Locale.ENGLISH, "%d", (long) exp.quantity) : String.format(Locale.ENGLISH, "%.2f", exp.quantity);
                canvas.drawText(qtyStr, 365, textY, textPaint);
                textPaint.setTextAlign(Paint.Align.LEFT);

                canvas.drawText(exp.unit, 382, textY, textPaint);

                textPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(String.format(Locale.ENGLISH, "%,.2f", exp.rate), 475, textY, textPaint);
                canvas.drawText(String.format(Locale.ENGLISH, "%,.2f", exp.amount), 568, textY, boldPaint);
                textPaint.setTextAlign(Paint.Align.LEFT);

                canvas.drawLine(LEFT_MARGIN, currentY + ROW_HEIGHT, RIGHT_MARGIN, currentY + ROW_HEIGHT, gridLinePaint);
                currentY += ROW_HEIGHT;
            }

            // Draw Grand Total row
            if (currentY + 24 > BOTTOM_LIMIT) {
                drawFooter(canvas, curPage, totalPages, PAGE_WIDTH, footerPaint);
                document.finishPage(page);

                curPage++;
                pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, curPage).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();
                currentY = TOP_TABLE_START_LATER;
            }

            currentY += 4;
            Paint totalLinePaint = new Paint();
            totalLinePaint.setColor(Color.parseColor("#1A365D"));
            totalLinePaint.setStrokeWidth(1.5f);
            canvas.drawLine(LEFT_MARGIN, currentY, RIGHT_MARGIN, currentY, totalLinePaint);

            currentY += 16;
            boldPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("Grand Total:", 475, currentY, boldPaint);
            canvas.drawText(String.format(Locale.ENGLISH, "%,.2f", grandTotal), 568, currentY, boldPaint);
            boldPaint.setTextAlign(Paint.Align.LEFT);

            // Render Item-wise Summary if requested
            if (includeItemwise && !summaryList.isEmpty()) {
                currentY += 16;
                if (currentY + 24 + HEADER_HEIGHT + ROW_HEIGHT * 2 > BOTTOM_LIMIT) {
                    drawFooter(canvas, curPage, totalPages, PAGE_WIDTH, footerPaint);
                    document.finishPage(page);

                    curPage++;
                    pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, curPage).create();
                    page = document.startPage(pageInfo);
                    canvas = page.getCanvas();
                    currentY = TOP_TABLE_START_LATER;
                }

                canvas.drawText("Item-wise Spending Summary", LEFT_MARGIN, currentY + 16, sectionHeadingPaint);
                currentY += 24;

                drawSummaryTableHeader(canvas, currentY, HEADER_HEIGHT, LEFT_MARGIN, RIGHT_MARGIN, summaryHeaderBgPaint, tableHeaderTxtPaint);
                currentY += HEADER_HEIGHT;

                for (int i = 0; i < summaryList.size(); i++) {
                    if (currentY + ROW_HEIGHT > BOTTOM_LIMIT) {
                        drawFooter(canvas, curPage, totalPages, PAGE_WIDTH, footerPaint);
                        document.finishPage(page);

                        curPage++;
                        pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, curPage).create();
                        page = document.startPage(pageInfo);
                        canvas = page.getCanvas();

                        canvas.drawText("Item-wise Spending Summary (Cont.)", LEFT_MARGIN, 45, subtitlePaint);
                        currentY = TOP_TABLE_START_LATER;
                        drawSummaryTableHeader(canvas, currentY, HEADER_HEIGHT, LEFT_MARGIN, RIGHT_MARGIN, summaryHeaderBgPaint, tableHeaderTxtPaint);
                        currentY += HEADER_HEIGHT;
                    }

                    ItemSummary sumItem = summaryList.get(i);
                    Paint bgPaint = (i % 2 == 0) ? rowBgEven : summaryRowBgOdd;
                    canvas.drawRect(LEFT_MARGIN, currentY, RIGHT_MARGIN, currentY + ROW_HEIGHT, bgPaint);

                    float textY = currentY + 14.5f;
                    String itemText = sumItem.itemName;
                    if (itemText.length() > 24) itemText = itemText.substring(0, 22) + "..";
                    canvas.drawText(itemText, 42, textY, textPaint);
                    canvas.drawText(capitalize(sumItem.category), 215, textY, textPaint);

                    textPaint.setTextAlign(Paint.Align.RIGHT);
                    String qtyStr = (sumItem.quantity == (long) sumItem.quantity) ? String.format(Locale.ENGLISH, "%d", (long) sumItem.quantity) : String.format(Locale.ENGLISH, "%.2f", sumItem.quantity);
                    canvas.drawText(qtyStr, 365, textY, textPaint);
                    textPaint.setTextAlign(Paint.Align.LEFT);

                    canvas.drawText(sumItem.unit, 382, textY, textPaint);

                    textPaint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(String.format(Locale.ENGLISH, "%,.2f", sumItem.amount), 485, textY, boldPaint);

                    double pct = (grandTotal > 0) ? (sumItem.amount / grandTotal * 100.0) : 0.0;
                    canvas.drawText(String.format(Locale.ENGLISH, "%.1f%%", pct), 568, textY, textPaint);
                    textPaint.setTextAlign(Paint.Align.LEFT);

                    canvas.drawLine(LEFT_MARGIN, currentY + ROW_HEIGHT, RIGHT_MARGIN, currentY + ROW_HEIGHT, summaryGridLinePaint);
                    currentY += ROW_HEIGHT;
                }
            }

            // Finish the last page
            drawFooter(canvas, curPage, totalPages, PAGE_WIDTH, footerPaint);
            document.finishPage(page);

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
            try {
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = context.getFilesDir();
                File localFile = new File(dir, fileName);
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

    public static PdfResult createItemUsageReport(Context context, String jsonExpenses, String targetItem, String startDate, String endDate) {
        try {
            List<ExpenseItem> allItems = new ArrayList<>();
            JSONArray arr = new JSONArray(jsonExpenses);

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
                allItems.add(item);
            }

            // Filter for target item within startDate to endDate
            String cleanTarget = targetItem != null ? targetItem.trim() : "";
            List<ExpenseItem> items = new ArrayList<>();
            double totalSpend = 0.0;
            Map<String, Double> unitMap = new LinkedHashMap<>();

            boolean hasDateRange = startDate != null && !startDate.trim().isEmpty() && !startDate.equalsIgnoreCase("all") &&
                                   endDate != null && !endDate.trim().isEmpty() && !endDate.equalsIgnoreCase("all");

            // 1. Exact match attempt (case-insensitive)
            for (ExpenseItem exp : allItems) {
                if (!hasDateRange || (exp.date.compareTo(startDate) >= 0 && exp.date.compareTo(endDate) <= 0)) {
                    if (exp.itemName.trim().equalsIgnoreCase(cleanTarget)) {
                        items.add(exp);
                    }
                }
            }

            // 2. Substring fallback if no exact match
            if (items.isEmpty()) {
                String lowerTarget = cleanTarget.toLowerCase(Locale.ENGLISH);
                for (ExpenseItem exp : allItems) {
                    if (!hasDateRange || (exp.date.compareTo(startDate) >= 0 && exp.date.compareTo(endDate) <= 0)) {
                        if (exp.itemName.toLowerCase(Locale.ENGLISH).contains(lowerTarget)) {
                            items.add(exp);
                        }
                    }
                }
            }

            Collections.sort(items, new Comparator<ExpenseItem>() {
                @Override
                public int compare(ExpenseItem a, ExpenseItem b) {
                    return a.date.compareTo(b.date);
                }
            });

            for (ExpenseItem exp : items) {
                totalSpend += exp.amount;
                Double prev = unitMap.get(exp.unit);
                unitMap.put(exp.unit, (prev == null ? 0.0 : prev) + exp.quantity);
            }

            StringBuilder qtySb = new StringBuilder();
            for (Map.Entry<String, Double> entry : unitMap.entrySet()) {
                if (qtySb.length() > 0) qtySb.append(", ");
                double q = entry.getValue();
                String qStr = (q == (long) q) ? String.valueOf((long) q) : String.format(Locale.ENGLISH, "%.2f", q);
                qtySb.append(qStr).append(" ").append(entry.getKey());
            }
            String totalQtyStr = qtySb.length() > 0 ? qtySb.toString() : "0";

            String avgRateStr = "Rs. 0.00";
            if (unitMap.size() == 1) {
                String singleUnit = unitMap.keySet().iterator().next();
                double singleQ = unitMap.values().iterator().next();
                if (singleQ > 0) {
                    avgRateStr = String.format(Locale.ENGLISH, "Rs. %,.2f / %s", (totalSpend / singleQ), singleUnit);
                }
            } else if (!items.isEmpty()) {
                double sumRate = 0.0;
                for (ExpenseItem it : items) sumRate += it.rate;
                avgRateStr = String.format(Locale.ENGLISH, "Rs. %,.2f (avg)", (sumRate / items.size()));
            }

            final int PAGE_WIDTH = 612;
            final int PAGE_HEIGHT = 792;
            final int LEFT_MARGIN = 36;
            final int RIGHT_MARGIN = 576;

            final int ROW_HEIGHT = 22;
            final int HEADER_HEIGHT = 24;
            final int TOP_TABLE_START_PAGE1 = 175;
            final int TOP_TABLE_START_LATER = 70;
            final int BOTTOM_LIMIT = 730;

            // Simulation pass for totalPages
            int simPage = 1;
            int simY = TOP_TABLE_START_PAGE1 + HEADER_HEIGHT;
            int renderCount = Math.max(1, items.size());
            for (int i = 0; i < renderCount; i++) {
                if (simY + ROW_HEIGHT > BOTTOM_LIMIT) {
                    simPage++;
                    simY = TOP_TABLE_START_LATER + HEADER_HEIGHT;
                }
                simY += ROW_HEIGHT;
            }
            if (simY + 24 > BOTTOM_LIMIT) {
                simPage++;
            }
            final int totalPages = Math.max(1, simPage);

            PdfDocument document = new PdfDocument();

            Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setColor(Color.parseColor("#1A365D"));
            titlePaint.setTextSize(18);
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subtitlePaint.setColor(Color.parseColor("#4A5568"));
            subtitlePaint.setTextSize(10);
            subtitlePaint.setTypeface(Typeface.DEFAULT);

            Paint bannerBgPaint = new Paint();
            bannerBgPaint.setColor(Color.parseColor("#F0F9FF"));

            Paint bannerBorderPaint = new Paint();
            bannerBorderPaint.setColor(Color.parseColor("#BAE6FD"));
            bannerBorderPaint.setStyle(Paint.Style.STROKE);
            bannerBorderPaint.setStrokeWidth(1.0f);

            Paint kpiLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            kpiLabelPaint.setColor(Color.parseColor("#0369A1"));
            kpiLabelPaint.setTextSize(8f);
            kpiLabelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            kpiLabelPaint.setTextAlign(Paint.Align.CENTER);

            Paint kpiValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            kpiValuePaint.setColor(Color.parseColor("#0C4A6E"));
            kpiValuePaint.setTextSize(10.5f);
            kpiValuePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            kpiValuePaint.setTextAlign(Paint.Align.CENTER);

            Paint tableHeaderBgPaint = new Paint();
            tableHeaderBgPaint.setColor(Color.parseColor("#0284C7"));

            Paint tableHeaderTxtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            tableHeaderTxtPaint.setColor(Color.WHITE);
            tableHeaderTxtPaint.setTextSize(9);
            tableHeaderTxtPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            Paint rowBgEven = new Paint();
            rowBgEven.setColor(Color.WHITE);

            Paint rowBgOdd = new Paint();
            rowBgOdd.setColor(Color.parseColor("#F8FAFC"));

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

            int curPage = 1;
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, curPage).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            // Page 1 Header
            canvas.drawText("Item Usage Statement: " + capitalize(cleanTarget), LEFT_MARGIN, 52, titlePaint);
            String sub = hasDateRange ? ("Period: " + formatDate(startDate) + " to " + formatDate(endDate)) : "Period: All Time (All Records)";
            canvas.drawText(sub, LEFT_MARGIN, 70, subtitlePaint);

            // KPI 4-block banner
            float bannerTop = 86;
            float bannerHeight = 52;
            canvas.drawRect(LEFT_MARGIN, bannerTop, RIGHT_MARGIN, bannerTop + bannerHeight, bannerBgPaint);
            canvas.drawRect(LEFT_MARGIN, bannerTop, RIGHT_MARGIN, bannerTop + bannerHeight, bannerBorderPaint);

            float colW = (RIGHT_MARGIN - LEFT_MARGIN) / 4f;
            float[] centers = {
                LEFT_MARGIN + colW * 0.5f,
                LEFT_MARGIN + colW * 1.5f,
                LEFT_MARGIN + colW * 2.5f,
                LEFT_MARGIN + colW * 3.5f
            };

            for (int k = 1; k < 4; k++) {
                float divX = LEFT_MARGIN + colW * k;
                canvas.drawLine(divX, bannerTop + 6, divX, bannerTop + bannerHeight - 6, bannerBorderPaint);
            }

            canvas.drawText("TOTAL QUANTITY", centers[0], bannerTop + 18, kpiLabelPaint);
            canvas.drawText(totalQtyStr, centers[0], bannerTop + 37, kpiValuePaint);

            canvas.drawText("TOTAL SPEND", centers[1], bannerTop + 18, kpiLabelPaint);
            canvas.drawText(String.format(Locale.ENGLISH, "Rs. %,.2f", totalSpend), centers[1], bannerTop + 37, kpiValuePaint);

            canvas.drawText("PURCHASES COUNT", centers[2], bannerTop + 18, kpiLabelPaint);
            canvas.drawText(items.size() + " times", centers[2], bannerTop + 37, kpiValuePaint);

            canvas.drawText("AVERAGE RATE", centers[3], bannerTop + 18, kpiLabelPaint);
            canvas.drawText(avgRateStr, centers[3], bannerTop + 37, kpiValuePaint);

            int currentY = TOP_TABLE_START_PAGE1;
            drawMainTableHeader(canvas, currentY, HEADER_HEIGHT, LEFT_MARGIN, RIGHT_MARGIN, tableHeaderBgPaint, tableHeaderTxtPaint);
            currentY += HEADER_HEIGHT;

            if (items.isEmpty()) {
                canvas.drawRect(LEFT_MARGIN, currentY, RIGHT_MARGIN, currentY + ROW_HEIGHT, rowBgEven);
                canvas.drawLine(LEFT_MARGIN, currentY + ROW_HEIGHT, RIGHT_MARGIN, currentY + ROW_HEIGHT, gridLinePaint);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("No purchase records found for this item in selected period", PAGE_WIDTH / 2f, currentY + 14.5f, textPaint);
                textPaint.setTextAlign(Paint.Align.LEFT);
                currentY += ROW_HEIGHT;
            } else {
                for (int i = 0; i < items.size(); i++) {
                    if (currentY + ROW_HEIGHT > BOTTOM_LIMIT) {
                        drawFooter(canvas, curPage, totalPages, PAGE_WIDTH, footerPaint);
                        document.finishPage(page);

                        curPage++;
                        pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, curPage).create();
                        page = document.startPage(pageInfo);
                        canvas = page.getCanvas();

                        canvas.drawText("Item Usage Statement: " + capitalize(cleanTarget) + " (Cont.)", LEFT_MARGIN, 45, subtitlePaint);
                        currentY = TOP_TABLE_START_LATER;
                        drawMainTableHeader(canvas, currentY, HEADER_HEIGHT, LEFT_MARGIN, RIGHT_MARGIN, tableHeaderBgPaint, tableHeaderTxtPaint);
                        currentY += HEADER_HEIGHT;
                    }

                    ExpenseItem exp = items.get(i);
                    Paint bgPaint = (i % 2 == 0) ? rowBgEven : rowBgOdd;
                    canvas.drawRect(LEFT_MARGIN, currentY, RIGHT_MARGIN, currentY + ROW_HEIGHT, bgPaint);

                    float textY = currentY + 14.5f;
                    textPaint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(formatDate(exp.date), 42, textY, textPaint);
                    canvas.drawText(capitalize(exp.category), 112, textY, textPaint);

                    String itemText = exp.itemName;
                    if (itemText.length() > 24) itemText = itemText.substring(0, 22) + "..";
                    canvas.drawText(itemText, 195, textY, textPaint);

                    textPaint.setTextAlign(Paint.Align.RIGHT);
                    String qStr = (exp.quantity == (long) exp.quantity) ? String.format(Locale.ENGLISH, "%d", (long) exp.quantity) : String.format(Locale.ENGLISH, "%.2f", exp.quantity);
                    canvas.drawText(qStr, 365, textY, textPaint);

                    textPaint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(exp.unit, 382, textY, textPaint);

                    textPaint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(String.format(Locale.ENGLISH, "%,.2f", exp.rate), 475, textY, textPaint);
                    canvas.drawText(String.format(Locale.ENGLISH, "%,.2f", exp.amount), 568, textY, textPaint);

                    canvas.drawLine(LEFT_MARGIN, currentY + ROW_HEIGHT, RIGHT_MARGIN, currentY + ROW_HEIGHT, gridLinePaint);
                    currentY += ROW_HEIGHT;
                }
            }

            // Total row
            if (currentY + 24 > BOTTOM_LIMIT) {
                drawFooter(canvas, curPage, totalPages, PAGE_WIDTH, footerPaint);
                document.finishPage(page);

                curPage++;
                pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, curPage).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();
                currentY = TOP_TABLE_START_LATER;
            }

            canvas.drawRect(LEFT_MARGIN, currentY, RIGHT_MARGIN, currentY + 24, rowBgEven);
            Paint linePaint = new Paint();
            linePaint.setColor(Color.parseColor("#0284C7"));
            linePaint.setStrokeWidth(1.5f);
            canvas.drawLine(380, currentY, RIGHT_MARGIN, currentY, linePaint);

            boldPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("Total Spend:", 475, currentY + 16, boldPaint);
            canvas.drawText("Rs. " + String.format(Locale.ENGLISH, "%,.2f", totalSpend), 568, currentY + 16, boldPaint);
            boldPaint.setTextAlign(Paint.Align.LEFT);

            drawFooter(canvas, curPage, totalPages, PAGE_WIDTH, footerPaint);
            document.finishPage(page);

            String safeItem = cleanTarget.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String fileDate = hasDateRange ? (startDate + "_to_" + endDate) : "All_Time";
            String fileName = "Item_Usage_" + safeItem + "_" + fileDate + ".pdf";
            Uri resultUri = null;
            boolean savedPublicly = false;

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

            try {
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = context.getFilesDir();
                File localFile = new File(dir, fileName);
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
                    ? "Item usage report saved to Downloads as " + fileName
                    : "Item usage report generated successfully (" + fileName + ")";

            return new PdfResult(true, fileName, resultUri, message);

        } catch (Exception e) {
            return new PdfResult(false, "", null, "Error generating Item PDF: " + e.getMessage());
        }
    }

    private static void drawMainTableHeader(Canvas canvas, int currentY, int height, int left, int right, Paint bgPaint, Paint txtPaint) {
        canvas.drawRect(left, currentY, right, currentY + height, bgPaint);
        float headerTextY = currentY + 15.5f;
        canvas.drawText("Date", 42, headerTextY, txtPaint);
        canvas.drawText("Category", 112, headerTextY, txtPaint);
        canvas.drawText("Item Description", 195, headerTextY, txtPaint);

        Paint.Align oldAlign = txtPaint.getTextAlign();
        txtPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("Qty", 365, headerTextY, txtPaint);
        txtPaint.setTextAlign(oldAlign);

        canvas.drawText("Unit", 382, headerTextY, txtPaint);

        txtPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("Rate", 475, headerTextY, txtPaint);
        canvas.drawText("Total Price", 568, headerTextY, txtPaint);
        txtPaint.setTextAlign(oldAlign);
    }

    private static void drawSummaryTableHeader(Canvas canvas, int currentY, int height, int left, int right, Paint bgPaint, Paint txtPaint) {
        canvas.drawRect(left, currentY, right, currentY + height, bgPaint);
        float headerTextY = currentY + 15.5f;
        canvas.drawText("Item Description", 42, headerTextY, txtPaint);
        canvas.drawText("Category", 215, headerTextY, txtPaint);

        Paint.Align oldAlign = txtPaint.getTextAlign();
        txtPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("Total Qty", 365, headerTextY, txtPaint);
        txtPaint.setTextAlign(oldAlign);

        canvas.drawText("Unit", 382, headerTextY, txtPaint);

        txtPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("Total Spend", 485, headerTextY, txtPaint);
        canvas.drawText("% Total", 568, headerTextY, txtPaint);
        txtPaint.setTextAlign(oldAlign);
    }

    private static void drawFooter(Canvas canvas, int pageNum, int totalPages, int pageWidth, Paint footerPaint) {
        String footerText = "Expense Control Center   •   Page " + pageNum + " of " + totalPages;
        canvas.drawText(footerText, pageWidth / 2f, 765, footerPaint);
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
