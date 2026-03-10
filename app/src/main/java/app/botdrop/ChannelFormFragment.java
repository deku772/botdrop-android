package app.botdrop;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.graphics.Paint;
import android.text.Html;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Base class for channel configuration pages (Telegram/Discord/Feishu/QQ Bot).
 */
public abstract class ChannelFormFragment extends Fragment {

    private static final String LOG_TAG = "ChannelFormFragment";
    private static final String QQBOT_PLUGIN_IDENTIFIER = "sliverp/qqbot";
    private static final String QQBOT_PLUGIN_IDENTIFIER_FALLBACK = "qqbot";
    private static final String QQBOT_PLUGIN_ENTRY_ID = "qqbot";
    private static final String QQBOT_PLUGIN_INSTALL_COMMAND = "openclaw plugins install @sliverp/qqbot@latest";
    private static final int QQBOT_PLUGIN_LIST_TIMEOUT_SECONDS = 120;
    private static final int QQBOT_PLUGIN_INSTALL_TIMEOUT_SECONDS = 300;
    private static final long CONNECT_PENDING_DIALOG_DELAY_MS = 600L;

    private ChannelConfigMeta mMeta;
    private TextView mOpenSetupBotButton;
    private TextView mTokenLabel;
    private EditText mTokenInput;
    private TextView mOwnerLabel;
    private EditText mOwnerInput;
    private View mOwnerRow;
    private TextView mFeishuUserIdLabel;
    private EditText mFeishuUserIdInput;
    private TextView mFeishuUserIdHelp;
    private View mFeishuUserIdRow;
    private View mDiscordGuildRow;
    private TextView mDiscordGuildLabel;
    private EditText mDiscordGuildInput;
    private Button mConnectButton;
    private Button mDeleteButton;
    private Button mSkipButton;
    private TextView mErrorMessage;
    private TextView mSetupHelpText;
    private AlertDialog mConnectProgressDialog;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private Runnable mShowConnectProgressRunnable;

    private BotDropService mService;
    private boolean mBound;
    private boolean mServiceBound;
    private boolean mHasExistingConfig;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mService = null;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mMeta = ChannelConfigMeta.forPlatform(getPlatformId());
    }

    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(getLayoutResId(), container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mOpenSetupBotButton = view.findViewById(R.id.channel_open_setup_bot);
        mOpenSetupBotButton.setPaintFlags(mOpenSetupBotButton.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        mTokenLabel = view.findViewById(R.id.channel_token_label);
        mTokenInput = view.findViewById(R.id.channel_token_input);
        mOwnerLabel = view.findViewById(R.id.channel_owner_label);
        mOwnerInput = view.findViewById(R.id.channel_owner_input);
        mOwnerRow = view.findViewById(R.id.channel_owner_row);
        mFeishuUserIdLabel = view.findViewById(R.id.channel_feishu_user_id_label);
        mFeishuUserIdInput = view.findViewById(R.id.channel_feishu_user_id_input);
        mFeishuUserIdHelp = view.findViewById(R.id.channel_feishu_user_id_help);
        mFeishuUserIdRow = view.findViewById(R.id.channel_feishu_user_id_row);
        mDiscordGuildRow = view.findViewById(R.id.channel_discord_guild_id_row);
        mDiscordGuildLabel = view.findViewById(R.id.channel_discord_guild_id_label);
        mDiscordGuildInput = view.findViewById(R.id.channel_discord_guild_id_input);
        mConnectButton = view.findViewById(R.id.channel_connect_button);
        mDeleteButton = view.findViewById(R.id.channel_delete_button);
        mSkipButton = view.findViewById(R.id.channel_skip_button);
        mErrorMessage = view.findViewById(R.id.channel_error_message);
        mSetupHelpText = view.findViewById(R.id.channel_setup_help_text);

        if (mMeta != null) {
            if (mTokenLabel != null) {
                mTokenLabel.setText(
                    mMeta.tokenLabelRes != 0 ? mMeta.tokenLabelRes : R.string.botdrop_bot_token
                );
            }
            if (mTokenInput != null) {
                mTokenInput.setHint(
                    mMeta.tokenHintRes != 0 ? mMeta.tokenHintRes : R.string.botdrop_bot_token_hint
                );
            }
            if (mOwnerLabel != null) {
                mOwnerLabel.setText(
                    mMeta.ownerLabelRes != 0 ? mMeta.ownerLabelRes : R.string.botdrop_owner_id
                );
            }
            if (mOwnerInput != null) {
                mOwnerInput.setHint(
                    mMeta.ownerHintRes != 0 ? mMeta.ownerHintRes : R.string.botdrop_owner_id_hint
                );
            }
            if (mOwnerRow != null) {
                mOwnerRow.setVisibility(mMeta.showOwnerField ? View.VISIBLE : View.GONE);
            }
            if (mFeishuUserIdRow != null) {
                mFeishuUserIdRow.setVisibility(
                    ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform) ? View.VISIBLE : View.GONE
                );
            }
            if (mFeishuUserIdLabel != null) {
                mFeishuUserIdLabel.setText(R.string.botdrop_feishu_user_id_next_step);
            }
            if (mFeishuUserIdInput != null) {
                mFeishuUserIdInput.setHint(R.string.botdrop_feishu_user_id_hint);
            }
            if (mFeishuUserIdHelp != null) {
                mFeishuUserIdHelp.setText(
                    Html.fromHtml(
                        getString(R.string.botdrop_feishu_setup_steps),
                        Html.FROM_HTML_MODE_COMPACT
                    )
                );
            }
            if (mDiscordGuildRow != null) {
                mDiscordGuildRow.setVisibility(
                    ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform) ? View.VISIBLE : View.GONE
                );
            }
            if (mDiscordGuildLabel != null) {
                mDiscordGuildLabel.setText(R.string.botdrop_guild_id);
            }
            if (mDiscordGuildInput != null) {
                mDiscordGuildInput.setHint(R.string.botdrop_guild_id);
            }
            if (mSetupHelpText != null && mMeta.setupHelpTextRes != 0) {
                mSetupHelpText.setText(
                    Html.fromHtml(getString(mMeta.setupHelpTextRes), Html.FROM_HTML_MODE_COMPACT)
                );
                mSetupHelpText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            }
        }

        mOpenSetupBotButton.setOnClickListener(v -> openSetupBot());
        mConnectButton.setOnClickListener(v -> connect());
        preloadExistingConfig();
        configureActionButtons();

        Logger.logDebug(LOG_TAG, "ChannelFormFragment view created for " + (mMeta == null ? "unknown" : mMeta.platform));
    }

    @Override
    public void onStart() {
        super.onStart();
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, BotDropService.class);
        boolean bound = activity.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        if (bound) {
            mServiceBound = true;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mServiceBound) {
            Activity activity = getActivity();
            if (activity != null) {
                try {
                    activity.unbindService(mConnection);
                } catch (IllegalArgumentException e) {
                    Logger.logDebug(LOG_TAG, "Service was already unbound");
                }
            }
            mServiceBound = false;
            mBound = false;
            mService = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        hideConnectPendingProgress();
        if (mShowConnectProgressRunnable != null) {
            mUiHandler.removeCallbacks(mShowConnectProgressRunnable);
            mShowConnectProgressRunnable = null;
        }
        if (mConnectProgressDialog != null) {
            if (mConnectProgressDialog.isShowing()) {
                mConnectProgressDialog.dismiss();
            }
            mConnectProgressDialog = null;
        }
    }

    private void openSetupBot() {
        if (mMeta == null || TextUtils.isEmpty(mMeta.setupBotUrl)) {
            return;
        }
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(mMeta.setupBotUrl));
        startActivity(browserIntent);
    }

    private void connect() {
        if (mMeta == null) {
            return;
        }

        mErrorMessage.setVisibility(View.GONE);

        String token = mTokenInput.getText().toString().trim();
        String ownerId = mOwnerInput != null ? mOwnerInput.getText().toString().trim() : "";
        String feishuUserId = mFeishuUserIdInput != null ? mFeishuUserIdInput.getText().toString().trim() : "";
        String guildId = mDiscordGuildInput != null ? mDiscordGuildInput.getText().toString().trim() : "";

        if (!mMeta.isTokenValid(token)) {
            if (ChannelConfigMeta.PLATFORM_TELEGRAM.equals(mMeta.platform)) {
                showError(getString(R.string.botdrop_error_enter_valid_bot_token));
            } else if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
                showError(getString(R.string.botdrop_error_enter_app_id));
            } else if (ChannelConfigMeta.PLATFORM_QQBOT.equals(mMeta.platform)) {
                showError(getString(R.string.botdrop_error_enter_qqbot_app_id));
            } else {
                showError(getString(R.string.botdrop_error_enter_token));
            }
            return;
        }

        if (!mMeta.isOwnerValid(ownerId)) {
            if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
                showError(getString(R.string.botdrop_error_enter_app_secret));
            } else if (ChannelConfigMeta.PLATFORM_QQBOT.equals(mMeta.platform)) {
                showError(getString(R.string.botdrop_error_enter_qqbot_app_secret));
            } else {
                showError(getString(R.string.botdrop_error_enter_owner_id));
            }
            return;
        }

        if (ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform)) {
            if (!mMeta.isDiscordGuildIdValid(guildId)) {
                showError(getString(R.string.botdrop_error_enter_guild_id));
                return;
            }
        }

        mConnectButton.setEnabled(false);
        mConnectButton.setText(R.string.botdrop_connecting);
        showConnectPendingProgressWithDelay();

        if (ChannelConfigMeta.PLATFORM_QQBOT.equals(mMeta.platform)) {
            ensureQqBotPluginInstalledBeforeConfigWrite(token, ownerId, feishuUserId, guildId);
            return;
        }

        writeConfigAndStartGateway(token, ownerId, feishuUserId, guildId);
    }

    private void writeConfigAndStartGateway(
        String token,
        String ownerId,
        String feishuUserId,
        String guildId
    ) {
        boolean success;
        if (ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform)) {
            success = ChannelSetupHelper.writeChannelConfig(
                mMeta.platform,
                token,
                ownerId,
                guildId,
                null
            );
        } else if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
            success = ChannelSetupHelper.writeFeishuChannelConfig(
                token,
                ownerId,
                feishuUserId
            );
        } else if (ChannelConfigMeta.PLATFORM_QQBOT.equals(mMeta.platform)) {
            success = ChannelSetupHelper.writeQQBotChannelConfig(token, ownerId);
        } else {
            success = ChannelSetupHelper.writeChannelConfig(
                mMeta.platform,
                token,
                ownerId
            );
        }
        if (!success) {
            showError(getString(R.string.botdrop_error_write_config));
            resetButton();
            return;
        }

        if (ChannelConfigMeta.PLATFORM_TELEGRAM.equals(mMeta.platform)) {
            try {
                Context ctx = getContext();
                if (ctx != null) {
                    ConfigTemplate template = ConfigTemplateCache.loadTemplate(ctx);
                    if (template == null) {
                        template = new ConfigTemplate();
                    }
                    template.tgBotToken = token;
                    template.tgUserId = ownerId;
                    ConfigTemplateCache.saveTemplate(ctx, template);
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to save template: " + e.getMessage());
            }
        }
        startGateway();
    }

    private void showConnectPendingProgressWithDelay() {
        hideConnectPendingProgress();
        mShowConnectProgressRunnable = () -> {
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                return;
            }
            showConnectProgressDialog();
        };
        mUiHandler.postDelayed(mShowConnectProgressRunnable, CONNECT_PENDING_DIALOG_DELAY_MS);
    }

    private void hideConnectPendingProgress() {
        if (mShowConnectProgressRunnable != null) {
            mUiHandler.removeCallbacks(mShowConnectProgressRunnable);
            mShowConnectProgressRunnable = null;
        }
        if (mConnectProgressDialog != null) {
            if (mConnectProgressDialog.isShowing()) {
                mConnectProgressDialog.dismiss();
            }
            mConnectProgressDialog = null;
        }
    }

    private void showConnectProgressDialog() {
        if (mConnectProgressDialog != null && mConnectProgressDialog.isShowing()) {
            return;
        }
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }

        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.HORIZONTAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);

        ProgressBar progressBar = new ProgressBar(activity);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        progressParams.rightMargin = (int) (16 * getResources().getDisplayMetrics().density);
        content.addView(progressBar, progressParams);

        TextView statusText = new TextView(activity);
        statusText.setText(R.string.botdrop_connecting);
        statusText.setTextSize(16f);
        content.addView(statusText);

        mConnectProgressDialog = new AlertDialog.Builder(activity)
            .setCancelable(false)
            .setView(content)
            .create();
        mConnectProgressDialog.setCanceledOnTouchOutside(false);
        mConnectProgressDialog.show();
    }

    private void ensureQqBotPluginInstalledBeforeConfigWrite(
        String token,
        String ownerId,
        String feishuUserId,
        String guildId
    ) {
        if (!mBound || mService == null) {
            showError(getString(R.string.botdrop_service_not_ready));
            hideConnectPendingProgress();
            resetButton();
            return;
        }

        if (isQqBotPluginConfiguredInLocalConfig()) {
            hideConnectPendingProgress();
            Logger.logInfo(LOG_TAG, "QQ Bot plugin entry already exists in local config");
            writeConfigAndStartGateway(token, ownerId, feishuUserId, guildId);
            return;
        }

        mService.executeCommand("openclaw plugins list", QQBOT_PLUGIN_LIST_TIMEOUT_SECONDS, listResult -> {
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                hideConnectPendingProgress();
                return;
            }

            if (isQqBotPluginInstalled(listResult) || isQqBotPluginConfiguredInLocalConfig()) {
                hideConnectPendingProgress();
                Logger.logInfo(LOG_TAG, "QQ Bot plugin already installed, start gateway directly");
                writeConfigAndStartGateway(token, ownerId, feishuUserId, guildId);
                return;
            }

            mService.executeCommand(QQBOT_PLUGIN_INSTALL_COMMAND, QQBOT_PLUGIN_INSTALL_TIMEOUT_SECONDS, installResult -> {
                if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                    hideConnectPendingProgress();
                    return;
                }

                if (installResult.success || isQqBotPluginInstallNoop(installResult)) {
                    hideConnectPendingProgress();
                    Logger.logInfo(LOG_TAG, "QQ Bot plugin installation completed");
                    writeConfigAndStartGateway(token, ownerId, feishuUserId, guildId);
                    return;
                }

                Logger.logError(LOG_TAG, "Failed to install QQ Bot plugin: " + installResult.stderr);
                String errorMsg = installResult.stderr;
                if (TextUtils.isEmpty(errorMsg)) {
                    errorMsg = installResult.stdout;
                }
                if (TextUtils.isEmpty(errorMsg)) {
                    errorMsg = getString(R.string.botdrop_unknown_error_exit_code, installResult.exitCode);
                }
                hideConnectPendingProgress();
                showError(getString(R.string.botdrop_install_failed, errorMsg));
                resetButton();
            });
        });
    }

    private boolean isQqBotPluginInstalled(BotDropService.CommandResult result) {
        if (result == null) {
            return false;
        }

        return isQqBotPluginInstalled(result.stdout)
            || isQqBotPluginInstalled(result.stderr);
    }

    private boolean isQqBotPluginConfiguredInLocalConfig() {
        try {
            JSONObject config = BotDropConfig.readConfig();
            JSONObject plugins = config.optJSONObject("plugins");
            if (plugins == null) {
                return false;
            }
            JSONObject entries = plugins.optJSONObject("entries");
            if (entries == null) {
                return false;
            }

            Object pluginEntry = entries.opt(QQBOT_PLUGIN_ENTRY_ID);
            if (pluginEntry instanceof JSONObject) {
                JSONObject pluginObj = (JSONObject) pluginEntry;
                return pluginObj.optBoolean("enabled", true);
            }
            if (pluginEntry instanceof Boolean) {
                return (Boolean) pluginEntry;
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to read QQ plugin entry from local config: " + e.getMessage());
        }
        return false;
    }

    private boolean isQqBotPluginInstalled(String output) {
        if (TextUtils.isEmpty(output)) {
            return false;
        }

        String lower = output.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(QQBOT_PLUGIN_IDENTIFIER)
            || lower.contains("@" + QQBOT_PLUGIN_IDENTIFIER)
            || lower.contains(QQBOT_PLUGIN_IDENTIFIER + "@")
            || lower.contains(QQBOT_PLUGIN_IDENTIFIER_FALLBACK);
    }

    private boolean isQqBotPluginInstallNoop(BotDropService.CommandResult result) {
        if (result == null) {
            return false;
        }

        return isQqBotPluginInstallNoop(result.stdout)
            || isQqBotPluginInstallNoop(result.stderr);
    }

    private boolean isQqBotPluginInstallNoop(String output) {
        if (TextUtils.isEmpty(output)) {
            return false;
        }

        String lower = output.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("already installed")
            || lower.contains("already up to date")
            || lower.contains("nothing to install")
            || lower.contains("already exists")
            || lower.contains("skipping")
            || lower.contains("already have")
            || lower.contains(QQBOT_PLUGIN_IDENTIFIER);
    }

    private void preloadExistingConfig() {
        mHasExistingConfig = false;
        try {
            JSONObject config = BotDropConfig.readConfig();
            JSONObject channels = config != null ? config.optJSONObject("channels") : null;
            if (channels == null || mMeta == null) {
                return;
            }

            JSONObject channelConfig = channels.optJSONObject(mMeta.platform);
            if (channelConfig == null) {
                return;
            }

            String token;
            String owner;
            String feishuUserId = null;
            if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
                token = extractFeishuAppIdFromChannelConfig(channelConfig);
                owner = extractFeishuAppSecretFromChannelConfig(channelConfig);
                feishuUserId = extractFeishuUserIdFromChannelConfig(channelConfig);
            } else if (ChannelConfigMeta.PLATFORM_QQBOT.equals(mMeta.platform)) {
                token = extractQQBotAppIdFromChannelConfig(channelConfig);
                owner = extractQQBotClientSecretFromChannelConfig(channelConfig);
            } else {
                token = channelConfig.optString("botToken", null);
                if (TextUtils.isEmpty(token)) {
                    token = channelConfig.optString("token", null);
                }
                owner = extractOwnerFromChannelConfig(channelConfig);
            }
            String guildId = null;
            String channelId = null;
            JSONObject guilds = channelConfig.optJSONObject("guilds");
            if (guilds != null && guilds.length() > 0) {
                Iterator<String> guildIterator = guilds.keys();
                while (guildIterator.hasNext()) {
                    String guild = guildIterator.next();
                    if (TextUtils.isEmpty(guild)) {
                        continue;
                    }
                    guildId = guild;
                    break;
                }
            }
            if (ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform) && TextUtils.isEmpty(guildId)) {
                return;
            }


            if (!TextUtils.isEmpty(token)) {
                mHasExistingConfig = true;
                mTokenInput.setText(token.trim());
            }
            if (mOwnerInput != null && !TextUtils.isEmpty(owner)) {
                mOwnerInput.setText(owner.trim());
            }
            if (mFeishuUserIdInput != null && !TextUtils.isEmpty(feishuUserId)) {
                mFeishuUserIdInput.setText(feishuUserId.trim());
            }
            if (mDiscordGuildInput != null && !TextUtils.isEmpty(guildId)) {
                mDiscordGuildInput.setText(guildId.trim());
            }
            if (ChannelConfigMeta.PLATFORM_DISCORD.equals(mMeta.platform)) {
                mHasExistingConfig = !TextUtils.isEmpty(token) && !TextUtils.isEmpty(guildId);
            } else if (ChannelConfigMeta.PLATFORM_FEISHU.equals(mMeta.platform)) {
                mHasExistingConfig = !TextUtils.isEmpty(token)
                    && !TextUtils.isEmpty(owner);
                String dmPolicy = channelConfig.optString("dmPolicy", "").trim();
                if ("allowlist".equals(dmPolicy) && TextUtils.isEmpty(feishuUserId)) {
                    mHasExistingConfig = false;
                }
            } else if (ChannelConfigMeta.PLATFORM_QQBOT.equals(mMeta.platform)) {
                mHasExistingConfig = !TextUtils.isEmpty(token) && !TextUtils.isEmpty(owner);
            }

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to preload channel config: " + e.getMessage());
        }
    }

    private String extractOwnerFromChannelConfig(JSONObject channelConfig) {
        Object owner = channelConfig.opt("ownerId");
        if (owner != null) {
            return String.valueOf(owner);
        }

        Object ownerFromAllowFrom = channelConfig.opt("allowFrom");
        if (ownerFromAllowFrom instanceof String) {
            return (String) ownerFromAllowFrom;
        }
        if (ownerFromAllowFrom instanceof JSONArray) {
            JSONArray ids = (JSONArray) ownerFromAllowFrom;
            if (ids.length() > 0) {
                Object first = ids.opt(0);
                return first != null ? String.valueOf(first) : null;
            }
        }
        return "";
    }

    private String extractFeishuAppIdFromChannelConfig(JSONObject channelConfig) {
        if (channelConfig == null) {
            return "";
        }

        JSONObject accounts = channelConfig.optJSONObject("accounts");
        JSONObject mainAccount = accounts != null ? accounts.optJSONObject("main") : null;
        if (mainAccount == null) {
            return "";
        }

        Object appId = mainAccount.opt("appId");
        return appId != null ? String.valueOf(appId) : "";
    }

    private String extractFeishuAppSecretFromChannelConfig(JSONObject channelConfig) {
        if (channelConfig == null) {
            return "";
        }

        JSONObject accounts = channelConfig.optJSONObject("accounts");
        JSONObject mainAccount = accounts != null ? accounts.optJSONObject("main") : null;
        if (mainAccount == null) {
            return "";
        }

        Object appSecret = mainAccount.opt("appSecret");
        return appSecret != null ? String.valueOf(appSecret) : "";
    }

    private String extractFeishuUserIdFromChannelConfig(JSONObject channelConfig) {
        if (channelConfig == null) {
            return "";
        }

        Object allowFrom = channelConfig.opt("allowFrom");
        if (allowFrom instanceof String) {
            return (String) allowFrom;
        }
        if (allowFrom instanceof JSONArray) {
            JSONArray ids = (JSONArray) allowFrom;
            if (ids.length() > 0) {
                Object first = ids.opt(0);
                return first != null ? String.valueOf(first) : "";
            }
        }
        return "";
    }

    private String extractQQBotAppIdFromChannelConfig(JSONObject channelConfig) {
        if (channelConfig == null) {
            return "";
        }
        Object appId = channelConfig.opt("appId");
        return appId != null ? String.valueOf(appId) : "";
    }

    private String extractQQBotClientSecretFromChannelConfig(JSONObject channelConfig) {
        if (channelConfig == null) {
            return "";
        }
        Object clientSecret = channelConfig.opt("clientSecret");
        return clientSecret != null ? String.valueOf(clientSecret) : "";
    }

    private void configureActionButtons() {
        configureSkipAction();
        configureDeleteAction();
    }

    private void configureSkipAction() {
        if (mSkipButton == null) {
            return;
        }

        if (mHasExistingConfig) {
            mSkipButton.setText(R.string.botdrop_cancel);
            mSkipButton.setOnClickListener(v -> finishChannelSetup());
        } else {
            mSkipButton.setOnClickListener(v -> skipSetup());
        }
    }

    private void configureDeleteAction() {
        if (mDeleteButton == null) {
            return;
        }

        if (!mHasExistingConfig) {
            mDeleteButton.setVisibility(View.GONE);
            mDeleteButton.setOnClickListener(null);
            return;
        }

        mDeleteButton.setVisibility(View.VISIBLE);
        mDeleteButton.setText(R.string.botdrop_delete_channel_config);
        mDeleteButton.setOnClickListener(v -> confirmDeleteConfig());
    }

    private void confirmDeleteConfig() {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }

        if (mMeta == null) {
            return;
        }

        Context ctx = getContext();
        if (ctx == null) {
            return;
        }

        String platformLabel = getString(mMeta.titleRes);
        new AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.botdrop_delete_channel_title, platformLabel))
            .setMessage(getString(R.string.botdrop_delete_channel_message, platformLabel))
            .setPositiveButton(R.string.botdrop_delete_channel_config, (dialog, which) -> deleteChannelConfig())
            .setNegativeButton(R.string.botdrop_cancel, (dialog, which) -> dialog.dismiss())
            .show();
    }

    private void deleteChannelConfig() {
        if (mMeta == null || !isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }

        if (!ChannelSetupHelper.removeChannelConfig(mMeta.platform)) {
            showError(getString(R.string.botdrop_delete_channel_failed));
            return;
        }

        mHasExistingConfig = false;
        clearConfigInputs();
        mErrorMessage.setVisibility(View.GONE);

        Context ctx = getContext();
        if (ctx != null) {
            Toast.makeText(ctx, R.string.botdrop_channel_config_deleted, Toast.LENGTH_SHORT).show();
        }

        restartGatewayAndExit();
    }

    private void restartGatewayAndExit() {
        if (!mBound || mService == null) {
            showError(getString(R.string.botdrop_service_not_ready));
            finishChannelSetup();
            return;
        }

        Context ctx = getContext();
        if (ctx != null) {
            Toast.makeText(ctx, R.string.botdrop_gateway_restarting, Toast.LENGTH_SHORT).show();
        }

        mService.restartGateway(result -> {
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                return;
            }
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }

            activity.runOnUiThread(() -> {
                if (!result.success) {
                    String errorMsg = result.stderr;
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = result.stdout;
                    }
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = getString(
                            R.string.botdrop_unknown_error_exit_code,
                            result.exitCode
                        );
                    }
                    showError(getString(R.string.botdrop_error_start_gateway, errorMsg));
                }
                finishChannelSetup();
            });
        });
    }

    private void clearConfigInputs() {
        if (mTokenInput != null) {
            mTokenInput.setText("");
        }
        if (mOwnerInput != null) {
            mOwnerInput.setText("");
        }
        if (mFeishuUserIdInput != null) {
            mFeishuUserIdInput.setText("");
        }
        if (mDiscordGuildInput != null) {
            mDiscordGuildInput.setText("");
        }
    }

    private void startGateway() {
        if (!mBound || mService == null) {
            showError(getString(R.string.botdrop_service_not_ready));
            hideConnectPendingProgress();
            resetButton();
            return;
        }

        Logger.logInfo(LOG_TAG, "Starting gateway...");
        mService.startGateway(result -> {
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                return;
            }

            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            activity.runOnUiThread(() -> {
                hideConnectPendingProgress();
                if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                    return;
                }

                if (result.success) {
                Logger.logInfo(LOG_TAG, "Gateway started successfully");
                Context ctx = getContext();
                if (ctx != null) {
                    Toast.makeText(
                        ctx,
                        R.string.botdrop_connected_gateway_starting,
                        Toast.LENGTH_LONG
                    ).show();
                }

                    SetupActivity setupActivity = (SetupActivity) getActivity();
                    if (setupActivity != null && !setupActivity.isFinishing()) {
                        setupActivity.goToNextStep();
                    }
                } else {
                    Logger.logError(LOG_TAG, "Failed to start gateway: " + result.stderr);
                    String errorMsg = result.stderr;
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = result.stdout;
                    }
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = getString(R.string.botdrop_unknown_error_exit_code, result.exitCode);
                    }
                    showError(getString(R.string.botdrop_error_start_gateway, errorMsg));
                    resetButton();
                }
            });
        });
    }

    private void skipSetup() {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }
        String platformLabel = mMeta == null ? getString(R.string.botdrop_this_channel) : getString(mMeta.titleRes);
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        new AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.botdrop_skip_channel_setup_title, platformLabel))
            .setMessage(getString(R.string.botdrop_skip_channel_setup_message, platformLabel))
            .setPositiveButton(R.string.botdrop_skip, (dialog, which) -> {
                Logger.logInfo(LOG_TAG, "User skipped channel setup");
                SetupActivity activity = (SetupActivity) getActivity();
                if (activity == null || activity.isFinishing()) {
                    return;
                }
                activity.goToNextStep();
            })
            .setNegativeButton(R.string.botdrop_cancel, (dialog, which) -> dialog.dismiss())
            .show();
    }

    private void finishChannelSetup() {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }
        getActivity().finish();
    }

    private void showError(String message) {
        mErrorMessage.setText(message);
        mErrorMessage.setVisibility(View.VISIBLE);
    }

    private void resetButton() {
        hideConnectPendingProgress();
        mConnectButton.setEnabled(true);
        mConnectButton.setText(R.string.botdrop_connect_start);
    }

    protected abstract String getPlatformId();
    protected abstract int getLayoutResId();
}
