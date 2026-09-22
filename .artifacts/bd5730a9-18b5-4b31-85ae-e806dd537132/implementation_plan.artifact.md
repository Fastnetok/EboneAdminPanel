# امپلیمنٹیشن پلان: ریپیٹ کمپلینٹ کی مکمل تفصیلات اور ترتیب

اس پلان کا مقصد ایمپلائی رپورٹس میں ریپیٹ کمپلینٹس کے سیکشن کو بہتر بنانا ہے تاکہ ایڈریس کے ساتھ ساتھ فون نمبر، یوزر آئی ڈی اور تاریخ کی مکمل تفصیلات ترتیب وار نظر آئیں۔

## مجوزہ تبدیلیاں

### [ایپ کمپوننٹ]

#### [ترمیم] [EmployeeReportDetailsActivity.kt](file:///C:/Users/Dell/AndroidStudioProjects/EboneAdminPanel/app/src/main/java/com/example/eboneadminpanel/EmployeeReportDetailsActivity.kt)
- `loadRepeatComplaints` فنکشن میں `phoneNumber` اور `createdTime` کو بھی فائر بیس سے حاصل کیا جائے گا۔
- ہر یوزر کی کمپلینٹس کو `createdTime` کے لحاظ سے ترتیب (Sort) دیا جائے گا تاکہ تاریخ وار ہسٹری نظر آئے۔

#### [ترمیم] [RepeatComplaintAdapter.kt](file:///C:/Users/Dell/AndroidStudioProjects/EboneAdminPanel/app/src/main/java/com/example/eboneadminpanel/RepeatComplaintAdapter.kt)
- اڈاپٹر میں نئے فیلڈز (Phone Number اور Date) کو ہینڈل کرنے کے لیے تبدیلیاں کی جائیں گی۔
- تاریخ کو پڑھنے کے قابل فارمیٹ (مثلاً dd/MM/yyyy) میں تبدیل کیا جائے گا۔

#### [ترمیم] [item_repeat_complaint.xml](file:///C:/Users/Dell/AndroidStudioProjects/EboneAdminPanel/app/src/main/res/layout/item_repeat_complaint.xml)
- لے آؤٹ میں فون نمبر اور تاریخ کے لیے نئے `TextView` شامل کیے جائیں گے۔
- کارڈ کے ڈیزائن کو بہتر بنایا جائے گا تاکہ تمام معلومات واضح نظر آئیں۔

## تصدیق کا منصوبہ

### مینوئل تصدیق
- ایمپلائی رپورٹ میں جا کر کسی ملازم کی ریپیٹ کمپلینٹ پر کلک کر کے چیک کیا جائے گا کہ:
    - کیا فون نمبر نظر آ رہا ہے؟
    - کیا کمپلینٹس تاریخ کے حساب سے ترتیب وار ہیں؟
    - کیا یوزر آئی ڈی اور ایڈریس درست طریقے سے ڈسپلے ہو رہے ہیں؟
