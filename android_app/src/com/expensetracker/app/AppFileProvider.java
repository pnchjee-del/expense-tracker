package com.expensetracker.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class AppFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.expensetracker.app.fileprovider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = getFileForUri(uri);
        if (file == null || !file.exists()) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = getFileForUri(uri);
        if (file != null && file.exists()) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException("File not found: " + uri);
    }

    private File getFileForUri(Uri uri) {
        if (getContext() == null || uri == null) return null;
        String fileName = uri.getLastPathSegment();
        if (fileName == null) return null;

        File extDocs = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (extDocs != null) {
            File f = new File(extDocs, fileName);
            if (f.exists()) return f;
        }

        File cacheDir = getContext().getCacheDir();
        if (cacheDir != null) {
            File f = new File(cacheDir, fileName);
            if (f.exists()) return f;
        }

        File filesDir = getContext().getFilesDir();
        if (filesDir != null) {
            File f = new File(filesDir, fileName);
            if (f.exists()) return f;
        }

        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
