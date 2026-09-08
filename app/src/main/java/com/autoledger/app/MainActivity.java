package com.autoledger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.autoledger.app.capture.CategoryCatalog;
import com.autoledger.app.capture.CsvBillImporter;
import com.autoledger.app.capture.SourceKey;
import com.autoledger.app.data.LedgerEntry;
import com.autoledger.app.data.LedgerRepository;
import com.autoledger.app.data.RawCaptureRecord;
import com.autoledger.app.service.AccessibilityCaptureService;
import com.autoledger.app.service.KeepAliveService;
import com.autoledger.app.service.NotificationCaptureService;
import com.autoledger.app.service.ServiceGuard;
import com.autoledger.app.service.ShizukuSupport;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int COLOR_BACKGROUND = Color.rgb(246, 248, 247);
    private static final int COLOR_PRIMARY = Color.rgb(33, 102, 88);
    private static final int COLOR_PRIMARY_DARK = Color.rgb(21, 72, 63);
    private static final int COLOR_INK = Color.rgb(28, 39, 36);
    private static final int COLOR_MUTED = Color.rgb(99, 112, 107);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_BORDER = Color.rgb(226, 233, 229);
    private static final int COLOR_EXPENSE = Color.rgb(196, 75, 60);
    private static final int COLOR_INCOME = Color.rgb(39, 139, 98);
    private static final int COLOR_ACTION_IMPORT = Color.rgb(44, 103, 153);
    private static final int COLOR_ACTION_SETTINGS = Color.rgb(82, 97, 106);
    private static final int COLOR_ACTION_REFRESH = Color.rgb(109, 120, 116);
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
    private LedgerRepository repository;
    private LinearLayout content;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, result) -> {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST) {
                    return;
                }
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
        repository = LedgerRepository.get(this);
        ShizukuSupport.addPermissionResultListener(shizukuPermissionListener);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(30));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setContentView(scrollView);

        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(COLOR_PRIMARY);
            window.setNavigationBarColor(COLOR_BACKGROUND);
        }
        render();
        if (repository.isKeepAliveEnabled()) {
            KeepAliveService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        if (repository.isKeepAliveEnabled()) {
            KeepAliveService.start(this);
        }
    }

    @Override
    protected void onDestroy() {
        ShizukuSupport.removePermissionResultListener(shizukuPermissionListener);
        super.onDestroy();
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
        content.removeAllViews();

        content.addView(appHeader());
        content.addView(summaryPanel(), cardParams());
        content.addView(actionRow(), cardParams());
        if (renderPending()) {
            // Pending section was rendered above recent transactions.
        }
        content.addView(sectionTitle("最近流水"));
        renderTransactions();
    }

    private View appHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(0, dp(4), 0, dp(8));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("自动记账", 28, true, COLOR_INK);
        TextView subtitle = text("本地流水 · 不上传", 13, false, COLOR_MUTED);
        copy.addView(title);
        copy.addView(subtitle);
        header.addView(copy, weightParams(1));

        TextView month = text(monthLabel(), 12, true, COLOR_PRIMARY);
        month.setGravity(Gravity.CENTER);
        month.setTextColor(Color.WHITE);
        month.setBackground(roundedDrawable(COLOR_PRIMARY, 12));
        month.setPadding(dp(10), dp(5), dp(10), dp(5));
        header.addView(month);
        return header;
    }

    private String monthLabel() {
        return new SimpleDateFormat("M月", Locale.CHINA).format(new Date());
    }

    private View summaryPanel() {
        LedgerRepository.Summary summary = repository.loadSummary();
        long balance = summary.incomeCents - summary.expenseCents;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(roundedGradient(new int[]{
                COLOR_PRIMARY,
                COLOR_PRIMARY_DARK
        }, 8));

        LinearLayout captionRow = row();
        captionRow.addView(text("本月结余", 13, false, Color.rgb(214, 235, 227)));
        if (summary.pendingCount > 0) {
            captionRow.addView(pendingPill(summary.pendingCount));
        }
        panel.addView(captionRow);

        TextView balanceText = text(money(balance), 30, true, Color.WHITE);
        balanceText.setPadding(0, dp(2), 0, dp(8));
        panel.addView(balanceText);

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(70, 255, 255, 255));
        panel.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
        ));

        LinearLayout stats = row();
        stats.setPadding(0, dp(12), 0, 0);
        stats.addView(summaryStat("支出", summary.expenseCents, Color.rgb(255, 201, 184)), weightParams(1));
        stats.addView(summaryStat("收入", summary.incomeCents, Color.rgb(191, 235, 213)), weightParams(1));
        panel.addView(stats);
        return panel;
    }

    private View pendingPill(int count) {
        TextView pill = text("待确认 " + count, 11, true, Color.rgb(255, 218, 139));
        pill.setPadding(dp(8), dp(2), dp(8), dp(2));
        pill.setBackground(roundedDrawable(Color.argb(50, 255, 220, 130), 12));
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
        cell.addView(text(label, 12, false, Color.rgb(214, 235, 227)));
        cell.addView(text(money(cents), 16, true, tint));
        return cell;
    }

    private View actionRow() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(10));
        panel.setBackgroundColor(COLOR_CARD);

        Button manual = button("记一笔", COLOR_PRIMARY);
        Button importBill = button("导入账单", COLOR_ACTION_IMPORT);
        Button permissions = button("权限与来源", COLOR_ACTION_SETTINGS);
        Button refresh = button("刷新", COLOR_ACTION_REFRESH);
        manual.setOnClickListener(v -> showManualDialog());
        importBill.setOnClickListener(v -> showBillImportDialog());
        permissions.setOnClickListener(v -> showSettingsDialog());
        refresh.setOnClickListener(v -> render());

        LinearLayout firstRow = row();
        firstRow.setPadding(0, 0, 0, dp(6));
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
            Button ignore = button("忽略", COLOR_ACTION_REFRESH);
            confirm.setOnClickListener(v -> {
                repository.confirmRawCapture(raw.id);
                render();
            });
            ignore.setOnClickListener(v -> {
                repository.ignoreRawCapture(raw.id);
                render();
            });
            buttons.addView(confirm, weightParams(1));
            buttons.addView(ignore, weightParams(1));
            card.addView(buttons);
            content.addView(card, cardParams());
        }
        return true;
    }

    private void renderTransactions() {
        List<LedgerEntry> entries = repository.recentTransactions(60);
        if (entries.isEmpty()) {
            content.addView(emptyState("还没有流水", "记一笔、等待通知，或导入账单后会自动出现"), cardParams());
            return;
        }

        for (LedgerEntry entry : entries) {
            LinearLayout card = flowCard();
            card.setOnClickListener(v -> showTransactionDialog(entry));

            LinearLayout top = row();
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            String prefix = entry.isExpense() ? "-" : "+";
            String merchantLabel = entry.merchant == null || entry.merchant.isEmpty()
                    ? "自动记录"
                    : entry.merchant;
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
                    CategoryCatalog.label(entry.category)
                            + " · " + dateFormat.format(new Date(entry.occurredAt))
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
        RadioButton income = new RadioButton(this);
        income.setId(View.generateViewId());
        income.setText("收入");
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
        dialog.show();
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

        new AlertDialog.Builder(this)
                .setTitle("导入账单 Excel/CSV")
                .setView(wrapDialogForm(form))
                .setPositiveButton("选择文件", (dialog, which) -> openBillCsvPicker())
                .setNegativeButton("取消", null)
                .show();
    }

    private void openBillCsvPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, BILL_IMPORT_REQUEST);
        } catch (Exception ignored) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void importBillCsv(Uri uri) {
        final AlertDialog loading = new AlertDialog.Builder(this)
                .setMessage("正在读取并导入，请稍候")
                .setCancelable(false)
                .create();
        loading.show();
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

        new AlertDialog.Builder(this)
                .setTitle("导入结果")
                .setMessage(message.toString())
                .setPositiveButton("完成", null)
                .show();
    }

    private void showSettingsDialog() {
        LinearLayout form = formContainer();
        form.addView(titleText("抓取与权限", 20, true, COLOR_INK));
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
        boolean shizukuReady = ShizukuSupport.isPermissionGranted()
                || ShizukuSupport.canWriteSecureSettings(this);
        Button shizuku = button("授权 Shizuku 自愈", COLOR_PRIMARY);
        shizuku.setOnClickListener(v -> showShizukuSetup());
        CheckBox rootHook = new CheckBox(this);
        rootHook.setText("允许未来 Root Hook 广播（默认关）");
        rootHook.setChecked(repository.isSourceEnabled(SourceKey.ROOT_HOOK));

        form.addView(wechat);
        form.addView(alipay);
        form.addView(unionpay);
        form.addView(autoConfirm);
        form.addView(keepAlive);
        form.addView(shizuku);
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
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            repository.setSourceEnabled(SourceKey.WECHAT, wechat.isChecked());
            repository.setSourceEnabled(SourceKey.ALIPAY, alipay.isChecked());
            repository.setSourceEnabled(SourceKey.UNIONPAY, unionpay.isChecked());
            repository.setAutoConfirmEnabled(autoConfirm.isChecked());
            boolean keepAliveEnabled = keepAlive.isChecked();
            repository.setKeepAliveEnabled(keepAliveEnabled);
            if (keepAliveEnabled) {
                KeepAliveService.start(this);
            } else {
                KeepAliveService.stop(this);
            }
            repository.setSourceEnabled(SourceKey.ROOT_HOOK, rootHook.isChecked());
            dialog.dismiss();
            render();
        });
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
        ShizukuSupport.requestPermission(SHIZUKU_PERMISSION_REQUEST);
    }

    private void showTransactionDialog(LedgerEntry entry) {
        String detail = money(entry.amountCents)
                + "\n" + directionName(entry.direction)
                + " · " + CategoryCatalog.label(entry.category)
                + "\n" + (entry.merchant == null || entry.merchant.isEmpty() ? "无商户" : entry.merchant)
                + "\n" + dateFormat.format(new Date(entry.occurredAt))
                + "\n来源：" + sourceName(entry.sourceKey);
        if (entry.note != null && !entry.note.isEmpty()) {
            detail += "\n备注：" + entry.note;
        }

        new AlertDialog.Builder(this)
                .setTitle("流水详情")
                .setMessage(detail)
                .setPositiveButton("改分类", (dialog, which) -> showCategoryDialog(entry))
                .setNeutralButton("删除", (dialog, which) -> {
                    repository.deleteTransaction(entry.id);
                    render();
                })
                .setNegativeButton("关闭", null)
                .show();
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

        new AlertDialog.Builder(this)
                .setTitle("选择分类")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    repository.updateTransactionCategory(entry.id, keys[which]);
                    dialog.dismiss();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openSettings(Class<?> service, boolean notificationListener) {
        try {
            if (notificationListener) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } else {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        } catch (Exception ignored) {
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

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout flowCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(11));
        card.setBackground(roundedDrawable(COLOR_CARD, 8));
        return card;
    }

    private LinearLayout formContainer() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(8), dp(8), dp(8));
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
        button.setTextColor(Color.WHITE);
        button.setBackground(roundedDrawable(color, 8));
        button.setElevation(0);
        button.setMinHeight(0);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
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
        return editText;
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

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
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
