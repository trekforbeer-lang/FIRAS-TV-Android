# FIRAS TV v4.0.0-universal

مشروع Android أصلي بلغة Kotlin، موجه للهواتف ذات الشاشات اللمسية. يستخدم Media3/ExoPlayer لتشغيل البث، مع واجهة عربية RTL ووضع أفقي وملء الشاشة وخيارات تكبير الصورة.

## البناء

يُبنى المشروع عبر GitHub Actions من الفرع `kotlin-v3` باستخدام Java 17 وGradle 8.7. بعد نجاح التشغيل ستجد ملفات APK في Artifacts باسم `FIRAS-TV-APK-v4-admin`، كما يُنشأ GitHub Release يحتوي على `app-release.apk للتنزيل المباشر.

## ملاحظات

- `minSdk = 21`.
- `applicationId = com.firas.tv`.
- نسخة Release موقعة بمفتاح Android debug للاختبار والتثبيت؛ ليست مفتاح نشر نهائياً.
- يجب اختبار APK على الجهاز قبل اعتباره إصداراً مستقراً.
