package com.autoledger.app.capture;

import com.autoledger.app.data.LedgerEntry;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CsvBillImporterTest {
    @Test
    public void parsesWechatPersonalCsvWithMetadataHeader() {
        String csv = "微信支付账单明细\n"
                + "微信昵称：[测试]\n"
                + "----------------------微信支付账单明细列表--------------------\n"
                + "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n"
                + "2024-03-08 10:10:10,商户消费,\"瑞幸咖啡(测试店)\",\"拿铁,大杯\",支出,9.90,零钱,支付成功,420000001,100000001,一杯拿铁\n"
                + "2024-03-08 11:11:11,商户消费,测试超市,日用品,支出,\"1,000.00\",银行卡,支付成功,420000002,100000002,\n"
                + "2024-03-08 12:12:12,交易关闭,测试超市,商品,支出,9.00,银行卡,交易关闭,420000003,100000003,\n";

        CsvBillImporter.ParseResult result = CsvBillImporter.parse(csv);

        assertEquals(SourceKey.WECHAT, result.sourceKey);
        assertEquals(2, result.rows.size());
        assertEquals(1, result.invalidRows);
        CsvBillImporter.BillRow first = result.rows.get(0);
        assertEquals(990L, first.amountCents);
        assertEquals(LedgerEntry.DIRECTION_EXPENSE, first.direction);
        assertEquals("瑞幸咖啡(测试店)", first.merchant);
        assertEquals(CategoryCatalog.FOOD, first.category);
        assertEquals("零钱", first.account);
        assertEquals("420000001", first.orderNo);
        assertTrue(first.occurredAt > 0);

        CsvBillImporter.BillRow second = result.rows.get(1);
        assertEquals(100000L, second.amountCents);
    }

    @Test
    public void parsesAlipayPersonalCsv() {
        String csv = "交易号,商户订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态\n"
                + "202400001,202400011,2024-03-08 12:00:00,2024-03-08 12:00:01,2024-03-08 12:00:02,网上购物,商户消费,蜜雪冰城,冰淇淋,6.00,支出,交易成功,0.00,0.00,,已支出\n"
                + "202400002,202400012,2024-03-09 13:00:00,2024-03-09 13:00:01,2024-03-09 13:00:02,退款,退款,原商家,原路退回,9.80,收入,退款成功,0.00,9.80,退款到账,已收入\n";

        CsvBillImporter.ParseResult result = CsvBillImporter.parse(csv);

        assertEquals(SourceKey.ALIPAY, result.sourceKey);
        assertEquals(2, result.rows.size());
        CsvBillImporter.BillRow first = result.rows.get(0);
        assertEquals(600L, first.amountCents);
        assertEquals(LedgerEntry.DIRECTION_EXPENSE, first.direction);
        assertEquals("蜜雪冰城", first.merchant);
        assertEquals(CategoryCatalog.FOOD, first.category);
        CsvBillImporter.BillRow second = result.rows.get(1);
        assertEquals(980L, second.amountCents);
        assertEquals(LedgerEntry.DIRECTION_INCOME, second.direction);
        assertEquals(CategoryCatalog.REFUND, second.category);
    }

    @Test
    public void keepsEmptyFilesUsefulForUi() {
        CsvBillImporter.ParseResult result = CsvBillImporter.parse("随便一个不是账单的文件\n");

        assertNotNull(result);
        assertTrue(result.sourceKey == null);
        assertTrue(result.rows.isEmpty());
        assertTrue(!result.errors.isEmpty());
    }

    @Test
    public void supportsNewerAlipayColumnNames() {
        String csv = "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注\n"
                + "2024-05-01 09:30:00,餐饮美食,饿了么,商家账号,午餐,支出,18.80,支付宝余额,交易成功,50000001,90000001,\n";

        CsvBillImporter.ParseResult result = CsvBillImporter.parse(csv);

        assertEquals(SourceKey.ALIPAY, result.sourceKey);
        assertEquals(1, result.rows.size());
        CsvBillImporter.BillRow row = result.rows.get(0);
        assertEquals(1880L, row.amountCents);
        assertEquals("饿了么", row.merchant);
    }

    @Test
    public void readsWechatPersonalXlsx() throws IOException {
        byte[] xlsx = buildWechatXlsx();

        CsvBillImporter.ParseResult result = CsvBillImporter.read(
                new ByteArrayInputStream(xlsx)
        );

        assertEquals(SourceKey.WECHAT, result.sourceKey);
        assertEquals(1, result.rows.size());
        CsvBillImporter.BillRow row = result.rows.get(0);
        assertEquals(600L, row.amountCents);
        assertEquals("蜜雪冰城", row.merchant);
        assertEquals(CategoryCatalog.FOOD, row.category);
        assertEquals("W202400001", row.orderNo);
        assertTrue(row.occurredAt > 0);
    }

    @Test
    public void acceptsExcelSerialTimeInWechatBill() {
        String csv = "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n"
                + "46273.6808912037,商户消费,微信账单测试店,商品,支出,6.00,零钱,支付成功,420000099,100000099,\n";

        CsvBillImporter.ParseResult result = CsvBillImporter.parse(csv);

        assertEquals(SourceKey.WECHAT, result.sourceKey);
        assertEquals(1, result.rows.size());
        assertTrue(result.rows.get(0).occurredAt > 0);
    }

    private static byte[] buildWechatXlsx() throws IOException {
        String shared = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<si><t>交易时间</t></si>"
                + "<si><t>交易类型</t></si>"
                + "<si><t>交易对方</t></si>"
                + "<si><t>商品</t></si>"
                + "<si><t>收/支</t></si>"
                + "<si><t>金额(元)</t></si>"
                + "<si><t>支付方式</t></si>"
                + "<si><t>当前状态</t></si>"
                + "<si><t>交易单号</t></si>"
                + "<si><t>商户单号</t></si>"
                + "<si><t>备注</t></si>"
                + "<si><t>2024-03-08 10:10:10</t></si>"
                + "<si><t>商户消费</t></si>"
                + "<si><t>蜜雪冰城</t></si>"
                + "<si><t>冰淇淋</t></si>"
                + "<si><t>支出</t></si>"
                + "<si><t>6.00</t></si>"
                + "<si><t>零钱</t></si>"
                + "<si><t>支付成功</t></si>"
                + "<si><t>W202400001</t></si>"
                + "<si><t>M202400001</t></si>"
                + "<si><t>测试备注</t></si>"
                + "</sst>";
        String sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData>"
                + "<row r=\"1\">"
                + "<c r=\"A1\" t=\"s\"><v>0</v></c>"
                + "<c r=\"B1\" t=\"s\"><v>1</v></c>"
                + "<c r=\"C1\" t=\"s\"><v>2</v></c>"
                + "<c r=\"D1\" t=\"s\"><v>3</v></c>"
                + "<c r=\"E1\" t=\"s\"><v>4</v></c>"
                + "<c r=\"F1\" t=\"s\"><v>5</v></c>"
                + "<c r=\"G1\" t=\"s\"><v>6</v></c>"
                + "<c r=\"H1\" t=\"s\"><v>7</v></c>"
                + "<c r=\"I1\" t=\"s\"><v>8</v></c>"
                + "<c r=\"J1\" t=\"s\"><v>9</v></c>"
                + "<c r=\"K1\" t=\"s\"><v>10</v></c>"
                + "</row>"
                + "<row r=\"2\">"
                + "<c r=\"A2\" t=\"s\"><v>11</v></c>"
                + "<c r=\"B2\" t=\"s\"><v>12</v></c>"
                + "<c r=\"C2\" t=\"s\"><v>13</v></c>"
                + "<c r=\"D2\" t=\"s\"><v>14</v></c>"
                + "<c r=\"E2\" t=\"s\"><v>15</v></c>"
                + "<c r=\"F2\" t=\"s\"><v>16</v></c>"
                + "<c r=\"G2\" t=\"s\"><v>17</v></c>"
                + "<c r=\"H2\" t=\"s\"><v>18</v></c>"
                + "<c r=\"I2\" t=\"s\"><v>19</v></c>"
                + "<c r=\"J2\" t=\"s\"><v>20</v></c>"
                + "<c r=\"K2\" t=\"s\"><v>21</v></c>"
                + "</row>"
                + "</sheetData>"
                + "</worksheet>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            putZip(zip, "xl/sharedStrings.xml", shared);
            putZip(zip, "xl/worksheets/sheet1.xml", sheet);
        }
        return out.toByteArray();
    }

    private static void putZip(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
