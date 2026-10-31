/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.widget.EditText;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.view.WindowManager;

/** Test APK activity, deliberately in a different UID from the input method. */
public final class ClipboardImageExternalEditorActivity extends Activity {
    private EditText editor;
    private int showAttempts;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ClipboardImageExternalState.activity = this;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        editor = new EditText(this) {
            @Override public InputConnection onCreateInputConnection(EditorInfo info) {
                InputConnection base = super.onCreateInputConnection(info);
                if (Build.VERSION.SDK_INT >= 25) {
                    info.contentMimeTypes = ClipboardImageExternalState.mimeTypes();
                }
                return new InputConnectionWrapper(base, false) {
                    @Override public CharSequence getSelectedText(int flags) {
                        ClipboardImageExternalState.awaitSelection();
                        return super.getSelectedText(flags);
                    }
                    @Override public boolean commitContent(InputContentInfo content, int flags, Bundle options) {
                        return ClipboardImageExternalState.receive(ClipboardImageExternalEditorActivity.this, content, flags);
                    }
                };
            }
        };
        ClipboardImageExternalState.editor = editor;
        editor.setText(ClipboardImageExternalState.editorText());
        setContentView(editor);
        editor.requestFocus();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && editor != null) {
            showAttempts = 0;
            editor.post(this::showWhenServed);
        }
    }
    private void showWhenServed() {
        if (isFinishing() || !hasWindowFocus() || editor == null) return;
        InputMethodManager manager = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager.isActive(editor)) manager.showSoftInput(editor, 0);
        else if (++showAttempts < 30) editor.postDelayed(this::showWhenServed, 100);
    }
    @Override protected void onDestroy() {
        if (ClipboardImageExternalState.activity == this) ClipboardImageExternalState.activity = null;
        if (ClipboardImageExternalState.editor == editor) ClipboardImageExternalState.editor = null;
        super.onDestroy();
    }
}
