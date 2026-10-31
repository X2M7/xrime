/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.Process;
import android.content.pm.PackageManager;

/** Signature-protected, content-free control channel for the external editor fixture. */
public final class ClipboardImageExternalStateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (getContext().getPackageManager().checkSignatures(Binder.getCallingUid(), Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Fixture access denied");
        }
        if ("configure".equals(method)) { ClipboardImageExternalState.configure(extras == null ? new Bundle() : extras); return new Bundle(); }
        if ("state".equals(method)) return ClipboardImageExternalState.snapshot();
        if ("release".equals(method)) { ClipboardImageExternalState.release(); return new Bundle(); }
        if ("releaseSelection".equals(method)) { ClipboardImageExternalState.releaseSelection(); return new Bundle(); }
        if ("resetSelection".equals(method)) { ClipboardImageExternalState.resetSelectionEntered(); return new Bundle(); }
        if ("armSelection".equals(method)) { ClipboardImageExternalState.armSelection(); return new Bundle(); }
        if ("select".equals(method)) { ClipboardImageExternalState.select(extras.getInt("start"), extras.getInt("end")); return new Bundle(); }
        if ("finish".equals(method)) { ClipboardImageExternalState.finishActivity(); return new Bundle(); }
        throw new IllegalArgumentException("Unknown method");
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
