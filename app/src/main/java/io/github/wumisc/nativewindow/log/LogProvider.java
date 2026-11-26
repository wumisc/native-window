package io.github.wumisc.nativewindow.log;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** 把 cache/logs 下的日志以 content:// 暴露给分享 Intent，避免 file:// 在 target 37 被拦。 */
public class LogProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.wumisc.nativewindow.logs";
    private static final String DIR = "logs";

    public static Uri uriFor(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + file.getName());
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        try {
            File dir = new File(getContext().getCacheDir(), DIR).getCanonicalFile();
            File file = new File(dir, uri.getLastPathSegment()).getCanonicalFile();
            if (!file.getPath().startsWith(dir.getPath() + File.separator)) {
                throw new FileNotFoundException("outside log dir");
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File file = new File(new File(getContext().getCacheDir(), DIR),
                uri.getLastPathSegment());
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = file.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { return 0; }
}
