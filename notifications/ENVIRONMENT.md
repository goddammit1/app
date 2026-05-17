# Development environment

Версии и зависимости, на которых собирается этот проект. Этот файл —
канонический источник правды; в README есть только короткая выжимка.

> Если что-то из этого у тебя не совпадает — сборка с большой
> вероятностью упадёт. Совпадение **минорной** версии (например
> `17.0.x` вместо `17.0.3`) обычно ОК, но major (Java 17 vs 21, Gradle
> 9 vs 8) — нет.

---

## 1. Системные требования

| Что | Версия | Зачем |
|-----|--------|-------|
| **JDK** | 17 (Temurin / Oracle / Microsoft Build) | Gradle 9 + AGP 9 требуют Java 17. Java 21 пока **не поддерживается** AGP в этом проекте, Java 11 — слишком старая. |
| **Android SDK Platform** | API 34 (Android 14) | `compileSdk = 34`, `targetSdk = 34`. |
| **Android SDK Build-Tools** | 34.0.0+ | Подтянутся автоматически при первой сборке. |
| **Android Studio** | Hedgehog 2023.1 или новее (рекомендуется Iguana/Jellyfish/Koala) | Старые версии не понимают AGP 9. |
| **git** | любая 2.x | Для `clone` / `pull` / `push`. |
| **Минимальное устройство** | Android 7.0 (API 24) | `minSdk = 24`. |

### Где взять JDK 17

- **Windows:** [Temurin 17 (LTS)](https://adoptium.net/temurin/releases/?version=17) (MSI installer ставит и `JAVA_HOME`).
- **macOS:** `brew install --cask temurin@17`.
- **Linux (Ubuntu/Debian):** `sudo apt install temurin-17-jdk` (предварительно добавив Adoptium repo) или `sudo apt install openjdk-17-jdk`.

После установки проверь:

```bash
java -version
# должно вывести что-то вида:
# openjdk version "17.0.10" 2024-01-16
```

### ?? JDK путь в `gradle.properties`

В `gradle.properties` сейчас захардкожен мой локальный путь:

```properties
org.gradle.java.home=C:/Program Files/Java/jdk-17.0.3
```

Это **сломает сборку у кого угодно, кроме меня**. У соавтора два варианта:

1. **(рекомендую) Закомментировать или удалить строку**
   `org.gradle.java.home=...` в `gradle.properties` локально и не
   коммитить это изменение. Тогда Gradle возьмёт JDK из переменной
   окружения `JAVA_HOME` или из настроек Android Studio. Чтобы git
   не показывал эту строку как изменённую:
   ```bash
   git update-index --skip-worktree gradle.properties
   ```
2. **(если первый вариант не вариант)** Поправить путь под себя.
   Например, для Linux/macOS: `org.gradle.java.home=/usr/lib/jvm/temurin-17-jdk`.

В будущем я планирую вынести этот путь в `local.properties` (он в `.gitignore` и не уезжает в репо), но пока — так.

---

## 2. Gradle и Android Gradle Plugin

| Что | Версия | Где задано |
|-----|--------|------------|
| Gradle Wrapper | **9.3.1** | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin (AGP) | **9.1.1** | `build.gradle` (root) |
| Google Services plugin | **4.4.1** | `build.gradle` (root) |
| Foojay toolchains resolver | **0.10.0** | `settings.gradle` |

**Не запускай `gradle ...` напрямую** — у разработчика на машине может
быть установлена не та версия. Всегда через wrapper:

```bash
# Windows (PowerShell или cmd)
.\gradlew.bat assembleDebug

# Linux/macOS
./gradlew assembleDebug
```

Wrapper сам скачает Gradle 9.3.1 при первом запуске.

---

## 3. Конфигурация модуля `app`

`app/build.gradle`:

```groovy
android {
    namespace      "com.goddddd.notification"
    compileSdk     34
    defaultConfig {
        applicationId "com.goddddd.notification"
        minSdk     24
        targetSdk  34
        versionCode 4          // bump на каждом релизе
        versionName "1.0.2"    // semver
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
```

### Зависимости (полный список)

```groovy
// AndroidX
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.11.0'
implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
implementation 'androidx.lifecycle:lifecycle-process:2.7.0'

// Firebase (через BoM)
implementation platform('com.google.firebase:firebase-bom:32.7.0')
implementation 'com.google.firebase:firebase-messaging'
implementation 'com.google.firebase:firebase-database'

// Testing
testImplementation 'junit:junit:4.13.2'
androidTestImplementation 'androidx.test.ext:junit:1.1.5'
androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
```

При обновлении версий — обязательно проверять, что приложение всё
ещё собирается на JDK 17 + AGP 9.1.1 + Gradle 9.3.1.

---

## 4. Firebase

Проект использует **Firebase Realtime Database** + **FCM topic `group1`**.

- `app/google-services.json` лежит в репо (так удобнее коллабораторам,
  и это не критичный секрет — там только publishable ключи проекта).
- Database URL зашит в `RemoteUsers.DATABASE_URL`. Если форкнуть
  проект — нужно сменить и `google-services.json`, и эту константу.
- Правила Realtime DB на текущем проекте: `{ ".read": true, ".write": true }`
  (приватный набор пользователей, без Firebase Auth).

---

## 5. Релизная подпись (только для maintainer'а)

| Файл | В репо? | Что внутри |
|------|---------|-----------|
| `release.keystore` | **НЕТ** (в `.gitignore`) | Закрытый ключ, которым подписаны все APK с GitHub Releases. |
| `keystore.properties` | **НЕТ** (в `.gitignore`) | Пароли от keystore. |
| `app/build.gradle` ? `signingConfigs.release` | да | Логика, читающая `keystore.properties`. Если файла нет — `assembleRelease` соберёт unsigned APK (debuggable). |

**Соавтор НЕ должен** получать копию `release.keystore`. Подписание
релизов делает только maintainer:

- если он подпишет APK своим ключом, его сборку **нельзя будет
  установить поверх уже установленной у пользователей версии** —
  Android откажет (signature mismatch). Self-update сломается.

Соавтор может собирать только **debug** (без подписи) или
**unsigned release** для собственного тестирования. Финальные релизы
с тегом `vX.Y.Z` и публикацией на GitHub Releases — задача maintainer'а.

---

## 6. Быстрый чеклист для соавтора

```bash
# 1. Поставить JDK 17 (Temurin)
# 2. Поставить Android Studio (Hedgehog+) + Android SDK 34
# 3. Клонировать
git clone https://github.com/goddammit1/app.git
cd app/notifications

# 4. (обязательно) убрать чужой JDK-путь из репо-копии
git update-index --skip-worktree gradle.properties
# затем закомментировать строку org.gradle.java.home=... в gradle.properties

# 5. Открыть в Android Studio - оно само подтянет Gradle 9.3.1 через wrapper

# 6. Debug-сборка
./gradlew assembleDebug          # Linux/macOS
.\gradlew.bat assembleDebug      # Windows

# Готовый APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## 7. Что делать если...

| Симптом | Скорее всего |
|---------|--------------|
| `Could not target platform: 'Java SE 17'` | У тебя JDK не 17. Поставь Temurin 17. |
| `org.gradle.java.home: C:/Program Files/Java/jdk-17.0.3 does not exist` | Не убрал `org.gradle.java.home` из `gradle.properties` (см. п. 1). |
| `Could not resolve all artifacts for configuration ':classpath'` | Нет интернета или зеркало Maven Central лежит. Просто повторить. |
| `Unknown option ...` от AGP | У тебя Android Studio слишком старая, обнови до Hedgehog или новее. |
| `Failed to find Build Tools revision 34.0.0` | Android Studio ? SDK Manager ? SDK Platforms ? ? Android 14 (API 34), SDK Tools ? ? Android SDK Build-Tools 34. |