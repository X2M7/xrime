/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ClipboardImageExternalState {
    static volatile Activity activity;
    static volatile EditText editor;
    private static boolean accepts = true;
    private static String mime = "image/png";
    private static int calls;
    private static String uri;
    private static String receivedMime;
    private static String sha256;
    private static boolean read;
    private static boolean completed;
    private static boolean permissionRequested;
    private static CountDownLatch hold;
    private static CountDownLatch selectionHold;
    private static boolean selectionEntered;
    private static String editorText = "external-editor-sentinel";

    static synchronized void configure(Bundle args) {
        accepts = args.getBoolean("accept", true);
        mime = args.getString("mime", "image/png");
        calls = 0;
        uri = null;
        receivedMime = null;
        sha256 = null;
        read = false;
        completed = false;
        permissionRequested = false;
        hold = args.getBoolean("hold", false) ? new CountDownLatch(1) : null;
        selectionHold = args.getBoolean("holdSelection", false) ? new CountDownLatch(1) : null;
        selectionEntered = false;
        editorText = args.getString("editorText", "external-editor-sentinel");
    }
    static synchronized String[] mimeTypes() { return new String[]{mime}; }
    static synchronized String editorText() { return editorText; }
    static synchronized Bundle snapshot() {
        Bundle result = new Bundle();
        result.putInt("calls", calls);
        result.putString("uri", uri);
        result.putString("mime", receivedMime);
        result.putString("sha256", sha256);
        result.putBoolean("read", read);
        result.putBoolean("completed", completed);
        result.putBoolean("permissionRequested", permissionRequested);
        result.putBoolean("selectionEntered", selectionEntered);
        return result;
    }
    static boolean receive(Context context, InputContentInfo content, int flags) {
        boolean allowed;
        CountDownLatch waiting;
        synchronized (ClipboardImageExternalState.class) {
            calls++;
            uri = content.getContentUri().toString();
            receivedMime = content.getDescription().getMimeType(0);
            allowed = accepts;
            waiting = hold;
        }
        if (waiting != null) {
            try { waiting.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        if (!allowed) {
            synchronized (ClipboardImageExternalState.class) { completed = true; }
            return false;
        }
        boolean requested = (flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0;
        try {
            if (requested) content.requestPermission();
            synchronized (ClipboardImageExternalState.class) { permissionRequested = requested; }
        } catch (RuntimeException ignored) {
            synchronized (ClipboardImageExternalState.class) { completed = true; }
            return false;
        }
        try (InputStream input = context.getContentResolver().openInputStream(content.getContentUri())) {
            if (input == null) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int size = 0, count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0 || (size += count) > 1024 * 1024) return false;
                digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
            synchronized (ClipboardImageExternalState.class) {
                sha256 = hex.toString();
                read = true;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (requested) content.releasePermission();
            synchronized (ClipboardImageExternalState.class) { completed = true; }
        }
    }
    static void finishActivity() {
        new Handler(Looper.getMainLooper()).post(() -> {
            Activity current = activity;
            if (current != null) current.finish();
        });
    }
    static synchronized void release() { if (hold != null) hold.countDown(); }
    static synchronized void releaseSelection() { if (selectionHold != null) selectionHold.countDown(); }
    static synchronized void resetSelectionEntered() { selectionEntered = false; }
    static synchronized void armSelection() { selectionEntered = false; selectionHold = new CountDownLatch(1); }
    static void awaitSelection() {
        CountDownLatch waiting;
        synchronized (ClipboardImageExternalState.class) { selectionEntered = true; waiting = selectionHold; }
        if (waiting != null) {
            try { waiting.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    static void select(int start, int end) {
        new Handler(Looper.getMainLooper()).post(() -> {
            EditText current = editor;
            if (current != null && start >= 0 && end >= start && end <= current.length()) current.setSelection(start, end);
        });
    }
}
