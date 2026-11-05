# 🚀 QAutoEngine - شرح كامل للنظام

---

## 🧠 الفكرة العامة

السيستم ده عبارة عن **Automation Tool ذكي** مش بس بيشغل Test Cases
لا… ده بيشتغل كأنه "Tester حقيقي"

بيعمل:

1. يفتح الموقع
2. يكتشف اللينكات
3. يضغط على العناصر
4. يملأ الفورم
5. يتنقل بين الصفحات
6. يتعلم من اللي حصل
7. يحسن نفسه في الرن اللي بعده

يعني ببساطة:

👉 Crawl + Act + Learn + Optimize

---

# 🏗️ الهيكل العام

```
EngineOrchestrator (العقل المدبر)
        ↓
ScannerEngine (استكشاف)
        ↓
Actions (Click / Form / Navigation)
        ↓
DecisionEngine (يقرر يعمل ايه)
        ↓
LearningEngine (يتعلم)
        ↓
PathOptimizer (يحسن المسار)
        ↓
Validators (يتأكد من النتائج)
```

---

# 🧠 شرح الكلاسات الأساسية

---

## 1️⃣ EngineOrchestrator (المخ الرئيسي)

ده أهم كلاس في السيستم

### وظيفته:

* يشغل كل حاجة بالترتيب
* يقسم السيستم phases
* يتحكم في الـ flow

### بيعمل ايه؟

```java
scannerEngine.run(driver);
```

👉 يبدأ يجمع اللينكات

بعد كده:

```java
for (Action action : actions)
```

👉 يبدأ يشغل:

* Navigation
* Click
* Form

### كمان:

* فيه error handling
* فيه recovery لو حاجة وقعت

---

## 2️⃣ ScannerEngine (الاكتشاف)

### وظيفته:

* يلم كل اللينكات
* يجهز data لباقي السيستم

### بيعمل:

* Collect links
* Remove duplicates
* يحفظهم في memory

---

## 3️⃣ Actions Layer (التفاعل)

### 🔘 ClickAction

بيعمل:

* يجيب كل العناصر القابلة للضغط
* يرتبهم حسب الأهمية
* يمنع الضغط المتكرر
* يعمل safe click

💡 مثال:

* next → priority عالي
* cancel → priority قليل

---

### 📝 FormAction

بيعمل:

* يلاقي الفورمز
* يملأ inputs
* يعمل submit

💡 smart behavior:

* email → [test@example.com](mailto:test@example.com)
* phone → 01000000000

---

### 🌍 NavigationAction

بيعمل:

* يتنقل بين الصفحات
* يستخدم recursion (depth)

💡 controls:

* maxDepth
* maxVisits

💡 كمان:

* يمنع تكرار نفس اللينك
* ينضف اللينك (remove params & #)

---

## 4️⃣ ActionDecider (العقل البسيط)

### وظيفته:

يقرر:

❌ نضغط ولا لا
❌ نملأ الفورم ولا لا
❌ نروح اللينك ولا لا

### مثال:

```java
if (text.contains("delete")) return false;
```

👉 يمنع الحاجات الخطيرة

---

## 5️⃣ DecisionEngine (AI Level)

ده upgrade على ActionDecider

### بيستخدم:

* ScoreCalculator
* LearningEngine

### وظيفته:

* يدي score لكل element
* يقرر بناءً على score

---

## 6️⃣ ScoreCalculator

### وظيفته:

يدي تقييم لكل element

### مثال:

| Element | Score |
| ------- | ----- |
| next    | +50   |
| submit  | +40   |
| cancel  | -40   |
| close   | -50   |

👉 كل ما score أعلى → يتنفذ الأول

---

## 7️⃣ LearningEngine (التعلم)

### وظيفته:

السيستم يتعلم من نفسه

### بيخزن:

* العناصر اللي نجحت
* العناصر اللي فشلت

### مثال:

لو button عمل crash → يتمنع بعد كده

---

## 8️⃣ BehaviorStore (الذاكرة)

### وظيفته:

يحفظ:

* bad elements
* good elements

💡 ممكن بعد كده:

* نحطه في file
* أو database

---

## 9️⃣ PathOptimizer

### وظيفته:

يحسن ترتيب التنفيذ

بدل ما يمشي عشوائي:

👉 يمشي smart path

---

## 🔟 SessionState

### وظيفته:

يحفظ حالة السيشن

### بيخزن:

* URLs اللي زرناها
* elements اللي ضغطناها

👉 يمنع:

* loops
* التكرار

---

# 🕷️ Crawler Layer

## LinkCollector

### وظيفته:

* يجيب كل اللينكات من الصفحة

---

## LinkScannerTool

### وظيفته:

* يستخدم الـ collector
* يشغل scan

---

# ✅ Validators

## LinkValidator

* يفحص UI links

## ApiValidator

* يفحص APIs

---

# 📦 Models

## LinkResult

بيرجع:

* status code
* success / fail

---

# 🛠️ Utilities

## DriverFactory

بيعمل create للـ driver

## Waits

حل مشاكل الـ timing

## ElementActions

wrapper فوق selenium

---

# 🔥 Flow الحقيقي

```
Start
 ↓
Scan الموقع
 ↓
اجمع لينكات
 ↓
نفذ actions
 ↓
قرر ايه المهم
 ↓
اتعلم من النتائج
 ↓
حسن الأداء
 ↓
كرر العملية
```

---

# 💣 المشاكل اللي حليناها

✔ منع الخروج لمواقع خارجية (WhatsApp / Instagram)
✔ منع loops
✔ stability عالي
✔ retry + recovery
✔ smart click

---

# 🚀 احنا وصلنا لإيه؟

✔ Phase 1 → Crawling
✔ Phase 2 → Actions + Validation
✔ Phase 3 → AI + Learning (شغالين عليه)

---

# 😈 اللي جاي

* AI حقيقي (ML)
* save learning في DB
* parallel execution
* smart path prediction

---

# 👊 الخلاصة

السيستم ده:

❌ مش test tool عادي
✔ ده intelligent testing system

بيفكر… ويتعلم… ويتطور
