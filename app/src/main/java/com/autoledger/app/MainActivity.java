package com.autoledger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.autoledger.app.capture.CategoryCatalog;
import com.autoledger.app.capture.CsvBillImporter;
import com.autoledger.app.capture.SourceKey;
import com.autoledger.app.data.DebugLog;
import com.autoledger.app.data.LedgerEntry;
import com.autoledger.app.data.LedgerRepository;
import com.autoledger.app.data.RawCaptureRecord;
import com.autoledger.app.service.AccessibilityCaptureService;
import com.autoledger.app.service.BackgroundTaskHider;
import com.autoledger.app.service.KeepAliveService;
import com.autoledger.app.service.NotificationCaptureService;
import com.autoledger.app.service.ServiceGuard;
import com.autoledger.app.service.ShizukuSupport;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    public static final String EXTRA_OPEN_MANUAL_FROM_CAPTURE =
            "com.autoledger.app.extra.OPEN_MANUAL_FROM_CAPTURE";
    private int COLOR_BACKGROUND = Color.rgb(12, 14, 15);
    private int COLOR_PRIMARY = Color.rgb(213, 247, 214);
    private int COLOR_PRIMARY_DARK = Color.rgb(183, 231, 188);
    private int COLOR_ACCENT = Color.rgb(196, 164, 255);
    private int COLOR_ACCENT_DEEP = Color.rgb(101, 67, 168);
    private int COLOR_INK = Color.rgb(243, 245, 243);
    private int COLOR_MUTED = Color.rgb(171, 177, 173);
    private int COLOR_CARD = Color.rgb(29, 31, 31);
    private int COLOR_SURFACE_VARIANT = Color.rgb(39, 42, 41);
    private int COLOR_BORDER = Color.rgb(68, 73, 70);
    private int COLOR_PRIMARY_CONTAINER = Color.rgb(213, 247, 214);
    private int COLOR_ON_PRIMARY_CONTAINER = Color.rgb(18, 54, 25);
    private int COLOR_SECONDARY_CONTAINER = Color.rgb(54, 59, 56);
    private int COLOR_ON_SECONDARY_CONTAINER = Color.rgb(237, 241, 238);
    private int COLOR_EXPENSE = Color.rgb(217, 137, 137);
    private int COLOR_INCOME = Color.rgb(126, 180, 139);
    private int COLOR_ACTION_IMPORT = Color.rgb(54, 59, 56);
    private int COLOR_ACTION_SETTINGS = Color.rgb(54, 59, 56);
    private int COLOR_ACTION_REFRESH = Color.rgb(45, 49, 47);
    private static final int SHIZUKU_PERMISSION_REQUEST = 6201;
    private static final int BILL_IMPORT_REQUEST = 4201;

    private static final String[] MANUAL_CATEGORY_KEYS = {
            CategoryCatalog.FOOD,
            CategoryCatalog.SHOPPING,
            CategoryCatalog.TRANSPORT,
            CategoryCatalog.SUBSCRIPTION,
            CategoryCatalog.ENTERTAINMENT,
            CategoryCatalog.HOUSING,
            CategoryCatalog.MEDICAL,
            CategoryCatalog.EDUCATION,
            CategoryCatalog.COMMUNICATION,
            CategoryCatalog.SALARY,
            CategoryCatalog.REFUND,
            CategoryCatalog.OTHER
    };

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
    private final Calendar selectedMonth = Calendar.getInstance();
    private Calendar selectedDay;
    private LedgerRepository repository;
    private FrameLayout rootView;
    private ScrollView scrollView;
    private LinearLayout content;
    private LinearLayout bottomNav;
    private LinearLayout floatingActions;
    private LinearLayout refreshIndicator;
    private ProgressBar refreshSpinner;
    private TextView refreshLabel;
    private int currentTab;
    private int lastScrollY;
    private boolean floatingActionsVisible = true;
    private int lastNavTab = -1;
    private int slideDirection = 1;
    private boolean animateTabTransition;
    private float pullStartY;
    private float pullProgress;
    private boolean pullTriggered;
    private boolean refreshing;
    private int externalLaunches;
    private boolean calendarDialogOpen;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, result) -> {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST) {
                    return;
                }
                externalLaunches = 0;
                boolean granted = result == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (granted) {
                    boolean writeGranted = ShizukuSupport.grantWriteSecureSettings(this);
                    boolean guardFixed = ServiceGuard.run(this);
                    Toast.makeText(
                            this,
                            (writeGranted && guardFixed)
                                    ? "Shizuku 权限已恢复"
                                    : "Shizuku 已授权，但系统仍需一次手动开启",
                            Toast.LENGTH_SHORT
                    ).show();
                    render();
                } else {
                    Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyMonetPalette();
        repository = LedgerRepository.get(this);
        Calendar now = Calendar.getInstance();
        selectedMonth.set(
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                1
        );
        ShizukuSupport.addPermissionResultListener(shizukuPermissionListener);

        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(COLOR_BACKGROUND);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                dp(18),
                statusBarHeight() + dp(12),
                dp(18),
                dp(108)
        );
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        rootView.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        refreshIndicator = createRefreshIndicator();
        FrameLayout.LayoutParams refreshParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        refreshParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        refreshParams.topMargin = statusBarHeight() + dp(2);
        refreshIndicator.setAlpha(0f);
        refreshIndicator.setTranslationY(-dp(12));
        refreshIndicator.setVisibility(View.GONE);
        rootView.addView(refreshIndicator, refreshParams);

        bottomNav = createBottomNav();
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.68f),
                dp(70)
        );
        navParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        navParams.bottomMargin = dp(10);
        rootView.addView(bottomNav, navParams);

        floatingActions = createFloatingActions();
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionParams.gravity = Gravity.BOTTOM | Gravity.END;
        actionParams.rightMargin = dp(20);
        actionParams.bottomMargin = dp(94);
        rootView.addView(floatingActions, actionParams);

        configureScrollBehavior();
        setContentView(rootView);

        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(COLOR_BACKGROUND);
            window.setNavigationBarColor(COLOR_BACKGROUND);
        }
        render();
        handleCaptureFallbackIntent(getIntent());
        if (repository.isKeepAliveEnabled()) {
            KeepAliveService.start(this);
            AccessibilityCaptureService.refreshKeepAliveOverlay(this);
        }
        BackgroundTaskHider.apply(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        externalLaunches = 0;
        render();
        if (repository.isKeepAliveEnabled()) {
            KeepAliveService.start(this);
            AccessibilityCaptureService.refreshKeepAliveOverlay(this);
        }
        BackgroundTaskHider.apply(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCaptureFallbackIntent(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        boolean shouldHide = repository.isHideFromRecentsEnabled()
                && externalLaunches == 0
                && !calendarDialogOpen
                && !isChangingConfigurations()
                && !isFinishing();
        if (shouldHide) {
            DebugLog.append(this, "background task removed by finish");
            finishAndRemoveTask();
        }
    }

    @Override
    protected void onDestroy() {
        ShizukuSupport.removePermissionResultListener(shizukuPermissionListener);
        super.onDestroy();
    }

    private void handleCaptureFallbackIntent(Intent intent) {
        if (intent == null
                || !intent.getBooleanExtra(EXTRA_OPEN_MANUAL_FROM_CAPTURE, false)) {
            return;
        }
        intent.removeExtra(EXTRA_OPEN_MANUAL_FROM_CAPTURE);
        showManualDialog();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BILL_IMPORT_REQUEST
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            importBillCsv(data.getData());
        }
    }

    private void render() {
        content.animate().cancel();
        if (content.getChildCount() == 0) {
            rebuildCurrentPage();
            return;
        }
        boolean slide = animateTabTransition;
        float exitX = slide
                ? -slideDirection * content.getWidth() * 0.18f
                : 0f;
        float exitY = slide ? 0f : -dp(5);
        content.animate()
                .alpha(0f)
                .translationX(exitX)
                .translationY(exitY)
                .setDuration(slide ? 145L : 105L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::rebuildCurrentPage)
                .start();
    }

    private void rebuildCurrentPage() {
        boolean slide = animateTabTransition;
        float enterX = slide
                ? slideDirection * content.getWidth() * 0.18f
                : 0f;
        float enterY = slide ? 0f : dp(14);
        content.removeAllViews();
        content.setAlpha(0f);
        content.setTranslationX(enterX);
        content.setTranslationY(enterY);
        if (currentTab == 1) {
            renderSettingsPage();
        } else if (currentTab == 2) {
            renderAboutPage();
        } else {
            renderHome();
        }
        content.animate()
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(slide ? 300L : 260L)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
        animateTabTransition = false;
        updateBottomNav();
        floatingActions.setVisibility(currentTab == 0 ? View.VISIBLE : View.GONE);
    }

    private void renderHome() {
        content.addView(appHeader());
        content.addView(summaryPanel(), cardParams());
        if (renderPending()) {
            // Pending section was rendered above recent transactions.
        }
        content.addView(sectionTitle(selectedPeriodLabel() + "流水"));
        renderTransactions();
    }

    private void renderSettingsPage() {
        content.addView(sectionTitle("设置"));

        LinearLayout permissionCard = flowCard();
        permissionCard.addView(text("权限", 20, true, COLOR_INK));
        permissionCard.addView(bodyText(
                "通知读取和无障碍只读取系统暴露的支付文本，不会点击、付款或读取密码。",
                13,
                false,
                COLOR_MUTED
        ));
        boolean notificationOn = isNotificationListenerEnabled();
        boolean accessibilityOn = isAccessibilityServiceEnabled();
        LinearLayout notificationRow = row();
        notificationRow.addView(
                label("通知监听：" + (notificationOn ? "已开启" : "未开启")),
                weightParams(1)
        );
        Button openNotification = compactButton("设置", COLOR_ACCENT);
        openNotification.setOnClickListener(v -> openSettings(NotificationCaptureService.class, true));
        notificationRow.addView(openNotification, compactButtonParams());
        permissionCard.addView(notificationRow, fullWidthParams(dp(10)));

        LinearLayout accessibilityRow = row();
        accessibilityRow.addView(
                label("无障碍：" + (accessibilityOn ? "已开启" : "未开启")),
                weightParams(1)
        );
        Button openAccessibility = compactButton("设置", COLOR_ACCENT);
        openAccessibility.setOnClickListener(v -> openSettings(AccessibilityCaptureService.class, false));
        accessibilityRow.addView(openAccessibility, compactButtonParams());
        permissionCard.addView(accessibilityRow, fullWidthParams(dp(8)));
        content.addView(permissionCard, cardParams());

        LinearLayout sourceCard = flowCard();
        sourceCard.addView(text("抓取来源", 20, true, COLOR_INK));
        sourceCard.addView(settingCheckBox(
                "微信",
                repository.isSourceEnabled(SourceKey.WECHAT),
                (button, checked) -> repository.setSourceEnabled(SourceKey.WECHAT, checked)
        ));
        sourceCard.addView(settingCheckBox(
                "支付宝",
                repository.isSourceEnabled(SourceKey.ALIPAY),
                (button, checked) -> repository.setSourceEnabled(SourceKey.ALIPAY, checked)
        ));
        sourceCard.addView(settingCheckBox(
                "云闪付",
                repository.isSourceEnabled(SourceKey.UNIONPAY),
                (button, checked) -> repository.setSourceEnabled(SourceKey.UNIONPAY, checked)
        ));
        sourceCard.addView(settingCheckBox(
                "高置信度自动入账",
                repository.isAutoConfirmEnabled(),
                (button, checked) -> repository.setAutoConfirmEnabled(checked)
        ));
        content.addView(sourceCard, cardParams());

        LinearLayout keepAliveCard = flowCard();
        keepAliveCard.addView(text("后台保活", 20, true, COLOR_INK));
        keepAliveCard.addView(settingCheckBox(
                "后台保活通知",
                repository.isKeepAliveEnabled(),
                (button, checked) -> {
                    repository.setKeepAliveEnabled(checked);
                    if (checked) {
                        KeepAliveService.start(this);
                    } else {
                        KeepAliveService.stop(this);
                    }
                }
        ));
        keepAliveCard.addView(settingCheckBox(
                "从最近任务隐藏",
                repository.isHideFromRecentsEnabled(),
                (button, checked) -> {
                    repository.setHideFromRecentsEnabled(checked);
                    BackgroundTaskHider.apply(this);
                }
        ));

        boolean shizukuReady = ShizukuSupport.isPermissionGranted()
                || ShizukuSupport.canWriteSecureSettings(this);
        LinearLayout utilityRow = row();
        Button shizuku = compactButton("Shizuku", COLOR_ACCENT);
        shizuku.setOnClickListener(v -> showShizukuSetup());
        utilityRow.addView(shizuku, weightedCompactParams(1f));
        Button quickRestart = compactButton("快捷开关", COLOR_SECONDARY_CONTAINER);
        quickRestart.setOnClickListener(v -> {
            if (!ShizukuSupport.isPermissionGranted()
                    && !ShizukuSupport.canWriteSecureSettings(this)) {
                showShizukuSetup();
            } else {
                showQuickRestartGuide();
            }
        });
        utilityRow.addView(quickRestart, weightedCompactParams(1f));
        keepAliveCard.addView(utilityRow, fullWidthParams(dp(8)));
        keepAliveCard.addView(bodyText(
                "Shizuku 状态：" + (shizukuReady ? "已授权" : "未授权")
                        + "。可自动补回被澎湃系统移除的监听权限。",
                12,
                false,
                COLOR_MUTED
        ));
        content.addView(keepAliveCard, cardParams());

        LinearLayout futureCard = flowCard();
        futureCard.addView(text("扩展", 20, true, COLOR_INK));
        futureCard.addView(settingCheckBox(
                "允许未来 Root Hook 广播",
                repository.isSourceEnabled(SourceKey.ROOT_HOOK),
                (button, checked) -> repository.setSourceEnabled(SourceKey.ROOT_HOOK, checked)
        ));
        futureCard.addView(bodyText(
                "只有安装自建模块后才需要开启，关闭时任何外部广播都会被忽略。",
                12,
                false,
                COLOR_MUTED
        ));
        content.addView(futureCard, cardParams());
    }

    private void renderAboutPage() {
        content.addView(sectionTitle("关于"));

        CircleAvatarView avatar = new CircleAvatarView(this);
        avatar.setImageResource(R.drawable.profile_avatar);
        avatar.setRing(withAlpha(COLOR_ACCENT, 220), dp(3));
        LinearLayout avatarWrap = new LinearLayout(this);
        avatarWrap.setGravity(Gravity.CENTER);
        avatarWrap.addView(avatar, new LinearLayout.LayoutParams(dp(178), dp(178)));
        content.addView(avatarWrap, cardParams());

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(18)
        ));

        LinearLayout openSourceCard = flowCard();
        openSourceCard.addView(text("开源声明", 20, true, COLOR_INK));
        openSourceCard.addView(bodyText(
                "AutoLedger 是一个本地运行的开源记账工具。流水数据只保存在本机，不上传服务器。",
                14,
                false,
                COLOR_MUTED
        ));
        openSourceCard.addView(bodyText(
                "项目地址：github.com/doublefishes48/-AutoLedger-",
                13,
                false,
                COLOR_ACCENT
        ));
        openSourceCard.setOnClickListener(v -> openRepository());
        content.addView(openSourceCard, cardParams());

        content.addView(bodyText(
                "当前版本 V" + appVersion(),
                12,
                false,
                COLOR_MUTED
        ));
    }

    private CheckBox settingCheckBox(
            String label,
            boolean checked,
            CompoundButton.OnCheckedChangeListener listener
    ) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setChecked(checked);
        styleChoiceButton(checkBox);
        checkBox.setOnCheckedChangeListener(listener);
        return checkBox;
    }

    private void openRepository() {
        try {
            externalLaunches++;
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/doublefishes48/-AutoLedger-")
            ));
        } catch (Exception ignored) {
            if (externalLaunches > 0) {
                externalLaunches--;
            }
        }
    }

    private View appHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(0, dp(4), 0, dp(8));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("自动记账", 30, true, COLOR_INK);
        copy.addView(title);
        header.addView(copy, weightParams(1));

        TextView month = text(periodLabelShort() + " ▾", 12, true, COLOR_ACCENT);
        month.setGravity(Gravity.CENTER);
        month.setTextColor(contrastTextColor(COLOR_ACCENT));
        month.setBackground(roundedDrawable(COLOR_ACCENT, 18));
        month.setPadding(dp(14), dp(8), dp(14), dp(8));
        month.setOnClickListener(v -> showCalendarDialog());
        header.addView(month);
        return header;
    }

    private LinearLayout createBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable glass = roundedDrawable(withAlpha(COLOR_CARD, 224), 28);
        glass.setStroke(dp(1), withAlpha(COLOR_INK, 34));
        nav.setBackground(glass);
        nav.setElevation(dp(18));
        nav.addView(navItem(0, R.drawable.ic_home, "首页"), weightParams(1));
        nav.addView(navItem(1, R.drawable.ic_settings, "设置"), weightParams(1));
        nav.addView(navItem(2, R.drawable.ic_about, "关于"), weightParams(1));
        return nav;
    }

    private View navItem(int tab, int iconResource, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setTag(tab);
        item.setPadding(dp(3), dp(3), dp(3), dp(3));
        item.setOnClickListener(v -> selectTab(tab));

        FrameLayout iconWrap = new FrameLayout(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                dp(19),
                dp(19),
                Gravity.CENTER
        );
        iconWrap.addView(icon, iconParams);
        item.addView(iconWrap, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView text = text(label, 9, true, COLOR_MUTED);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(1), 0, 0);
        item.addView(text);
        return item;
    }

    private LinearLayout createRefreshIndicator() {
        LinearLayout indicator = new LinearLayout(this);
        indicator.setOrientation(LinearLayout.HORIZONTAL);
        indicator.setGravity(Gravity.CENTER);
        indicator.setPadding(dp(12), dp(7), dp(14), dp(7));
        GradientDrawable glass = roundedDrawable(withAlpha(COLOR_CARD, 232), 19);
        glass.setStroke(dp(1), withAlpha(COLOR_INK, 28));
        indicator.setBackground(glass);
        indicator.setElevation(dp(10));

        refreshSpinner = new ProgressBar(this);
        refreshSpinner.setIndeterminate(true);
        refreshSpinner.setVisibility(View.GONE);
        refreshSpinner.getIndeterminateDrawable().setTint(COLOR_ACCENT);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        spinnerParams.rightMargin = dp(8);
        indicator.addView(refreshSpinner, spinnerParams);

        refreshLabel = text("下拉刷新", 12, true, COLOR_INK);
        indicator.addView(refreshLabel);
        return indicator;
    }

    private void updateBottomNav() {
        if (bottomNav == null) {
            return;
        }
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            View child = bottomNav.getChildAt(i);
            if (!(child instanceof LinearLayout)) {
                continue;
            }
            LinearLayout item = (LinearLayout) child;
            boolean selected = item.getTag() instanceof Integer
                    && (Integer) item.getTag() == currentTab;
            FrameLayout iconWrap = (FrameLayout) item.getChildAt(0);
            ImageView icon = (ImageView) iconWrap.getChildAt(0);
            TextView label = (TextView) item.getChildAt(1);
            int color = selected ? COLOR_ACCENT : COLOR_MUTED;
            icon.setColorFilter(color);
            label.setTextColor(color);
            if (selected) {
                GradientDrawable glow = roundedDrawable(
                        withAlpha(COLOR_ACCENT, 52),
                        30
                );
                glow.setStroke(dp(1), withAlpha(COLOR_ACCENT, 118));
                item.setBackground(glow);
                item.setElevation(dp(4));
            } else {
                item.setBackground(null);
                item.setElevation(0);
            }
            float scale = selected ? 1.06f : 1f;
            if (lastNavTab != currentTab) {
                item.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(170L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else {
                item.setScaleX(scale);
                item.setScaleY(scale);
            }
        }
        lastNavTab = currentTab;
    }

    private LinearLayout createFloatingActions() {
        LinearLayout actions = row();
        actions.setGravity(Gravity.BOTTOM | Gravity.END);

        Button importButton = button("导入账单", COLOR_SECONDARY_CONTAINER);
        importButton.setOnClickListener(v -> showBillImportDialog());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(46)
        );
        importParams.rightMargin = dp(10);
        actions.addView(importButton, importParams);

        ImageButton addButton = new ImageButton(this);
        addButton.setImageResource(R.drawable.ic_add);
        addButton.setColorFilter(contrastTextColor(COLOR_ACCENT));
        addButton.setContentDescription("记一笔");
        addButton.setBackground(roundedDrawable(COLOR_ACCENT, 27));
        addButton.setPadding(dp(15), dp(15), dp(15), dp(15));
        addButton.setElevation(dp(12));
        addButton.setOnClickListener(v -> showManualDialog());
        actions.addView(addButton, new LinearLayout.LayoutParams(dp(54), dp(54)));
        return actions;
    }

    private void configureScrollBehavior() {
        scrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (currentTab != 0) {
                return;
            }
            int delta = scrollY - oldScrollY;
            if (scrollY <= dp(4)) {
                setFloatingActionsVisible(true);
            } else if (delta > dp(3)) {
                setFloatingActionsVisible(false);
            } else if (delta < -dp(3)) {
                setFloatingActionsVisible(true);
            }
            lastScrollY = scrollY;
        });

        scrollView.setOnTouchListener((view, event) -> {
            if (currentTab != 0 || refreshing) {
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pullStartY = event.getRawY();
                pullProgress = 0f;
                pullTriggered = false;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float distance = event.getRawY() - pullStartY;
                if (scrollView.getScrollY() == 0 && distance > 0f) {
                    pullProgress = Math.min(1f, distance / dp(150));
                    pullTriggered = pullProgress >= 1f;
                    refreshLabel.setText(pullTriggered ? "松开刷新" : "下拉刷新");
                    refreshIndicator.setVisibility(View.VISIBLE);
                    refreshIndicator.setAlpha(Math.max(0.18f, pullProgress));
                    refreshIndicator.setTranslationY(
                            -dp(12) + pullProgress * dp(12)
                    );
                } else if (distance <= 0f) {
                    hideRefreshIndicator();
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (pullTriggered) {
                    startRefreshAnimation();
                } else {
                    hideRefreshIndicator();
                }
                pullTriggered = false;
                pullProgress = 0f;
            }
            return false;
        });
    }

    private void startRefreshAnimation() {
        refreshing = true;
        refreshSpinner.setVisibility(View.VISIBLE);
        refreshLabel.setText("正在刷新");
        refreshIndicator.animate().cancel();
        refreshIndicator.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(150L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        scrollView.postDelayed(() -> {
            if (currentTab == 0) {
                render();
            }
            scrollView.postDelayed(this::hideRefreshIndicator, 360L);
        }, 620L);
    }

    private void hideRefreshIndicator() {
        if (refreshIndicator == null) {
            return;
        }
        refreshIndicator.animate().cancel();
        refreshIndicator.animate()
                .alpha(0f)
                .translationY(-dp(12))
                .setDuration(170L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    refreshing = false;
                    refreshSpinner.setVisibility(View.GONE);
                    refreshIndicator.setVisibility(View.GONE);
                })
                .start();
    }

    private void setFloatingActionsVisible(boolean visible) {
        if (floatingActions == null || floatingActionsVisible == visible) {
            return;
        }
        floatingActionsVisible = visible;
        floatingActions.animate().cancel();
        floatingActions.animate()
                .alpha(visible ? 1f : 0f)
                .translationY(visible ? 0f : dp(28))
                .setDuration(190L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void selectTab(int tab) {
        if (currentTab == tab) {
            scrollView.smoothScrollTo(0, 0);
            return;
        }
        slideDirection = tab > currentTab ? 1 : -1;
        animateTabTransition = true;
        currentTab = tab;
        lastScrollY = 0;
        scrollView.scrollTo(0, 0);
        floatingActionsVisible = true;
        floatingActions.setAlpha(1f);
        floatingActions.setTranslationY(0f);
        render();
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier(
                "status_bar_height",
                "dimen",
                "android"
        );
        return resourceId > 0
                ? getResources().getDimensionPixelSize(resourceId)
                : dp(28);
    }

    private String appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "未知" : info.versionName;
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private String periodLabelShort() {
        String pattern = selectedDay == null ? "M月" : "M月d日";
        Calendar value = selectedDay == null ? selectedMonth : selectedDay;
        return new SimpleDateFormat(pattern, Locale.CHINA).format(value.getTime());
    }

    private String selectedPeriodLabel() {
        String pattern = selectedDay == null ? "yyyy年M月" : "yyyy年M月d日";
        Calendar value = selectedDay == null ? selectedMonth : selectedDay;
        return new SimpleDateFormat(pattern, Locale.CHINA).format(value.getTime());
    }

    private void showCalendarDialog() {
        Calendar displayed = (Calendar) (selectedDay == null
                ? selectedMonth
                : selectedDay).clone();
        displayed.set(Calendar.DAY_OF_MONTH, 1);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(roundedDrawable(COLOR_CARD, 28));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel)
                .create();
        calendarDialogOpen = true;
        dialog.setOnDismissListener(ignored -> calendarDialogOpen = false);
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window == null) {
                return;
            }
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = Math.round(
                    getResources().getDisplayMetrics().widthPixels * 0.90f
            );
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            params.dimAmount = 0.68f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                params.setBlurBehindRadius(dp(22));
            }
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        });
        renderCalendar(dialog, panel, displayed);
        dialog.show();
    }

    private void renderCalendar(
            Dialog dialog,
            LinearLayout panel,
            Calendar displayed
    ) {
        panel.removeAllViews();

        TextView year = text(
                String.valueOf(displayed.get(Calendar.YEAR)) + "年",
                20,
                true,
                COLOR_INK
        );
        year.setGravity(Gravity.CENTER);
        panel.addView(year);

        LinearLayout monthRow = row();
        Button previous = compactButton("‹", COLOR_ACTION_SETTINGS);
        previous.setTextSize(24);
        previous.setPadding(0, 0, 0, dp(2));
        previous.setOnClickListener(v -> {
            displayed.add(Calendar.MONTH, -1);
            renderCalendar(dialog, panel, displayed);
        });
        monthRow.addView(previous, monthButtonParams());

        TextView month = text(
                String.valueOf(displayed.get(Calendar.MONTH) + 1) + "月",
                18,
                true,
                COLOR_ACCENT
        );
        month.setGravity(Gravity.CENTER);
        monthRow.addView(month, weightParams(1));

        Button next = compactButton("›", COLOR_ACTION_SETTINGS);
        next.setTextSize(24);
        next.setPadding(0, 0, 0, dp(2));
        next.setOnClickListener(v -> {
            displayed.add(Calendar.MONTH, 1);
            renderCalendar(dialog, panel, displayed);
        });
        monthRow.addView(next, monthButtonParams());
        panel.addView(monthRow);

        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(12)
        ));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(7);
        String[] weekdays = {"日", "一", "二", "三", "四", "五", "六"};
        for (String weekday : weekdays) {
            TextView label = text(weekday, 12, true, COLOR_MUTED);
            label.setGravity(Gravity.CENTER);
            grid.addView(label, calendarCellParams());
        }

        Calendar first = (Calendar) displayed.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int offset = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        for (int i = 0; i < offset; i++) {
            TextView blank = text("", 14, false, COLOR_INK);
            grid.addView(blank, calendarCellParams());
        }

        int dayCount = displayed.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar now = Calendar.getInstance();
        for (int day = 1; day <= dayCount; day++) {
            Calendar value = (Calendar) displayed.clone();
            value.set(Calendar.DAY_OF_MONTH, day);
            boolean selected = selectedDay != null
                    && selectedDay.get(Calendar.YEAR) == value.get(Calendar.YEAR)
                    && selectedDay.get(Calendar.MONTH) == value.get(Calendar.MONTH)
                    && selectedDay.get(Calendar.DAY_OF_MONTH) == day;
            boolean today = now.get(Calendar.YEAR) == value.get(Calendar.YEAR)
                    && now.get(Calendar.MONTH) == value.get(Calendar.MONTH)
                    && now.get(Calendar.DAY_OF_MONTH) == day;

            TextView dayView = text(
                    String.valueOf(day),
                    14,
                    selected || today,
                    selected
                            ? contrastTextColor(COLOR_PRIMARY)
                            : today
                            ? COLOR_ON_PRIMARY_CONTAINER
                            : COLOR_INK
            );
            dayView.setGravity(Gravity.CENTER);
            if (selected) {
                dayView.setBackground(roundedDrawable(COLOR_PRIMARY, 22));
            } else if (today) {
                dayView.setBackground(roundedDrawable(COLOR_PRIMARY_CONTAINER, 22));
            }
            dayView.setOnClickListener(v -> {
                selectedDay = value;
                selectedMonth.set(
                        value.get(Calendar.YEAR),
                        value.get(Calendar.MONTH),
                        1
                );
                dialog.dismiss();
                render();
            });
            grid.addView(dayView, calendarCellParams());
        }
        panel.addView(grid);

        View footerSpacer = new View(this);
        panel.addView(footerSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(12)
        ));

        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER);
        Button wholeMonth = compactButton("整月", COLOR_SECONDARY_CONTAINER);
        wholeMonth.setOnClickListener(v -> {
            selectedDay = null;
            selectedMonth.set(
                    displayed.get(Calendar.YEAR),
                    displayed.get(Calendar.MONTH),
                    1
            );
            dialog.dismiss();
            render();
        });
        actions.addView(wholeMonth, calendarActionParams());

        Button todayButton = compactButton("今天", COLOR_ACCENT);
        todayButton.setOnClickListener(v -> {
            Calendar today = Calendar.getInstance();
            selectedDay = today;
            selectedMonth.set(
                    today.get(Calendar.YEAR),
                    today.get(Calendar.MONTH),
                    1
            );
            dialog.dismiss();
            render();
        });
        actions.addView(todayButton, calendarActionParams());
        panel.addView(actions);
    }

    private GridLayout.LayoutParams calendarCellParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(42);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        return params;
    }

    private View summaryPanel() {
        LedgerRepository.Summary summary = selectedDay == null
                ? repository.loadSummary(selectedMonth)
                : repository.loadDaySummary(selectedDay);
        long balance = summary.incomeCents - summary.expenseCents;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(roundedDrawable(COLOR_ACCENT_DEEP, 28));

        LinearLayout captionRow = row();
        captionRow.addView(text(
                selectedDay == null ? "当月结余" : "当日结余",
                13,
                false,
                withAlpha(COLOR_PRIMARY_CONTAINER, 180)
        ));
        if (summary.pendingCount > 0) {
            captionRow.addView(pendingPill(summary.pendingCount));
        }
        panel.addView(captionRow);

        TextView balanceText = text(money(balance), 30, true, COLOR_PRIMARY_CONTAINER);
        balanceText.setPadding(0, dp(4), 0, dp(10));
        panel.addView(balanceText);

        View divider = new View(this);
        divider.setBackgroundColor(withAlpha(COLOR_PRIMARY_CONTAINER, 42));
        panel.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
        ));

        LinearLayout stats = row();
        stats.setPadding(0, dp(12), 0, 0);
        stats.addView(summaryStat("支出", summary.expenseCents, COLOR_PRIMARY_CONTAINER), weightParams(1));
        stats.addView(summaryStat("收入", summary.incomeCents, COLOR_PRIMARY_CONTAINER), weightParams(1));
        panel.addView(stats);
        return panel;
    }

    private View pendingPill(int count) {
        TextView pill = text("待确认 " + count, 11, true, COLOR_ON_SECONDARY_CONTAINER);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(roundedDrawable(COLOR_SECONDARY_CONTAINER, 18));
        pill.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = dp(8);
        pill.setLayoutParams(params);
        return pill;
    }

    private View summaryStat(String label, long cents, int tint) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(4), 0, dp(4), 0);
        cell.addView(text(label, 12, false, withAlpha(COLOR_PRIMARY_CONTAINER, 175)));
        cell.addView(text(money(cents), 16, true, tint));
        return cell;
    }

    private View actionRow() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackground(roundedDrawable(COLOR_SURFACE_VARIANT, 24));

        Button manual = button("记一笔", COLOR_PRIMARY);
        Button importBill = button("导入账单", COLOR_ACTION_IMPORT);
        Button permissions = button("权限与来源", COLOR_ACTION_SETTINGS);
        Button refresh = button("刷新", COLOR_ACTION_REFRESH);
        manual.setOnClickListener(v -> showManualDialog());
        importBill.setOnClickListener(v -> showBillImportDialog());
        permissions.setOnClickListener(v -> showSettingsDialog());
        refresh.setOnClickListener(v -> render());

        LinearLayout firstRow = row();
        firstRow.setPadding(0, 0, 0, dp(8));
        firstRow.addView(manual, actionParams());
        firstRow.addView(importBill, actionParams());
        panel.addView(firstRow);

        LinearLayout secondRow = row();
        secondRow.addView(permissions, actionParams());
        secondRow.addView(refresh, actionParams());
        panel.addView(secondRow);
        return panel;
    }

    private boolean renderPending() {
        List<RawCaptureRecord> pending = repository.pendingCaptures();
        if (pending.isEmpty()) {
            return false;
        }
        content.addView(sectionTitle("待确认抓取"));

        for (RawCaptureRecord raw : pending) {
            LinearLayout card = flowCard();
            LinearLayout top = row();

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(rawTitle(raw), 14, true, COLOR_INK);
            title.setMaxLines(2);
            info.addView(title);
            info.addView(text(
                    CategoryCatalog.label(raw.category)
                            + " · " + sourceName(raw.sourceKey),
                    12,
                    false,
                    COLOR_MUTED
            ));
            top.addView(info, weightParams(1));

            String prefix = LedgerEntry.DIRECTION_EXPENSE.equals(raw.direction) ? "-" : "+";
            TextView amountText = text(
                    prefix + money(raw.amountCents),
                    19,
                    true,
                    LedgerEntry.DIRECTION_EXPENSE.equals(raw.direction)
                            ? COLOR_EXPENSE
                            : COLOR_INCOME
            );
            top.addView(amountText);
            card.addView(top);

            LinearLayout buttons = row();
            buttons.setPadding(0, dp(10), 0, 0);
            Button confirm = button("确认", COLOR_PRIMARY);
            Button overwrite = button("覆盖", COLOR_ACTION_IMPORT);
            Button ignore = button("忽略", COLOR_ACTION_REFRESH);
            confirm.setOnClickListener(v -> {
                repository.confirmRawCapture(raw.id);
                render();
            });
            overwrite.setOnClickListener(v -> {
                if (repository.overwriteRecentTransaction(raw.id)) {
                    render();
                } else {
                    Toast.makeText(
                            this,
                            "没有找到可覆盖的近期流水",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            });
            ignore.setOnClickListener(v -> {
                repository.ignoreRawCapture(raw.id);
                render();
            });
            buttons.addView(confirm, weightParams(1));
            if (repository.canOverwriteRecentTransaction(raw)) {
                buttons.addView(overwrite, weightParams(1));
            }
            buttons.addView(ignore, weightParams(1));
            card.addView(buttons);
            content.addView(card, cardParams());
        }
        return true;
    }

    private void renderTransactions() {
        List<LedgerEntry> entries = selectedDay == null
                ? repository.transactionsForMonth(selectedMonth, 200)
                : repository.transactionsForDay(selectedDay, 200);
        if (entries.isEmpty()) {
            content.addView(emptyState(
                    selectedDay == null ? "这个月还没有流水" : "这天还没有流水",
                    selectedDay == null ? "点右上角月份选择其他日期" : "可以选择其他日期查看"
            ), cardParams());
            return;
        }

        for (LedgerEntry entry : entries) {
            LinearLayout card = flowCard();
            card.setOnClickListener(v -> showTransactionDialog(entry));

            LinearLayout top = row();
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            String prefix = entry.isExpense() ? "-" : "+";
            String categoryLabel = CategoryCatalog.label(entry.category);
            boolean hasMerchant = entry.merchant != null && !entry.merchant.isEmpty();
            String merchantLabel = hasMerchant ? entry.merchant : categoryLabel;
            TextView title = text(
                    merchantLabel,
                    15,
                    true,
                    COLOR_INK
            );
            title.setMaxLines(2);
            info.addView(title);
            String source = sourceName(entry.sourceKey);
            String accountSuffix = entry.account == null
                    || entry.account.isEmpty()
                    || source.equals(entry.account)
                    ? ""
                    : " · " + entry.account;
            TextView detail = text(
                    (hasMerchant ? categoryLabel + " · " : "")
                            + dateFormat.format(new Date(entry.occurredAt))
                            + " · " + source + accountSuffix,
                    12,
                    false,
                    COLOR_MUTED
            );
            info.addView(detail);
            top.addView(info, weightParams(1));

            TextView amountText = text(
                    prefix + money(entry.amountCents),
                    18,
                    true,
                    entry.isExpense() ? COLOR_EXPENSE : COLOR_INCOME
            );
            amountText.setPadding(dp(8), 0, 0, 0);
            top.addView(amountText);
            card.addView(top);
            content.addView(card, cardParams());
        }
    }

    private View emptyState(String title, String subtitle) {
        LinearLayout empty = flowCard();
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(14), dp(18), dp(14), dp(18));
        empty.addView(text(title, 15, true, COLOR_INK));
        empty.addView(text(subtitle, 12, false, COLOR_MUTED));
        return empty;
    }

    private void showManualDialog() {
        LinearLayout form = formContainer();
        EditText amountInput = editText("金额，例如 12.50", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        RadioGroup directionGroup = new RadioGroup(this);
        RadioButton expense = new RadioButton(this);
        expense.setId(View.generateViewId());
        expense.setText("支出");
        expense.setChecked(true);
        styleChoiceButton(expense);
        RadioButton income = new RadioButton(this);
        income.setId(View.generateViewId());
        income.setText("收入");
        styleChoiceButton(income);
        directionGroup.addView(expense);
        directionGroup.addView(income);

        Spinner categorySpinner = categorySpinner(MANUAL_CATEGORY_KEYS);
        EditText merchantInput = editText("商户/备注标题", InputType.TYPE_CLASS_TEXT);
        Spinner accountSpinner = accountSpinner();
        EditText noteInput = editText("备注（可选）", InputType.TYPE_CLASS_TEXT);

        form.addView(label("金额"));
        form.addView(amountInput);
        form.addView(label("收支"));
        form.addView(directionGroup);
        form.addView(label("分类"));
        form.addView(categorySpinner);
        form.addView(label("商户/标题"));
        form.addView(merchantInput);
        form.addView(label("账户"));
        form.addView(accountSpinner);
        form.addView(label("备注"));
        form.addView(noteInput);

        ScrollView scroll = wrapDialogForm(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("手动记一笔")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        showThemedDialog(dialog);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            double yuan;
            try {
                yuan = Double.parseDouble(amountInput.getText().toString().trim());
            } catch (NumberFormatException ignored) {
                Toast.makeText(this, "金额格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }
            if (yuan <= 0) {
                Toast.makeText(this, "金额必须大于 0", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean incomeSelected = directionGroup.getCheckedRadioButtonId() == income.getId();
            String direction = incomeSelected
                    ? LedgerEntry.DIRECTION_INCOME
                    : LedgerEntry.DIRECTION_EXPENSE;
            String category = MANUAL_CATEGORY_KEYS[categorySpinner.getSelectedItemPosition()];
            String merchant = merchantInput.getText().toString().trim();
            String account = accountSpinner.getSelectedItem() == null
                    ? "手动"
                    : accountSpinner.getSelectedItem().toString();
            String note = noteInput.getText().toString().trim();

            repository.addManualTransaction(
                    Math.round(yuan * 100),
                    direction,
                    category,
                    merchant.isEmpty() ? account : merchant,
                    account,
                    note
            );
            dialog.dismiss();
            render();
        });
    }

    private void showBillImportDialog() {
        LinearLayout form = formContainer();
        form.addView(bodyText("微信个人对账选 Excel/CSV：", 15, true, COLOR_INK));
        form.addView(bodyText(
                "微信：钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账。下载后直接选 Excel；PDF 是证明材料，不用于导入。",
                13,
                false,
                COLOR_MUTED
        ));
        form.addView(bodyText(
                "支付宝如果能导出 CSV 也会自动识别；没有稳定 CSV 时继续用自动抓取或手动记一笔。",
                13,
                false,
                COLOR_MUTED
        ));
        form.addView(bodyText(
                "云闪付暂时没有统一个人 CSV，先保留通知抓取。",
                13,
                false,
                COLOR_MUTED
        ));
        form.addView(bodyText(
                "导入只读取你选择的 Excel/CSV，不上传，也不需要存储权限。",
                13,
                false,
                COLOR_MUTED
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("导入账单 Excel/CSV")
                .setView(wrapDialogForm(form))
                .setPositiveButton("选择文件", (ignoredDialog, which) -> openBillCsvPicker())
                .setNegativeButton("取消", null)
                .create();
        showThemedDialog(dialog);
    }

    private void openBillCsvPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            externalLaunches++;
            startActivityForResult(intent, BILL_IMPORT_REQUEST);
        } catch (Exception ignored) {
            if (externalLaunches > 0) {
                externalLaunches--;
            }
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void importBillCsv(Uri uri) {
        final AlertDialog loading = new AlertDialog.Builder(this)
                .setMessage("正在读取并导入，请稍候")
                .setCancelable(false)
                .create();
        showThemedDialog(loading);
        new Thread(() -> {
            try {
                final CsvBillImporter.ParseResult parsed;
                try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                    parsed = CsvBillImporter.read(input);
                }
                final LedgerRepository.BillImportResult outcome = repository.importBillRows(parsed.rows);
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        loading.dismiss();
                    }
                    render();
                    showBillImportResult(parsed, outcome);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        loading.dismiss();
                    }
                    Toast.makeText(
                            this,
                            "导入失败，请确认选择的是微信个人对账 Excel 或 CSV 文件",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        }).start();
    }

    private void showBillImportResult(
            CsvBillImporter.ParseResult parsed,
            LedgerRepository.BillImportResult outcome
    ) {
        String platform = parsed.sourceName == null ? "未识别" : parsed.sourceName;
        StringBuilder message = new StringBuilder();
        message.append("识别为：").append(platform).append("\n");
        message.append("新增：").append(outcome.imported).append(" 条\n");
        message.append("重复跳过：").append(outcome.duplicate).append(" 条\n");
        if (outcome.skipped > 0) {
            message.append("其他跳过：").append(outcome.skipped).append(" 条\n");
        }
        if (parsed.invalidRows > 0) {
            message.append("无效或非交易行：").append(parsed.invalidRows).append(" 条\n");
        }
        if (!parsed.errors.isEmpty()) {
            message.append("\n提示：").append(parsed.errors.get(0));
            if (parsed.errors.size() > 1 || parsed.invalidRows > parsed.errors.size()) {
                message.append("\n更多问题可在文件中检查后再试。");
            }
        }
        if (outcome.imported > 0) {
            message.append("\n\n已加入最近流水，仍可点击流水修改分类或删除。");
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("导入结果")
                .setMessage(message.toString())
                .setPositiveButton("完成", null)
                .create();
        showThemedDialog(dialog);
    }

    private void showSettingsDialog() {
        LinearLayout form = formContainer();
        form.addView(bodyText("通知读取和无障碍只读取系统暴露的支付文本，不会点击、付款或读取密码。微信若抓不到，用账单导入兜底。", 14, false, COLOR_MUTED));

        boolean notificationOn = isNotificationListenerEnabled();
        boolean accessibilityOn = isAccessibilityServiceEnabled();
        form.addView(label("通知监听：" + (notificationOn ? "已开启" : "未开启")));
        Button openNotification = button("去系统开启通知使用权限", COLOR_PRIMARY);
        openNotification.setOnClickListener(v -> openSettings(NotificationCaptureService.class, true));
        form.addView(openNotification);

        form.addView(label("无障碍页面识别：" + (accessibilityOn ? "已开启" : "未开启")));
        Button openAccessibility = button("去系统开启无障碍", COLOR_PRIMARY);
        openAccessibility.setOnClickListener(v -> openSettings(AccessibilityCaptureService.class, false));
        form.addView(openAccessibility);

        CheckBox wechat = new CheckBox(this);
        wechat.setText("抓取微信通知/页面");
        wechat.setChecked(repository.isSourceEnabled(SourceKey.WECHAT));
        CheckBox alipay = new CheckBox(this);
        alipay.setText("抓取支付宝通知/页面");
        alipay.setChecked(repository.isSourceEnabled(SourceKey.ALIPAY));
        CheckBox unionpay = new CheckBox(this);
        unionpay.setText("抓取云闪付通知/页面");
        unionpay.setChecked(repository.isSourceEnabled(SourceKey.UNIONPAY));
        CheckBox autoConfirm = new CheckBox(this);
        autoConfirm.setText("高置信度自动入账");
        autoConfirm.setChecked(repository.isAutoConfirmEnabled());
        CheckBox keepAlive = new CheckBox(this);
        keepAlive.setText("后台保活通知（澎湃/MIUI建议开启）");
        keepAlive.setChecked(repository.isKeepAliveEnabled());
        CheckBox hideRecents = new CheckBox(this);
        hideRecents.setText("后台隐藏：从最近任务里隐藏这张卡片");
        hideRecents.setChecked(repository.isHideFromRecentsEnabled());
        boolean shizukuReady = ShizukuSupport.isPermissionGranted()
                || ShizukuSupport.canWriteSecureSettings(this);
        Button shizuku = button("授权 Shizuku 自愈", COLOR_PRIMARY);
        shizuku.setOnClickListener(v -> showShizukuSetup());
        Button quickRestart = button("无感保活：添加快捷开关", COLOR_ACTION_SETTINGS);
        quickRestart.setOnClickListener(v -> {
            if (!ShizukuSupport.isPermissionGranted()
                    && !ShizukuSupport.canWriteSecureSettings(this)) {
                showShizukuSetup();
            } else {
                showQuickRestartGuide();
            }
        });
        CheckBox rootHook = new CheckBox(this);
        rootHook.setText("允许未来 Root Hook 广播（默认关）");
        rootHook.setChecked(repository.isSourceEnabled(SourceKey.ROOT_HOOK));

        styleChoiceButton(wechat);
        styleChoiceButton(alipay);
        styleChoiceButton(unionpay);
        styleChoiceButton(autoConfirm);
        styleChoiceButton(keepAlive);
        styleChoiceButton(hideRecents);
        styleChoiceButton(rootHook);

        form.addView(wechat);
        form.addView(alipay);
        form.addView(unionpay);
        form.addView(autoConfirm);
        form.addView(keepAlive);
        form.addView(hideRecents);
        form.addView(shizuku);
        form.addView(quickRestart);
        form.addView(bodyText(
                "Shizuku 状态：" + (shizukuReady ? "已授权" : "未授权")
                        + "。可自动补回被澎湃系统移除的监听权限。",
                12,
                false,
                COLOR_MUTED
        ));
        form.addView(rootHook);
        form.addView(bodyText(
                "Root Hook 只在安装自建模块后手动打开。关闭时任何外部广播都会被忽略。",
                12,
                false,
                COLOR_MUTED
        ));

        ScrollView scroll = wrapDialogForm(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("抓取与权限")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        showThemedDialog(dialog);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            repository.setSourceEnabled(SourceKey.WECHAT, wechat.isChecked());
            repository.setSourceEnabled(SourceKey.ALIPAY, alipay.isChecked());
            repository.setSourceEnabled(SourceKey.UNIONPAY, unionpay.isChecked());
            repository.setAutoConfirmEnabled(autoConfirm.isChecked());
            boolean hideRecentsEnabled = hideRecents.isChecked();
            repository.setHideFromRecentsEnabled(hideRecentsEnabled);
            boolean keepAliveEnabled = keepAlive.isChecked();
            repository.setKeepAliveEnabled(keepAliveEnabled);
            if (keepAliveEnabled) {
                KeepAliveService.start(this);
            } else {
                KeepAliveService.stop(this);
            }
            AccessibilityCaptureService.refreshKeepAliveOverlay(this);
            BackgroundTaskHider.apply(this);
            repository.setSourceEnabled(SourceKey.ROOT_HOOK, rootHook.isChecked());
            dialog.dismiss();
            render();
        });
    }

    private void showQuickRestartGuide() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("无感保活：快捷开关")
                .setMessage(
                        "1. 下拉通知栏，点右上角编辑/更多，进入快捷开关面板\n\n"
                                + "2. 找到“自动记账”，点 + 号或拖到上方\n\n"
                                + "3. 以后后台服务被系统杀掉，下拉通知栏点这个开关即可自动拉起并修复监听。"
                )
                .setPositiveButton("完成", null)
                .create();
        showThemedDialog(dialog);
    }

    private void showShizukuSetup() {
        if (!ShizukuSupport.isAvailable()) {
            if (!ShizukuSupport.canWriteSecureSettings(this)) {
                Toast.makeText(
                        this,
                        "请先启动 Shizuku，再回来点授权",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
        }
        if (ShizukuSupport.isPermissionGranted()) {
            boolean writeGranted = ShizukuSupport.grantWriteSecureSettings(this);
            boolean guardFixed = ServiceGuard.run(this);
            Toast.makeText(
                    this,
                    (writeGranted && guardFixed)
                            ? "Shizuku 权限已恢复"
                            : "请到系统设置手动开启监听权限",
                    Toast.LENGTH_SHORT
            ).show();
            render();
            return;
        }
        if (ShizukuSupport.canWriteSecureSettings(this)) {
            boolean guardFixed = ServiceGuard.run(this);
            Toast.makeText(
                    this,
                    guardFixed ? "监听权限已恢复" : "需要到系统设置手动开启监听权限",
                    Toast.LENGTH_SHORT
            ).show();
            render();
            return;
        }
        externalLaunches++;
        ShizukuSupport.requestPermission(SHIZUKU_PERMISSION_REQUEST);
    }

    private void showTransactionDialog(LedgerEntry entry) {
        LinearLayout form = formContainer();
        form.addView(text("流水详情", 20, true, COLOR_INK));
        form.addView(text(
                money(entry.amountCents),
                28,
                true,
                entry.isExpense() ? COLOR_EXPENSE : COLOR_INCOME
        ));
        form.addView(bodyText(
                directionName(entry.direction)
                        + " · " + CategoryCatalog.label(entry.category),
                14,
                true,
                COLOR_INK
        ));
        form.addView(bodyText(
                entry.merchant == null || entry.merchant.isEmpty()
                        ? "无商户"
                        : entry.merchant,
                14,
                false,
                COLOR_MUTED
        ));
        form.addView(bodyText(
                dateFormat.format(new Date(entry.occurredAt))
                        + " · " + sourceName(entry.sourceKey),
                13,
                false,
                COLOR_MUTED
        ));
        if (entry.note != null && !entry.note.isEmpty()) {
            form.addView(bodyText("备注：" + entry.note, 13, false, COLOR_MUTED));
        }

        LinearLayout actions = row();
        actions.setPadding(0, dp(12), 0, 0);
        Button delete = compactButton("删除", COLOR_EXPENSE);
        actions.addView(delete, weightedCompactParams(1f));

        Button near = compactButton("关闭", COLOR_ACTION_REFRESH);
        actions.addView(near, weightedCompactParams(1f));

        Button category = compactButton("改分类", COLOR_ACCENT);
        actions.addView(category, weightedCompactParams(1f));
        form.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(wrapDialogForm(form))
                .create();
        delete.setOnClickListener(v -> {
            repository.deleteTransaction(entry.id);
            dialog.dismiss();
            render();
        });
        near.setOnClickListener(v -> dialog.dismiss());
        category.setOnClickListener(v -> {
            dialog.dismiss();
            showCategoryDialog(entry);
        });
        showThemedDialog(dialog);
    }

    private void showCategoryDialog(LedgerEntry entry) {
        String[] keys = LedgerEntry.DIRECTION_INCOME.equals(entry.direction)
                ? CategoryCatalog.incomeKeys()
                : CategoryCatalog.expenseKeys();
        String[] labels = new String[keys.length];
        int selected = 0;
        for (int i = 0; i < keys.length; i++) {
            labels[i] = CategoryCatalog.label(keys[i]);
            if (keys[i].equals(entry.category)) {
                selected = i;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择分类")
                .setSingleChoiceItems(labels, selected, (ignoredDialog, which) -> {
                    repository.updateTransactionCategory(entry.id, keys[which]);
                    ignoredDialog.dismiss();
                    render();
                })
                .setNegativeButton("取消", null)
                .create();
        showThemedDialog(dialog);
    }

    private void openSettings(Class<?> service, boolean notificationListener) {
        try {
            Intent intent;
            if (notificationListener) {
                intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            } else {
                intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            }
            externalLaunches++;
            startActivity(intent);
        } catch (Exception ignored) {
            if (externalLaunches > 0) {
                externalLaunches--;
            }
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isNotificationListenerEnabled() {
        ComponentName component = new ComponentName(this, NotificationCaptureService.class);
        String value = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return value != null && value.contains(component.flattenToString());
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName component = new ComponentName(this, AccessibilityCaptureService.class);
        String value = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return value != null && value.contains(component.flattenToString());
    }

    private void applyMonetPalette() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        COLOR_BACKGROUND = systemColor(android.R.color.system_neutral1_900, COLOR_BACKGROUND);
        COLOR_CARD = systemColor(android.R.color.system_neutral1_800, COLOR_CARD);
        COLOR_SURFACE_VARIANT = systemColor(android.R.color.system_neutral2_800, COLOR_SURFACE_VARIANT);
        COLOR_BORDER = systemColor(android.R.color.system_neutral2_700, COLOR_BORDER);
        COLOR_INK = systemColor(android.R.color.system_neutral1_50, COLOR_INK);
        COLOR_MUTED = systemColor(android.R.color.system_neutral1_200, COLOR_MUTED);
        COLOR_PRIMARY = systemColor(android.R.color.system_accent1_100, COLOR_PRIMARY);
        COLOR_PRIMARY_DARK = systemColor(android.R.color.system_accent1_200, COLOR_PRIMARY_DARK);
        COLOR_ACCENT = systemColor(android.R.color.system_accent1_300, COLOR_ACCENT);
        COLOR_ACCENT_DEEP = systemColor(android.R.color.system_accent1_700, COLOR_ACCENT_DEEP);
        COLOR_PRIMARY_CONTAINER = COLOR_PRIMARY;
        COLOR_ON_PRIMARY_CONTAINER = systemColor(
                android.R.color.system_accent1_900,
                COLOR_ON_PRIMARY_CONTAINER
        );
        COLOR_SECONDARY_CONTAINER = systemColor(
                android.R.color.system_neutral2_700,
                COLOR_SECONDARY_CONTAINER
        );
        COLOR_ON_SECONDARY_CONTAINER = systemColor(
                android.R.color.system_neutral1_50,
                COLOR_ON_SECONDARY_CONTAINER
        );
        COLOR_ACTION_IMPORT = systemColor(
                android.R.color.system_accent2_200,
                COLOR_ACTION_IMPORT
        );
        COLOR_ACTION_SETTINGS = COLOR_SECONDARY_CONTAINER;
        COLOR_ACTION_REFRESH = systemColor(
                android.R.color.system_neutral2_600,
                COLOR_ACTION_REFRESH
        );
    }

    private int systemColor(int resourceId, int fallback) {
        try {
            return getResources().getColor(resourceId, getTheme());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int contrastTextColor(int background) {
        return Color.luminance(background) > 0.48f
                ? COLOR_ON_PRIMARY_CONTAINER
                : Color.WHITE;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void showThemedDialog(AlertDialog dialog) {
        dialog.show();
        styleDialogWindow(dialog);
        styleDialogActionButton(
                dialog.getButton(DialogInterface.BUTTON_POSITIVE),
                COLOR_PRIMARY
        );
        styleDialogActionButton(
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE),
                COLOR_ACTION_REFRESH
        );
        styleDialogActionButton(
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL),
                COLOR_ACTION_IMPORT
        );
    }

    private void styleDialogWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(roundedDrawable(COLOR_CARD, 28));
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = Math.round(
                getResources().getDisplayMetrics().widthPixels * 0.92f
        );
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.dimAmount = 0.68f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            params.setBlurBehindRadius(dp(22));
        }
        window.setAttributes(params);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.getDecorView().setPadding(dp(4), dp(4), dp(4), dp(4));
    }

    private void styleDialogActionButton(Button button, int background) {
        if (button == null) {
            return;
        }
        button.setAllCaps(false);
        button.setTextColor(contrastTextColor(background));
        button.setBackground(roundedDrawable(background, 18));
        button.setMinHeight(dp(42));
        button.setMinimumHeight(dp(42));
        button.setPadding(dp(18), dp(8), dp(18), dp(8));
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout flowCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = roundedDrawable(COLOR_CARD, 20);
        background.setStroke(dp(1), COLOR_BORDER);
        card.setBackground(background);
        return card;
    }

    private LinearLayout formContainer() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(4), dp(4), dp(4));
        return form;
    }

    private TextView label(String value) {
        return text(value, 13, true, COLOR_MUTED);
    }

    private TextView titleText(String value, float size, boolean bold, int color) {
        return text(value, size, bold, color);
    }

    private TextView bodyText(String value, float size, boolean bold, int color) {
        return text(value, size, bold, color);
    }

    private TextView sectionTitle(String value) {
        TextView text = text(value, 17, true, COLOR_INK);
        text.setPadding(0, dp(16), 0, dp(4));
        return text;
    }

    private TextView text(String value, float size, boolean bold, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(size);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        textView.setLineSpacing(0, 1.08f);
        textView.setPadding(0, dp(2), 0, dp(2));
        return textView;
    }

    private Button button(String value, int color) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(contrastTextColor(color));
        GradientDrawable background = roundedDrawable(color, 18);
        if (color == COLOR_ACTION_SETTINGS || color == COLOR_ACTION_REFRESH) {
            background.setStroke(dp(1), COLOR_BORDER);
        }
        button.setBackground(background);
        button.setElevation(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        return button;
    }

    private Button compactButton(String value, int color) {
        Button button = button(value, color);
        button.setTextSize(12);
        button.setMinHeight(dp(38));
        button.setMinimumHeight(dp(38));
        button.setPadding(dp(12), dp(7), dp(12), dp(7));
        button.setBackground(roundedDrawable(color, 16));
        return button;
    }

    private GradientDrawable roundedDrawable(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundedGradient(int[] colors, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                colors
        );
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private EditText editText(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setInputType(inputType);
        editText.setTextColor(COLOR_INK);
        editText.setHintTextColor(COLOR_MUTED);
        editText.setBackground(roundedDrawable(COLOR_SURFACE_VARIANT, 16));
        editText.setPadding(dp(14), dp(10), dp(14), dp(10));
        return editText;
    }

    private void styleChoiceButton(CompoundButton button) {
        button.setTextColor(COLOR_INK);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        button.setButtonTintList(new ColorStateList(
                states,
                new int[]{COLOR_PRIMARY, COLOR_MUTED}
        ));
    }

    private Spinner categorySpinner(String[] keys) {
        String[] labels = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            labels[i] = CategoryCatalog.label(keys[i]);
        }
        return spinner(labels);
    }

    private Spinner accountSpinner() {
        return spinner(new String[]{"手动", "现金", "微信", "支付宝", "云闪付", "银行卡"});
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                values
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(roundedDrawable(COLOR_SURFACE_VARIANT, 16));
        spinner.setPadding(dp(12), dp(8), dp(12), dp(8));
        spinner.setPopupBackgroundDrawable(roundedDrawable(COLOR_CARD, 18));
        return spinner;
    }

    private ScrollView wrapDialogForm(View view) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(view, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scrollView;
    }

    private LinearLayout.LayoutParams weightParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.weight = weight;
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46));
        params.weight = 1;
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(72),
                dp(38)
        );
        params.leftMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams calendarActionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(112),
                dp(42)
        );
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams weightedCompactParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38));
        params.weight = weight;
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        return params;
    }

    private LinearLayout.LayoutParams monthButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(38));
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams fullWidthParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = topMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String money(long cents) {
        long sign = cents < 0 ? -1 : 1;
        long value = Math.abs(cents);
        return "¥" + (sign < 0 ? "-" : "") + String.format(Locale.CHINA, "%d.%02d", value / 100, value % 100);
    }

    private static String directionName(String direction) {
        return LedgerEntry.DIRECTION_EXPENSE.equals(direction) ? "支出" : "收入";
    }

    private static String sourceName(String source) {
        if (SourceKey.WECHAT.equals(source)) {
            return "微信";
        }
        if (SourceKey.ALIPAY.equals(source)) {
            return "支付宝";
        }
        if (SourceKey.UNIONPAY.equals(source)) {
            return "云闪付";
        }
        if (SourceKey.ROOT_HOOK.equals(source)) {
            return "Root";
        }
        return "手动";
    }

    private static String rawTitle(RawCaptureRecord raw) {
        String title = raw.title == null ? "" : raw.title.trim();
        if (!title.isEmpty()) {
            return title;
        }
        if (raw.rawText == null || raw.rawText.isEmpty()) {
            return "置信度 " + Math.round(raw.confidence * 100) + "%";
        }
        return raw.rawText.length() > 70 ? raw.rawText.substring(0, 70) + "…" : raw.rawText;
    }
}
