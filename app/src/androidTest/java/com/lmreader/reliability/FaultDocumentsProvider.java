package com.lmreader.reliability;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.*;

/** Pure Java because this provider runs in the test APK's separate process. */
public class FaultDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.lmreader.test.reliability.documents";
    private final String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
    @Override public boolean onCreate() { return true; }
    private File file(String id) throws FileNotFoundException {
        if (id.contains("..")) throw new FileNotFoundException();
        File root = new File(getContext().getFilesDir(), "reliability-documents"); root.mkdirs();
        File result = new File(root, id);
        if (!id.contains("/")) {
            result.mkdirs();
            if (id.startsWith("source") && result.list().length == 0) for (int i = 1; i <= 2; i++) {
                Bitmap bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(i == 1 ? android.graphics.Color.RED : android.graphics.Color.BLUE);
                try (FileOutputStream out = new FileOutputStream(new File(result, i + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                catch (IOException e) { throw new FileNotFoundException(e.toString()); }
                finally { bitmap.recycle(); }
            }
        }
        return result;
    }
    private void row(MatrixCursor cursor, String id) throws FileNotFoundException {
        File f = file(id);
        int flags = id.startsWith("revoked") ? 0 : Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME | (f.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE);
        String mime = f.isDirectory() ? Document.MIME_TYPE_DIR : f.getName().endsWith(".png") ? "image/png" : "application/octet-stream";
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (column.equals(Document.COLUMN_DOCUMENT_ID)) value = id;
            if (column.equals(Document.COLUMN_DISPLAY_NAME)) value = f.getName();
            if (column.equals(Document.COLUMN_MIME_TYPE)) value = mime;
            if (column.equals(Document.COLUMN_FLAGS)) value = flags;
            if (column.equals(Document.COLUMN_SIZE)) value = f.length();
            if (column.equals(Document.COLUMN_LAST_MODIFIED)) value = f.lastModified();
            row.add(value);
        }
    }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection == null ? new String[]{"root_id", "document_id"} : projection); }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException { MatrixCursor cursor = new MatrixCursor(projection == null ? columns : projection); row(cursor, id); return cursor; }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? columns : projection);
        File[] files = file(parent).listFiles();
        if (files != null) for (File f : files) row(cursor, parent + "/" + f.getName());
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String id) { return id.startsWith(parent + "/"); }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        file(parent); String id = parent + "/" + name; File f = file(id);
        try { if (!(mime.equals(Document.MIME_TYPE_DIR) ? f.mkdir() : f.createNewFile())) throw new IOException("create failed"); }
        catch (IOException e) { throw new FileNotFoundException(e.toString()); } return id;
    }
    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        if (id.contains("no-rename")) throw new UnsupportedOperationException("rename disabled");
        String next = id.substring(0, id.lastIndexOf('/')) + "/" + name;
        if (!file(id).renameTo(file(next))) throw new FileNotFoundException("rename failed"); return next;
    }
    private boolean remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) if (!remove(child)) return false; return file.delete(); }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (id.startsWith("no-delete")) throw new FileNotFoundException("delete disabled");
        if (!remove(file(id))) throw new FileNotFoundException("delete failed");
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (id.startsWith("fail-write") && mode.contains("w")) throw new FileNotFoundException("simulated disk full");
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
}
