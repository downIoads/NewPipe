package org.schabi.newpipe.settings;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import org.schabi.newpipe.R;

import java.io.File;

public class TranslationSettingsFragment extends BasePreferenceFragment {

    private Preference modelPref;
    private Preference grantStoragePref;

    private final ActivityResultLauncher<Intent> pickModelLauncher =
            registerForActivityResult(new StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK
                        || result.getData() == null
                        || result.getData().getData() == null) {
                    return;
                }
                final Uri uri = result.getData().getData();
                Log.i(TAG, "picked URI=" + uri);

                final String resolved = resolveToRealPath(uri);
                if (resolved == null) {
                    Toast.makeText(requireContext(),
                            R.string.translation_path_unresolvable,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Log.i(TAG, "resolved real path=" + resolved);

                defaultPreferences.edit()
                        .putString(getString(R.string.translation_model_uri_key),
                                uri.toString())
                        .putString(getString(R.string.translation_model_path_key),
                                resolved)
                        .apply();
                updateSummaries();
            });

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        modelPref = findPreference(getString(R.string.translation_model_uri_key));
        grantStoragePref = findPreference("translation_grant_storage");
        updateSummaries();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSummaries();
    }

    @Override
    public boolean onPreferenceTreeClick(@NonNull final Preference preference) {
        if (preference == modelPref) {
            launchModelPicker();
            return true;
        }
        if (preference == grantStoragePref) {
            launchGrantStorage();
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    private void launchModelPicker() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            pickModelLauncher.launch(intent);
        } catch (final Exception e) {
            Log.e(TAG, "no SAF picker available", e);
        }
    }

    private void launchGrantStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            try {
                startActivity(intent);
            } catch (final Exception e) {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            Toast.makeText(requireContext(),
                    "Storage permission already available on this Android version.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void updateSummaries() {
        final String storedPath = defaultPreferences.getString(
                getString(R.string.translation_model_path_key), null);
        final String storedUri = defaultPreferences.getString(
                getString(R.string.translation_model_uri_key), null);
        if (storedPath != null && !storedPath.isEmpty()) {
            modelPref.setSummary(storedPath);
        } else if (storedUri != null && !storedUri.isEmpty()) {
            modelPref.setSummary(storedUri);
        } else {
            modelPref.setSummary(R.string.translation_model_uri_summary_unset);
        }

        if (grantStoragePref != null) {
            final boolean granted = isAllFilesAccessGranted();
            grantStoragePref.setSummary(granted
                    ? "Granted"
                    : getString(R.string.translation_grant_storage_summary));
            grantStoragePref.setEnabled(!granted);
        }
    }

    private static boolean isAllFilesAccessGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    @Nullable
    private static String resolveToRealPath(@NonNull final Uri uri) {
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                return uri.getPath();
            }
            if (!"content".equals(uri.getScheme())) {
                return null;
            }
            final String docId = DocumentsContract.getDocumentId(uri);
            if (docId == null || !docId.contains(":")) {
                return null;
            }
            final String[] split = docId.split(":", 2);
            final String type = split[0];
            final String relative = split[1];
            if ("primary".equalsIgnoreCase(type)) {
                final File root = Environment.getExternalStorageDirectory();
                return new File(root, relative).getAbsolutePath();
            }
            // Non-primary volumes (SD card) — try /storage/<volume>/...
            return "/storage/" + type + "/" + relative;
        } catch (final Throwable t) {
            Log.w("TranslationSettings", "resolveToRealPath failed for " + uri, t);
            return null;
        }
    }
}
