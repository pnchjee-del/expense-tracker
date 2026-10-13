package com.expensetracker.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView webView;
    private WebView printWebView;
    private static final String DB_FILENAME = "expenses_db.json";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.parseColor("#312E81"));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public class AndroidBridge {
        private final Context context;

        public AndroidBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public String loadData() {
            try {
                File file = new File(context.getFilesDir(), DB_FILENAME);
                if (!file.exists()) {
                    return "";
                }
                FileInputStream fis = new FileInputStream(file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void saveData(String jsonData) {
            try {
                File file = new File(context.getFilesDir(), DB_FILENAME);
                FileOutputStream fos = new FileOutputStream(file, false);
                fos.write(jsonData.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.close();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String generatePdf(final String jsonExpenses, final String startDate, final String endDate) {
            final PdfReportGenerator.PdfResult result = PdfReportGenerator.createReport(context, jsonExpenses, startDate, endDate);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (result.success) {
                        Toast.makeText(MainActivity.this, "✅ " + result.message, Toast.LENGTH_LONG).show();

                        if (result.uri != null) {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setDataAndType(result.uri, "application/pdf");
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                Intent chooser = Intent.createChooser(intent, "Open Expense Report PDF");
                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(chooser);
                            } catch (Exception viewEx) {
                                try {
                                    Intent share = new Intent(Intent.ACTION_SEND);
                                    share.setType("application/pdf");
                                    share.putExtra(Intent.EXTRA_STREAM, result.uri);
                                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(Intent.createChooser(share, "Share Expense Report PDF"));
                                } catch (Exception ignored) {}
                            }
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "❌ Failed to generate PDF: " + result.message, Toast.LENGTH_LONG).show();
                    }
                }
            });

            try {
                JSONObject response = new JSONObject();
                response.put("success", result.success);
                response.put("fileName", result.fileName);
                response.put("message", result.message);
                return response.toString();
            } catch (Exception e) {
                return "{\"success\":" + result.success + "}";
            }
        }

        @JavascriptInterface
        public void printReport(final String htmlContent, final String jobName) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    printWebView = new WebView(MainActivity.this);
                    printWebView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                            if (printManager != null) {
                                PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(jobName);
                                PrintAttributes attributes = new PrintAttributes.Builder()
                                        .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
                                        .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                        .build();
                                printManager.print(jobName, adapter, attributes);
                            }
                        }
                    });
                    printWebView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
                }
            });
        }
    }
}
