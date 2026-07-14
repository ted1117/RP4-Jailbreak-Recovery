# RIDI Paper 4 Root Recovery Helper — 구현 명세

## 1. 문서 목적

이 문서는 Codex가 Kotlin 기반 Android 앱을 구현하기 위한 요구사항 명세다.

앱의 목적은 리디페이퍼 4가 부팅된 뒤 LSPosed Magisk 모듈의 비활성화 상태를 감지하고, 사용자가 Magisk와 LSPosed Manager를 통해 복구할 수 있도록 안내하는 것이다.

사용자에게는 내부 구현 용어 대신 이해하기 쉬운 표현인 **“루팅 해제”**를 사용한다.

---

## 2. 기술 조건

- 언어: Kotlin
- UI: Android View 기반 XML 레이아웃
- targetSdkVersion: 29
- minSdkVersion: 29
- 대상 OS: Android 10
- 앱 유형: 일반 Android APK
- 루트 명령 실행 방식: `su -c`
- 패키지명: 구현자가 적절히 지정
- 앱 이름: `Root Recovery Helper`

---

## 3. 핵심 사용자 흐름

### 3.1 최초 실행

1. 사용자가 앱을 설치하고 직접 실행한다.
2. 앱은 즉시 `su` 명령을 실행하여 Magisk 슈퍼유저 권한을 요청한다.
3. 루트 권한 획득 성공 여부를 확인한다.
4. 성공하면 앱이 정상적으로 준비되었다는 간단한 상태를 표시한다.
5. 실패하면 루트 권한이 필요하다는 오류 메시지를 표시한다.

루트 권한 확인 명령 예시:

```sh
su -c id
```

성공 조건:

```text
uid=0(root)
```

---

### 3.2 부팅 후 LSPosed Magisk 모듈 비활성화 감지

기기가 부팅을 완료하면 앱은 자동으로 상태를 점검한다.

확인할 파일:

```text
/data/adb/modules/zygisk_lsposed/disable
```

확인 명령 예시:

```sh
su -c 'test -f /data/adb/modules/zygisk_lsposed/disable'
```

종료 코드가 `0`이면 `disable` 파일이 존재하는 것으로 판단한다.

---

### 3.3 `disable` 파일이 존재하는 경우

다음과 같이 처리한다.

1. LSPosed Magisk 모듈이 비활성화된 것으로 판단한다.
2. 사용자에게는 이를 **루팅 해제 상태**로 표현한다.
3. ADB를 강제로 활성화한다.
4. 복구 안내 Dialog를 표시한다.
5. Dialog에 Magisk 앱으로 이동하는 버튼을 제공한다.
6. 다음 부팅에서 후속 안내를 표시할 수 있도록 상태를 저장한다.

ADB 활성화 명령:

```sh
su -c 'settings put global adb_enabled 1'
su -c 'setprop ctl.start adbd'
```

표시 문구:

```text
루팅 해제가 감지되었습니다!
```

보조 문구:

```text
Magisk에서 모듈을 다시 활성화한 후 기기를 재부팅해 주세요.
```

버튼:

```text
Magisk로 이동
```

Magisk 실행 대상 패키지:

```text
com.topjohnwu.magisk
```

앱 실행 방식:

```kotlin
packageManager.getLaunchIntentForPackage("com.topjohnwu.magisk")
```

실행 시 `Intent.FLAG_ACTIVITY_NEW_TASK`를 추가한다.

---

### 3.4 사용자의 복구 작업

사용자는 다음 순서로 직접 복구한다.

1. Dialog의 `Magisk로 이동` 버튼을 누른다.
2. Magisk에서 비활성화된 모듈을 다시 활성화한다.
3. 기기를 재부팅한다.

앱은 Magisk 내부 설정을 직접 변경하지 않는다.

---

### 3.5 다음 부팅 후 상태 확인

다음 부팅에서 앱은 다시 다음 파일을 확인한다.

```text
/data/adb/modules/zygisk_lsposed/disable
```

다음 조건을 모두 만족하면 LSPosed 후속 안내를 표시한다.

- 이전 부팅에서 `disable` 파일이 감지되었음
- 이번 부팅에서는 `disable` 파일이 존재하지 않음

이 상태를 저장하기 위해 `SharedPreferences`를 사용한다.

키 예시:

```text
pending_lsposed_setup
```

동작:

- `disable` 파일 감지 시 `true`
- 다음 부팅에서 `disable` 파일이 없고 값이 `true`이면 LSPosed 안내 표시

---

### 3.6 LSPosed Manager 안내

조건을 만족하면 다음 Dialog를 표시한다.

제목:

```text
추가 설정이 필요합니다
```

내용:

```text
LSPosed Manager에서 모듈을 활성화해 주세요.
```

버튼:

```text
LSPosed Manager로 이동
```

LSPosed Manager 패키지:

```text
org.lsposed.manager
```

앱 실행 방식:

```kotlin
packageManager.getLaunchIntentForPackage("org.lsposed.manager")
```

실행 시 `Intent.FLAG_ACTIVITY_NEW_TASK`를 추가한다.

Dialog를 표시한 뒤 `pending_lsposed_setup` 값을 `false`로 변경한다.

---

## 4. 부팅 감지

다음 브로드캐스트를 사용한다.

```text
android.intent.action.BOOT_COMPLETED
```

필수 권한:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

Receiver 등록 예시:

```xml
<receiver
    android:name=".boot.BootCompletedReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

부팅 직후 상태 검사는 별도 백그라운드 스레드 또는 Coroutine에서 실행한다.

메인 스레드에서 `su` 프로세스를 기다리지 않는다.

---

## 5. Dialog 표시 방식

부팅 Receiver에서 직접 일반 Dialog를 생성하지 않는다.

복구 Dialog 전용 Activity를 사용한다.

예시 Activity:

```text
RootRecoveryActivity
```

LSPosed 안내 Activity:

```text
LsposedSetupActivity
```

Activity는 Dialog처럼 보이도록 투명 또는 Dialog 테마를 사용한다.

예시 테마:

```xml
<style name="Theme.RootRecovery.Dialog" parent="Theme.MaterialComponents.DayNight.Dialog.Alert">
    <item name="android:windowNoTitle">true</item>
    <item name="android:windowCloseOnTouchOutside">false</item>
</style>
```

Activity 실행 시 다음 플래그를 사용한다.

```kotlin
Intent.FLAG_ACTIVITY_NEW_TASK
Intent.FLAG_ACTIVITY_CLEAR_TOP
```

---

## 6. 상태 판정

앱 상태는 다음 네 가지로 구분한다.

### `ROOT_UNAVAILABLE`

조건:

- `su` 실행 실패
- `su -c id` 결과가 root가 아님

동작:

- 루트 권한 필요 메시지 표시
- ADB 활성화 시도 안 함
- Magisk 모듈 검사 안 함

---

### `LSPOSED_DISABLED`

조건:

```text
/data/adb/modules/zygisk_lsposed/disable
```

파일이 존재함.

동작:

- ADB 강제 활성화
- `pending_lsposed_setup = true`
- `루팅 해제가 감지되었습니다!` Dialog 표시
- Magisk 이동 버튼 제공

---

### `LSPOSED_REENABLED_PENDING_SETUP`

조건:

- `disable` 파일 없음
- `pending_lsposed_setup == true`

동작:

- LSPosed Manager 안내 Dialog 표시
- LSPosed Manager 이동 버튼 제공
- `pending_lsposed_setup = false`

---

### `NORMAL`

조건:

- `disable` 파일 없음
- `pending_lsposed_setup == false`

동작:

- 아무것도 표시하지 않음

---

## 7. 권장 프로젝트 구조

```text
app/
└── src/main/java/<package>/
    ├── MainActivity.kt
    ├── boot/
    │   └── BootCompletedReceiver.kt
    ├── root/
    │   ├── RootCommandExecutor.kt
    │   └── RootStateChecker.kt
    ├── recovery/
    │   ├── RootRecoveryActivity.kt
    │   └── LsposedSetupActivity.kt
    ├── launcher/
    │   └── ExternalAppLauncher.kt
    └── storage/
        └── RecoveryPreferences.kt
```

---

## 8. 클래스별 책임

### `MainActivity`

- 앱 최초 실행 진입점
- 슈퍼유저 권한 요청
- 루트 권한 상태 표시
- 현재 LSPosed 모듈 상태 표시 가능

### `BootCompletedReceiver`

- `BOOT_COMPLETED` 수신
- Coroutine 또는 백그라운드 실행 시작
- 상태 점검 결과에 따라 복구 Activity 실행

### `RootCommandExecutor`

- `su -c` 명령 실행
- stdout, stderr, exit code 반환
- timeout 처리
- 프로세스 종료 처리

예시 반환 모델:

```kotlin
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)
```

### `RootStateChecker`

다음 기능 제공:

```kotlin
suspend fun hasRootAccess(): Boolean
suspend fun isLsposedDisabled(): Boolean
suspend fun enableAdb(): Boolean
```

### `RecoveryPreferences`

다음 값을 관리:

```kotlin
var pendingLsposedSetup: Boolean
```

### `ExternalAppLauncher`

다음 앱 실행:

```kotlin
fun openMagisk(context: Context): Boolean
fun openLsposedManager(context: Context): Boolean
```

---

## 9. 루트 명령 실행 요구사항

`Runtime.exec()` 또는 `ProcessBuilder`를 사용한다.

권장 방식:

```kotlin
ProcessBuilder("su", "-c", command)
```

다음 요구사항을 만족해야 한다.

- 메인 스레드에서 실행하지 않음
- stdout 전체 읽기
- stderr 전체 읽기
- exit code 확인
- 일정 시간 이상 응답이 없으면 프로세스 종료
- 예외 발생 시 앱이 종료되지 않도록 처리

명령 문자열:

```kotlin
const val LSPOSED_DISABLE_PATH =
    "/data/adb/modules/zygisk_lsposed/disable"
```

비활성화 확인:

```sh
test -f /data/adb/modules/zygisk_lsposed/disable
```

ADB 활성화:

```sh
settings put global adb_enabled 1
setprop ctl.start adbd
```

---

## 10. 앱 실행 실패 처리

Magisk 또는 LSPosed Manager 앱을 찾지 못하면 앱이 종료되면 안 된다.

표시 메시지:

Magisk 실행 실패:

```text
Magisk 앱을 찾을 수 없습니다.
```

LSPosed Manager 실행 실패:

```text
LSPosed Manager 앱을 찾을 수 없습니다.
```

---

## 11. Manifest 요구사항

필수 항목:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

등록 대상:

- `MainActivity`
- `RootRecoveryActivity`
- `LsposedSetupActivity`
- `BootCompletedReceiver`

Dialog Activity는 외부 앱에서 실행할 필요가 없으므로:

```xml
android:exported="false"
```

`BootCompletedReceiver`는 `BOOT_COMPLETED` 수신을 위해:

```xml
android:exported="true"
```

---

## 12. 앱 최초 실행 제한

Android에서는 사용자가 설치 후 앱을 한 번도 실행하지 않은 상태라면 부팅 브로드캐스트 동작이 제한될 수 있다.

따라서 사용자가 설치 직후 반드시 앱을 한 번 직접 실행해야 한다.

최초 실행 화면에 다음 안내를 표시한다.

```text
설정이 완료되었습니다.
이 앱은 기기 부팅 후 루팅 상태를 자동으로 확인합니다.
```

---

## 13. 완료 조건

다음 조건을 모두 만족하면 구현 완료로 판단한다.

1. 앱 최초 실행 시 Magisk 슈퍼유저 권한 요청이 표시된다.
2. 루트 권한 승인 후 `su -c id`가 성공한다.
3. 부팅 완료 후 앱이 자동으로 실행되지 않고 백그라운드에서 상태를 확인한다.
4. LSPosed 모듈 디렉터리에 `disable` 파일이 있으면 ADB가 활성화된다.
5. 같은 상황에서 `루팅 해제가 감지되었습니다!` Dialog Activity가 표시된다.
6. `Magisk로 이동` 버튼으로 Magisk 앱이 열린다.
7. `pending_lsposed_setup` 값이 저장된다.
8. 모듈을 다시 활성화하고 재부팅한 뒤 `disable` 파일이 없으면 LSPosed 안내 Dialog가 표시된다.
9. `LSPosed Manager로 이동` 버튼으로 LSPosed Manager가 열린다.
10. 정상 상태에서는 부팅 후 아무 화면도 표시하지 않는다.
11. Magisk나 LSPosed Manager 앱이 없더라도 앱이 크래시하지 않는다.
12. 모든 루트 명령은 메인 스레드 밖에서 실행된다.

---

## 14. 테스트 절차

### 테스트 A: 최초 실행

1. 앱 설치
2. 앱 실행
3. Magisk 슈퍼유저 요청 확인
4. 승인
5. 정상 준비 메시지 확인

### 테스트 B: LSPosed 비활성화 상태

테스트용으로 다음 파일을 생성한다.

```sh
su -c 'touch /data/adb/modules/zygisk_lsposed/disable'
```

그다음 재부팅한다.

확인 사항:

- ADB 활성화
- `루팅 해제가 감지되었습니다!` 표시
- Magisk 이동 버튼 동작
- `pending_lsposed_setup = true`

### 테스트 C: LSPosed 재활성화 후

다음 파일을 삭제한다.

```sh
su -c 'rm -f /data/adb/modules/zygisk_lsposed/disable'
```

그다음 재부팅한다.

확인 사항:

- LSPosed Manager 안내 표시
- LSPosed Manager 이동 버튼 동작
- `pending_lsposed_setup = false`

### 테스트 D: 정상 상태

조건:

- `disable` 파일 없음
- `pending_lsposed_setup = false`

재부팅 후 아무 Dialog도 표시되지 않아야 한다.

---

## 15. Codex 구현 지시

아래 순서대로 구현한다.

1. Android 프로젝트 생성
2. Kotlin 및 targetSdk 29 설정
3. `RootCommandExecutor` 구현
4. 최초 실행 시 root 권한 요청 구현
5. `BootCompletedReceiver` 구현
6. LSPosed `disable` 파일 검사 구현
7. ADB 활성화 구현
8. `RootRecoveryActivity` 구현
9. Magisk 앱 실행 구현
10. `RecoveryPreferences` 구현
11. 다음 부팅에서 `LsposedSetupActivity` 표시 구현
12. LSPosed Manager 실행 구현
13. 예외 처리 추가
14. 전체 테스트
15. README에 빌드 및 테스트 방법 작성

기존 요구사항을 임의로 확장하지 않는다.
Jetpack Compose는 사용하지 않는다.
서버 통신, 네트워크 기능, 분석 SDK, 광고 SDK는 추가하지 않는다.
