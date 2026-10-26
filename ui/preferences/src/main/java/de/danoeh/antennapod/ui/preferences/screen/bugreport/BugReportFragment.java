package de.danoeh.antennapod.ui.preferences.screen.bugreport;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.EditText;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.os.Build;
import de.danoeh.antennapod.net.common.PlaybackTrace;
import de.danoeh.antennapod.ui.preferences.BuildConfig;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.common.AnimatedFragment;
import de.danoeh.antennapod.ui.common.ClipboardUtils;
import de.danoeh.antennapod.ui.common.IntentUtils;
import de.danoeh.antennapod.ui.preferences.R;
import de.danoeh.antennapod.ui.preferences.databinding.BugReportFragmentBinding;

/**
 * UI fragment to allow the user to submit a bug report via the AntennaPod forum or GitHub page.
 */
public class BugReportFragment extends AnimatedFragment {

    private BugReportFragmentBinding viewBinding;
    private BugReportViewModel viewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(BugReportViewModel.class);

        postponeEnterTransition();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = BugReportFragmentBinding.inflate(inflater, container, false);
        return viewBinding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewBinding = null;
    }

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupContextMenu();

        viewModel.getState().observe(getViewLifecycleOwner(), uiState -> {
            refreshEnvironmentInfo(uiState.getEnvironmentInfo());
            refreshCrashLogInfo(uiState);

            startPostponedEnterTransition();
        });

        viewBinding.expandCrashLogButton.setOnClickListener(v -> {
            switch (viewModel.requireCurrentState().getCrashLogState()) {
                case SHOWN_COLLAPSED:
                    viewModel.setCrashLogState(BugReportViewModel.UiState.CrashLogState.SHOWN_EXPANDED);
                    break;
                case SHOWN_EXPANDED:
                    viewModel.setCrashLogState(BugReportViewModel.UiState.CrashLogState.SHOWN_COLLAPSED);
                    break;
                default:    // UNAVAILABLE
                    break;
            }
        });
        viewBinding.openForumButton.setOnClickListener(v ->
                IntentUtils.openInBrowser(requireContext(), "https://forum.antennapod.org/search"));
        viewBinding.openGithubButton.setOnClickListener(v ->
                IntentUtils.openInBrowser(requireContext(), "https://github.com/AntennaPod/AntennaPod/issues"));
        viewBinding.attribAppVersionLabel.setOnClickListener(v ->
                ClipboardUtils.copyText((TextView) v, R.string.report_bug_attrib_app_version));
        viewBinding.attribAndroidVersionLabel.setOnClickListener(v ->
                ClipboardUtils.copyText((TextView) v, R.string.report_bug_attrib_android_version));
        viewBinding.attribDeviceNameLabel.setOnClickListener(v ->
                ClipboardUtils.copyText((TextView) v, R.string.report_bug_attrib_device_name));
        viewBinding.crashLogContentText.setOnClickListener(v ->
                ClipboardUtils.copyText(v, R.string.report_bug_title,
                        viewModel.requireCurrentState().getCrashInfoWithMarkup()));
        viewBinding.copyToClipboardButton.setOnClickListener(v ->
                ClipboardUtils.copyText(v, R.string.report_bug_title,
                        viewModel.requireCurrentState().getBugReportWithMarkup()));
    }

    @Override
    public void onStart() {
        super.onStart();

        Objects.requireNonNull(((AppCompatActivity) requireActivity()).getSupportActionBar())
                .setTitle(R.string.report_bug_title);
    }

    private void refreshEnvironmentInfo(@NonNull BugReportViewModel.EnvironmentInfo info) {
        viewBinding.attribAppVersionLabel.setText(info.applicationVersion);
        viewBinding.attribAndroidVersionLabel.setText(info.androidVersion);
        viewBinding.attribDeviceNameLabel.setText(info.getFriendlyDeviceName());
    }

    private void refreshCrashLogInfo(@NonNull BugReportViewModel.UiState uiState) {
        BugReportViewModel.UiState.CrashLogState state = uiState.getCrashLogState();
        BugReportViewModel.CrashLogInfo crashLogInfo = uiState.getCrashLogInfo();

        switch (state) {
            case SHOWN_COLLAPSED:
            case SHOWN_EXPANDED:
                viewBinding.crashLogToggleGroup.setVisibility(View.VISIBLE);
                viewBinding.crashLogContentText.setText(crashLogInfo.getContent());
                viewBinding.crashLogMessageLabel.setText(getString(
                        R.string.report_bug_crash_log_message, uiState.getFormattedCrashLogTimestamp()));

                if (state == BugReportViewModel.UiState.CrashLogState.SHOWN_COLLAPSED) {
                    viewBinding.expandCrashLogButton.setText(R.string.general_expand_button);
                    viewBinding.crashLogContentText.setMaxLines(4);
                } else {
                    viewBinding.expandCrashLogButton.setText(R.string.general_collapse_button);
                    viewBinding.crashLogContentText.setMaxLines(Integer.MAX_VALUE);
                }
                break;
            default:    // UNAVAILABLE
                viewBinding.crashLogToggleGroup.setVisibility(View.GONE);
                break;
        }
    }

    private void setupContextMenu() {
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.bug_report_options, menu);
                if (BuildConfig.DEBUG) {
                    menu.add(0, R.id.playback_trace_start, 0, R.string.playback_trace_start);
                    menu.add(0, R.id.playback_trace_stop, 1, R.string.playback_trace_stop);
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (BuildConfig.DEBUG && menuItem.getItemId() == R.id.playback_trace_start) {
                    showPlaybackTraceDialog();
                    return true;
                }
                if (BuildConfig.DEBUG && menuItem.getItemId() == R.id.playback_trace_stop) {
                    PlaybackTrace.stop();
                    return true;
                }
                if (menuItem.getItemId() == R.id.export_logcat) {
                    showExportLogcatDialog();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner());
    }

    private void showPlaybackTraceDialog() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) {
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.playback_trace_start)
                .setMessage(R.string.playback_trace_instructions)
                .setView(input)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    String target = input.getText().toString().trim();
                    Uri uri = Uri.parse(target);
                    if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                            && uri.getHost() != null) {
                        PlaybackTrace.start(target);
                        Snackbar.make(viewBinding.getRoot(), R.string.playback_trace_started,
                                Snackbar.LENGTH_LONG).show();
                    } else {
                        Snackbar.make(viewBinding.getRoot(), R.string.playback_trace_invalid,
                                Snackbar.LENGTH_LONG).show();
                    }
                    input.setText("");
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void showExportLogcatDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.export_logs_menu_title);
        builder.setMessage(R.string.confirm_export_log_dialog_message);
        builder.setPositiveButton(R.string.confirm_label, (dialog, which) -> exportLogcat());
        builder.setNegativeButton(R.string.cancel_label, null);
        builder.show();
    }

    private void exportLogcat() {
        try {
            File filename = new File(UserPreferences.getDataFolder(null), "full-logs.txt");
            try (FileOutputStream outputStream = new FileOutputStream(filename)) {
                outputStream.write((viewModel.requireCurrentState().getEnvironmentInfoWithMarkup() + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            String cmd = "logcat -d -f " + filename.getAbsolutePath();
            Runtime.getRuntime().exec(cmd);

            //share file
            try {
                String authority = getString(R.string.provider_authority);
                Uri fileUri = FileProvider.getUriForFile(requireContext(), authority, filename);

                new ShareCompat.IntentBuilder(requireContext())
                        .setType("text/*")
                        .addStream(fileUri)
                        .setChooserTitle(R.string.share_file_label)
                        .startChooser();

            } catch (Exception e) {
                e.printStackTrace();
                Snackbar.make(viewBinding.getRoot(), R.string.log_file_share_exception, Snackbar.LENGTH_LONG).show();
            }
        } catch (IOException e) {
            e.printStackTrace();

            Snackbar.make(viewBinding.getRoot(), e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }
}
