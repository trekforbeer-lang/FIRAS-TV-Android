# بناء FIRAS TV APK من GitHub (هاتف فقط)

## 1) رفع الملفات
- فك ضغط ZIP.
- ارفع **محتويات المجلد** إلى جذر المستودع `FIRAS-TV-Android` (لا ترفع مجلد FIRAS_TV_Android كطبقة إضافية).
- تأكد أن `app/` و`build.gradle` و`settings.gradle` و`.github/workflows/android-build.yml` تظهر في الصفحة الرئيسية للمستودع.

## 2) بدء البناء
- افتح تبويب **Actions**.
- اختر **Build FIRAS TV APK**.
- اضغط **Run workflow** واختر الفرع ثم أكد التشغيل.
- البناء يبدأ كذلك تلقائياً عند push إلى `main` أو `master` وعند Pull Request.

## 3) تنزيل APK
- افتح تشغيل Workflow بعد اكتماله بنجاح (علامة خضراء).
- انزل إلى **Artifacts**.
- نزّل `FIRAS-TV-2.1.1-debug-apk` وفك ضغطه.
- ثبت `app-debug.apk` على جهاز Android مع السماح بالتثبيت من هذا المصدر عند الطلب.

## ملاحظات
- هذا Debug APK للتجربة، وليس إصدار Release موقّع للنشر.
- يتطلب البناء Android SDK platform 35 وJava 17؛ GitHub Actions يجهز Gradle ويستخدم بيئة Ubuntu.
- لا تضع مفاتيح التوقيع أو كلمات المرور في المستودع.
