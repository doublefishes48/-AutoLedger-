package com.autoledger.app.capture;

import com.autoledger.app.data.LedgerEntry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Pure-Java reader for personal WeChat / Alipay CSV bills.
 *
 * <p>The official files usually contain metadata rows above the header and may be
 * encoded as UTF-8 or GB18030/GBK, so this reader scans for the header instead of
 * assuming the first line is the table header.</p>
 */
public final class CsvBillImporter {
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ERRORS = 100;
    private static final Pattern DATE_TIME_PATTERN = Pattern.compile(
            "(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?"
                    + "(?:\\s+(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?)?"
    );

    private CsvBillImporter() {
    }

    public static final class BillRow {
        public final String sourceKey;
        public final long amountCents;
        public final String direction;
        public final String category;
        public final String merchant;
        public final long occurredAt;
        public final String account;
        public final String note;
        public final String orderNo;

        private BillRow(
                String sourceKey,
                long amountCents,
                String direction,
                String category,
                String merchant,
                long occurredAt,
                String account,
                String note,
                String orderNo
        ) {
            this.sourceKey = sourceKey;
            this.amountCents = amountCents;
            this.direction = direction;
            this.category = category;
            this.merchant = merchant;
            this.occurredAt = occurredAt;
            this.account = account;
            this.note = note;
            this.orderNo = orderNo;
        }
    }

    public static final class ParseResult {
        public final String sourceKey;
        public final String sourceName;
        public final List<BillRow> rows;
        public final int invalidRows;
        public final List<String> errors;

        private ParseResult(
                String sourceKey,
                String sourceName,
                List<BillRow> rows,
                int invalidRows,
                List<String> errors
        ) {
            this.sourceKey = sourceKey;
            this.sourceName = sourceName;
            this.rows = rows;
            this.invalidRows = invalidRows;
            this.errors = errors;
        }
    }

    public static ParseResult parse(String csvText) {
        if (csvText == null || csvText.trim().isEmpty()) {
            return new ParseResult(null, null, new ArrayList<>(), 0, error("文件是空的"));
        }
        return parseRows(readCsv(csvText));
    }

    public static ParseResult read(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("无法打开文件");
        }
        byte[] bytes = readBytes(input);
        if (startsWith(bytes, (byte) 'P', (byte) 'K')) {
            return parseXlsx(bytes);
        }
        if (startsWith(bytes, (byte) '%', (byte) 'P', (byte) 'D', (byte) 'F')) {
            return new ParseResult(
                    null,
                    null,
                    new ArrayList<>(),
                    0,
                    error("PDF 不能导入。微信里请选“用于个人对账”的 Excel/CSV，不要选“用作证明材料”的 PDF。")
            );
        }
        String head = new String(
                bytes,
                0,
                Math.min(200, bytes.length),
                StandardCharsets.UTF_8
        ).trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            return new ParseResult(
                    null,
                    null,
                    new ArrayList<>(),
                    0,
                    error("这个“xlsx”其实是网页文件，不是真正账单。请在微信账单下载结果里直接打开/保存实际文件，不要用浏览器另存为 xlsx。")
            );
        }
        return parse(decode(bytes));
    }

    private static ParseResult parseRows(List<List<String>> rows) {
        Header header = findHeader(rows);
        if (header == null || header.sourceKey == null) {
            return new ParseResult(
                    null,
                    null,
                    new ArrayList<>(),
                    0,
                    error("没认出是微信或支付宝账单。请选择带“交易时间/收-支/金额”表头的 Excel 或 CSV 文件。")
            );
        }

        List<BillRow> billRows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int invalid = 0;
        for (int rowIndex = header.rowIndex + 1; rowIndex < rows.size(); rowIndex++) {
            List<String> raw = rows.get(rowIndex);
            BillRow billRow = parseRow(header, raw, rowIndex + 1, errors);
            if (billRow == null) {
                invalid++;
            } else {
                billRows.add(billRow);
            }
        }
        return new ParseResult(
                header.sourceKey,
                sourceName(header.sourceKey),
                billRows,
                invalid,
                errors
        );
    }

    private static ParseResult parseXlsx(byte[] bytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (name.equals("xl/sharedStrings.xml")
                        || name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
                    entries.put(name, readBytes(zip));
                }
            }
        } catch (Exception ignored) {
            return new ParseResult(
                    null,
                    null,
                    new ArrayList<>(),
                    0,
                    error("无法读取 Excel 文件，请确认它没有加密或损坏。")
            );
        }

        byte[] sharedBytes = entries.get("xl/sharedStrings.xml");
        List<String> sharedStrings = sharedBytes == null
                ? new ArrayList<>()
                : readSharedStrings(sharedBytes);
        ParseResult best = null;
        try {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                String name = item.getKey();
                if (!name.startsWith("xl/worksheets/") || !name.endsWith(".xml")) {
                    continue;
                }
                List<List<String>> rows = extractXlsxRows(item.getValue(), sharedStrings);
                ParseResult candidate = parseRows(rows);
                if (candidate.sourceKey != null
                        && (best == null || candidate.rows.size() > best.rows.size())) {
                    best = candidate;
                }
            }
        } catch (Exception ignored) {
            return new ParseResult(
                    null,
                    null,
                    new ArrayList<>(),
                    0,
                    error("Excel 内容结构无法识别，请重新下载或另存为 CSV 后再试。")
            );
        }
        if (best == null) {
            return new ParseResult(
                    null,
                    null,
                    new ArrayList<>(),
                    0,
                    error("Excel 里没找到微信或支付宝流水表。个人对账文件通常会带“交易时间/金额/收-支”表头。")
            );
        }
        return best;
    }

    private static List<String> readSharedStrings(byte[] bytes) {
        try {
            Document document = parseXml(bytes);
            List<String> values = new ArrayList<>();
            NodeList items = document.getElementsByTagName("si");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                StringBuilder builder = new StringBuilder();
                NodeList texts = item.getElementsByTagName("t");
                for (int j = 0; j < texts.getLength(); j++) {
                    builder.append(texts.item(j).getTextContent());
                }
                values.add(builder.toString());
            }
            return values;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static List<List<String>> extractXlsxRows(
            byte[] sheetBytes,
            List<String> sharedStrings
    ) throws Exception {
        Document document = parseXml(sheetBytes);
        List<List<String>> rows = new ArrayList<>();
        NodeList rowNodes = document.getElementsByTagName("row");
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element row = (Element) rowNodes.item(i);
            NodeList cells = row.getElementsByTagName("c");
            List<String> values = new ArrayList<>();
            for (int j = 0; j < cells.getLength(); j++) {
                Element cell = (Element) cells.item(j);
                int column = cellColumnIndex(cell.getAttribute("r"), values.size());
                while (values.size() <= column) {
                    values.add("");
                }
                values.set(column, xlsxCellValue(cell, sharedStrings));
            }
            while (!values.isEmpty() && values.get(values.size() - 1).isEmpty()) {
                values.remove(values.size() - 1);
            }
            rows.add(values);
        }
        return rows;
    }

    private static String xlsxCellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            StringBuilder builder = new StringBuilder();
            NodeList texts = cell.getElementsByTagName("t");
            for (int i = 0; i < texts.getLength(); i++) {
                builder.append(texts.item(i).getTextContent());
            }
            return builder.toString();
        }
        NodeList values = cell.getElementsByTagName("v");
        if (values.getLength() == 0) {
            return "";
        }
        String value = values.item(0).getTextContent().trim();
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(value);
                if (index >= 0 && index < sharedStrings.size()) {
                    return sharedStrings.get(index);
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the raw shared string index for diagnostics.
            }
        }
        return value;
    }

    private static int cellColumnIndex(String reference, int fallback) {
        if (reference == null || reference.isEmpty()) {
            return fallback;
        }
        int index = 0;
        for (int i = 0; i < reference.length(); i++) {
            char c = Character.toUpperCase(reference.charAt(i));
            if (c < 'A' || c > 'Z') {
                break;
            }
            index = index * 26 + (c - 'A' + 1);
        }
        return index == 0 ? fallback : index - 1;
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // Some Android/JVM parsers do not expose Apache features.
        }
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > MAX_FILE_BYTES) {
                throw new IOException("CSV 文件太大，请先缩短导出时间范围");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (startsWith(bytes, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, (byte) 0xFF, (byte) 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, (byte) 0xFE, (byte) 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        try {
            Charset gb18030 = Charset.forName("GB18030");
            String gbk = decodeStrict(bytes, gb18030);
            return gbk == null ? new String(bytes, Charset.forName("GBK")) : gbk;
        } catch (Exception ignored) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException ignored) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, byte... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static List<List<String>> readCsv(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = true;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (c == '"' && fieldStarted) {
                inQuotes = true;
                fieldStarted = false;
                continue;
            }
            if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = true;
                continue;
            }
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                fieldStarted = true;
                continue;
            }
            field.append(c);
            if (!Character.isWhitespace(c) || field.length() == 0) {
                fieldStarted = false;
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static Header findHeader(List<List<String>> rows) {
        Header best = null;
        int bestScore = 0;
        for (int i = 0; i < rows.size(); i++) {
            List<String> columns = rows.get(i);
            Header header = Header.from(columns, i);
            if (header.sourceKey == null
                    || header.timeIndex < 0
                    || header.directionIndex < 0
                    || header.amountIndex < 0) {
                continue;
            }
            if (header.recognitionScore > bestScore) {
                best = header;
                bestScore = header.recognitionScore;
            }
        }
        return best;
    }

    private static BillRow parseRow(
            Header header,
            List<String> raw,
            int rowNumber,
            List<String> errors
    ) {
        String timeText = value(raw, header.timeIndex);
        String amountText = value(raw, header.amountIndex);
        String directionText = value(raw, header.directionIndex);
        Long occurredAt = parseDateTime(timeText);
        if (occurredAt == null) {
            addError(errors, rowNumber, "无法解析交易时间：" + timeText);
            return null;
        }

        Long amountCents = parseAmount(amountText);
        if (amountCents == null || amountCents <= 0) {
            addError(errors, rowNumber, "金额无效：" + amountText);
            return null;
        }
        boolean amountNegative = isNegativeAmount(amountText);
        String type = value(raw, header.typeIndex);
        String status = value(raw, header.statusIndex);
        if (isClosedOrFailed(status)) {
            addError(errors, rowNumber, "跳过未成交记录：" + status);
            return null;
        }

        String direction = detectDirection(directionText, type, status, amountNegative);
        if (direction == null) {
            addError(errors, rowNumber, "无法判断收支方向：" + directionText);
            return null;
        }

        String counterparty = value(raw, header.counterpartyIndex);
        String product = value(raw, header.productIndex);
        String merchant = merchantFor(counterparty, product);
        String paymentMethod = value(raw, header.paymentIndex);
        String account = accountFor(header.sourceKey, paymentMethod);
        String orderNo = value(raw, header.orderIndex);
        String remark = value(raw, header.remarkIndex);
        String classifyText = String.join(
                " ",
                type,
                counterparty,
                product,
                status,
                remark
        );
        String category = PaymentTextParser.classify(classifyText, merchant, direction);

        return new BillRow(
                header.sourceKey,
                amountCents,
                direction,
                category,
                merchant,
                occurredAt,
                account,
                remark,
                orderNo
        );
    }

    private static void addError(
            List<String> errors,
            int rowNumber,
            String message
    ) {
        if (errors.size() >= MAX_ERRORS) {
            return;
        }
        errors.add("第 " + rowNumber + " 行：" + message);
    }

    private static String value(List<String> row, int index) {
        if (row == null || index < 0 || index >= row.size()) {
            return "";
        }
        String value = row.get(index);
        return value == null ? "" : value.trim();
    }

    private static Long parseDateTime(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.matches("\\d{4,6}(?:\\.\\d+)?")) {
            try {
                double serial = Double.parseDouble(trimmed);
                if (serial >= 20000 && serial <= 80000) {
                    return excelSerialToMillis(serial);
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Matcher matcher = DATE_TIME_PATTERN.matcher(trimmed);
        if (!matcher.find()) {
            return null;
        }
        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            int hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
            int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
            int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
            Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
            calendar.clear();
            calendar.set(year, month - 1, day, hour, minute, second);
            return calendar.getTimeInMillis();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long excelSerialToMillis(double serial) {
        Calendar excelEpoch = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
        excelEpoch.clear();
        excelEpoch.set(1899, Calendar.DECEMBER, 30, 0, 0, 0);
        return excelEpoch.getTimeInMillis()
                + Math.round(serial * 86400000.0);
    }

    private static Long parseAmount(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String normalized = text.trim()
                .replace("¥", "")
                .replace("￥", "")
                .replace("元", "")
                .replace(" ", "")
                .replace(",", "");
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            double yuan = Math.abs(Double.parseDouble(normalized));
            long cents = Math.round(yuan * 100.0);
            return cents <= 0 ? null : cents;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isNegativeAmount(String text) {
        if (text == null) {
            return false;
        }
        String cleaned = text.trim().replace("¥", "").replace("￥", "");
        return cleaned.startsWith("-");
    }

    private static String detectDirection(
            String directionText,
            String type,
            String status,
            boolean amountNegative
    ) {
        String joined = directionText + " " + type + " " + status;
        if (containsAny(joined, "退款", "退货", "原路退回")) {
            return LedgerEntry.DIRECTION_INCOME;
        }
        if (containsAny(directionText, "收入", "收款", "到账", "转入")) {
            return LedgerEntry.DIRECTION_INCOME;
        }
        if (containsAny(directionText, "支出")) {
            return LedgerEntry.DIRECTION_EXPENSE;
        }
        if (containsAny(type, "退款", "收入", "收款", "收到")) {
            return LedgerEntry.DIRECTION_INCOME;
        }
        if (containsAny(type, "支出", "付款", "消费", "购买", "充值")) {
            return LedgerEntry.DIRECTION_EXPENSE;
        }
        if (amountNegative) {
            return LedgerEntry.DIRECTION_EXPENSE;
        }
        return null;
    }

    private static boolean isClosedOrFailed(String status) {
        if (status == null || status.trim().isEmpty()) {
            return false;
        }
        return containsAny(
                status,
                "交易关闭",
                "已关闭",
                "未支付",
                "超时关闭",
                "交易失败",
                "支付失败",
                "已取消",
                "交易取消"
        );
    }

    private static String merchantFor(String counterparty, String product) {
        String counterpartyClean = cleanText(counterparty);
        String productClean = cleanText(product);
        if (!isGenericCounterparty(counterpartyClean)) {
            return counterpartyClean;
        }
        if (!isGenericCounterparty(productClean)) {
            return productClean;
        }
        return "";
    }

    private static boolean isGenericCounterparty(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return containsAny(
                value,
                "微信团队",
                "财付通",
                "零钱",
                "零钱通",
                "理财通",
                "微信用户",
                "支付宝",
                "余额宝",
                "银行卡",
                "银行",
                "云闪付",
                "银联",
                "平台商户",
                "商户消费"
        );
    }

    private static String cleanText(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private static String accountFor(String sourceKey, String paymentMethod) {
        String account = cleanText(paymentMethod);
        if (!account.isEmpty() && !account.startsWith("订单")) {
            return account;
        }
        return sourceName(sourceKey);
    }

    private static String sourceName(String sourceKey) {
        if (SourceKey.WECHAT.equals(sourceKey)) {
            return "微信";
        }
        if (SourceKey.ALIPAY.equals(sourceKey)) {
            return "支付宝";
        }
        if (SourceKey.UNIONPAY.equals(sourceKey)) {
            return "云闪付";
        }
        return "账单";
    }

    private static List<String> error(String message) {
        List<String> errors = new ArrayList<>();
        errors.add(message);
        return errors;
    }

    private static boolean containsAny(String text, String... words) {
        if (text == null) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String compactHeader(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replace("（", "(")
                .replace("）", ")")
                .replace(" ", "")
                .replace("\u00A0", "")
                .toLowerCase(Locale.ROOT);
    }

    private static final class Header {
        private final String sourceKey;
        private final int rowIndex;
        private final int timeIndex;
        private final int directionIndex;
        private final int amountIndex;
        private final int typeIndex;
        private final int counterpartyIndex;
        private final int productIndex;
        private final int paymentIndex;
        private final int statusIndex;
        private final int orderIndex;
        private final int remarkIndex;
        private final int recognitionScore;

        private Header(
                String sourceKey,
                int rowIndex,
                int timeIndex,
                int directionIndex,
                int amountIndex,
                int typeIndex,
                int counterpartyIndex,
                int productIndex,
                int paymentIndex,
                int statusIndex,
                int orderIndex,
                int remarkIndex,
                int recognitionScore
        ) {
            this.sourceKey = sourceKey;
            this.rowIndex = rowIndex;
            this.timeIndex = timeIndex;
            this.directionIndex = directionIndex;
            this.amountIndex = amountIndex;
            this.typeIndex = typeIndex;
            this.counterpartyIndex = counterpartyIndex;
            this.productIndex = productIndex;
            this.paymentIndex = paymentIndex;
            this.statusIndex = statusIndex;
            this.orderIndex = orderIndex;
            this.remarkIndex = remarkIndex;
            this.recognitionScore = recognitionScore;
        }

        private static Header from(List<String> columns, int rowIndex) {
            if (columns == null || columns.isEmpty()) {
                return new Header(null, rowIndex, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0);
            }
            int time = findColumn(columns, "交易创建时间", "交易时间", "付款时间");
            int direction = findColumnContaining(columns, "收/支", "收支", "收入/支出");
            int amount = findAmountColumn(columns);
            int type = findColumnContaining(columns, "交易类型", "交易分类", "类型");
            int counterparty = findColumnContaining(columns, "交易对方", "对方", "收款方", "付款方");
            int product = findColumnContaining(columns, "商品名称", "商品说明", "商品");
            int payment = findColumnContaining(columns, "收/付款方式", "支付方式", "付款方式");
            int status = findColumnContaining(columns, "当前状态", "交易状态");
            int order = findColumnContaining(columns, "交易单号", "交易订单号", "交易号", "商户订单号");
            int remark = findColumnContaining(columns, "备注");
            String sourceKey = detectSourceKey(columns, status, type, order);
            int score = recognitionScore(columns, sourceKey, time, direction, amount, type, counterparty,
                    product, payment, status, order);
            return new Header(sourceKey, rowIndex, time, direction, amount, type, counterparty, product,
                    payment, status, order, remark, score);
        }

        private static int findColumn(List<String> columns, String... names) {
            for (String name : names) {
                for (int i = 0; i < columns.size(); i++) {
                    String compact = compactHeader(columns.get(i));
                    if (compact.equals(name) || compact.contains(name)) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static int findColumnContaining(List<String> columns, String... names) {
            for (String name : names) {
                for (int i = 0; i < columns.size(); i++) {
                    if (compactHeader(columns.get(i)).contains(name)) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static int findAmountColumn(List<String> columns) {
            for (int i = 0; i < columns.size(); i++) {
                String compact = compactHeader(columns.get(i));
                if (compact.equals("金额")
                        || compact.startsWith("金额(")
                        || compact.equals("金额(元)")
                        || compact.equals("交易金额")) {
                    return i;
                }
            }
            return -1;
        }

        private static String detectSourceKey(
                List<String> columns,
                int status,
                int type,
                int order
        ) {
            boolean currentStatus = status >= 0 && compactHeader(columns.get(status)).contains("当前状态");
            boolean tradeStatus = status >= 0 && compactHeader(columns.get(status)).contains("交易状态");
            boolean wechatType = type >= 0 && compactHeader(columns.get(type)).contains("交易类型");
            boolean alipayType = type >= 0
                    && (compactHeader(columns.get(type)).contains("交易分类")
                    || compactHeader(columns.get(type)).equals("类型"));
            boolean hasSingleOrder = order >= 0 && compactHeader(columns.get(order)).contains("交易单号");
            boolean hasAlipayOrder = order >= 0
                    && (compactHeader(columns.get(order)).contains("交易订单号")
                    || compactHeader(columns.get(order)).contains("交易号"));

            int wechatScore = 0;
            if (currentStatus) {
                wechatScore += 4;
            }
            if (hasSingleOrder) {
                wechatScore += 4;
            }
            if (wechatType) {
                wechatScore += 2;
            }

            int alipayScore = 0;
            if (tradeStatus) {
                alipayScore += 4;
            }
            if (hasAlipayOrder) {
                alipayScore += 4;
            }
            if (alipayType) {
                alipayScore += 2;
            }

            if (wechatScore > 0 && wechatScore >= alipayScore) {
                return SourceKey.WECHAT;
            }
            if (alipayScore > 0) {
                return SourceKey.ALIPAY;
            }
            return null;
        }

        private static int recognitionScore(
                List<String> columns,
                String sourceKey,
                int time,
                int direction,
                int amount,
                int type,
                int counterparty,
                int product,
                int payment,
                int status,
                int order
        ) {
            int score = 3;
            score += time >= 0 ? 1 : 0;
            score += direction >= 0 ? 1 : 0;
            score += amount >= 0 ? 1 : 0;
            score += type >= 0 ? 1 : 0;
            score += counterparty >= 0 ? 1 : 0;
            score += product >= 0 ? 1 : 0;
            score += payment >= 0 ? 1 : 0;
            score += status >= 0 ? 1 : 0;
            score += order >= 0 ? 1 : 0;
            if (SourceKey.WECHAT.equals(sourceKey)) {
                score += 8;
            } else if (SourceKey.ALIPAY.equals(sourceKey)) {
                score += 8;
            }
            return score;
        }
    }
}
