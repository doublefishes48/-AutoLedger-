package com.autoledger.app.capture;

public final class CategoryCatalog {
    public static final String FOOD = "FOOD";
    public static final String SHOPPING = "SHOPPING";
    public static final String TRANSPORT = "TRANSPORT";
    public static final String SUBSCRIPTION = "SUBSCRIPTION";
    public static final String ENTERTAINMENT = "ENTERTAINMENT";
    public static final String HOUSING = "HOUSING";
    public static final String MEDICAL = "MEDICAL";
    public static final String EDUCATION = "EDUCATION";
    public static final String COMMUNICATION = "COMMUNICATION";
    public static final String OTHER = "OTHER";
    public static final String SALARY = "SALARY";
    public static final String FINANCE = "FINANCE";
    public static final String REFUND = "REFUND";
    public static final String RED_PACKET = "RED_PACKET";
    public static final String TRANSFER = "TRANSFER";

    private CategoryCatalog() {
    }

    public static String[] expenseKeys() {
        return new String[]{
                FOOD, SHOPPING, TRANSPORT, SUBSCRIPTION, ENTERTAINMENT,
                HOUSING, MEDICAL, EDUCATION, COMMUNICATION, OTHER
        };
    }

    public static String[] incomeKeys() {
        return new String[]{
                SALARY, REFUND, FINANCE, RED_PACKET, TRANSFER, OTHER
        };
    }

    public static String label(String key) {
        switch (key == null ? OTHER : key) {
            case FOOD:
                return "餐饮";
            case SHOPPING:
                return "购物";
            case TRANSPORT:
                return "交通";
            case SUBSCRIPTION:
                return "订阅";
            case ENTERTAINMENT:
                return "娱乐";
            case HOUSING:
                return "住房";
            case MEDICAL:
                return "医疗";
            case EDUCATION:
                return "教育";
            case COMMUNICATION:
                return "通讯";
            case SALARY:
                return "工资";
            case FINANCE:
                return "理财";
            case REFUND:
                return "退款";
            case RED_PACKET:
                return "红包";
            case TRANSFER:
                return "转账";
            default:
                return "其他";
        }
    }
}

