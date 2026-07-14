# Root Recovery Helper

리디페이퍼 4(Android 10)에서 부팅 후 LSPosed Magisk 모듈의 비활성화 상태를 확인하고, Magisk와 LSPosed Manager를 통한 수동 복구 순서를 안내하는 Android 앱입니다.

## 요구 환경

- Android Studio의 JDK 17 이상
- Android SDK Platform 36 및 Build Tools 36.0.0
- Android 10(API 29) 리디페이퍼 4
- Magisk 및 LSPosed 설치
- 최초 실행 때 허용한 Magisk 슈퍼유저 권한

앱의 `minSdk`와 `targetSdk`는 모두 29이며, 빌드용 `compileSdk`만 36을 사용합니다.

## 빌드

Android Studio에서 프로젝트를 연 뒤 `app`을 빌드하거나 다음 명령을 실행합니다.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

네트워크 없이 현재 macOS SDK에 포함된 AAPT2를 사용하려면 다음처럼 실행합니다.

```sh
./gradlew --offline \
  -Pandroid.aapt2FromMavenOverride="$HOME/Library/Android/sdk/build-tools/36.0.0/aapt2" \
  :app:assembleDebug
```

생성되는 APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

단위 테스트:

```sh
./gradlew :app:testDebugUnitTest
```

## 설치 및 최초 설정

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 설치 후 `Root Recovery Helper`를 사용자가 직접 한 번 실행합니다.
2. Magisk 슈퍼유저 요청을 허용합니다. 부팅 후에도 사용할 수 있도록 영구 허용해야 합니다.
3. `설정이 완료되었습니다.` 안내를 확인합니다.

앱을 한 번도 직접 실행하지 않은 상태에서는 Android의 stopped 상태 제한으로 인해 `BOOT_COMPLETED`를 받지 못할 수 있습니다.

## 동작

- 현재는 임시 동작으로 부팅 직후에는 10초 예약만 수행하고, 약 10초 뒤 ADB를 활성화한 다음 LSPosed `disable` 파일과 후속 설정 상태를 확인해 기존 복구 안내 Dialog를 표시합니다.
- 현재는 임시 테스트 모드로, 수동 실행 시에는 즉시 ADB를 활성화하고 부팅 시에는 약 10초 뒤 ADB를 활성화합니다. 같은 시점에 LSPosed 모듈 디렉토리에 `disable` 파일이 있을 때만 `RootRecoveryActivity` 실행을 요청합니다.
- ADB 활성화와 LSPosed 상태 확인은 하나의 root 셸 명령으로 묶어 연속 권한 요청을 줄입니다.
- 약 10초 후 `/data/adb/modules/zygisk_lsposed/disable`을 확인합니다.
- 파일이 있으면 ADB 활성화를 시도하고 Magisk 복구 안내를 표시합니다.
- 모듈을 재활성화한 다음 부팅에서 파일이 사라졌으면 약 10초 뒤 LSPosed Manager 후속 안내를 표시합니다.
- LSPosed Manager 버튼은 `am start -c org.lsposed.manager.LAUNCH_MANAGER com.android.shell/.BugreportWarningActivity` 명령으로 실행합니다.
- 정상 상태에서는 복구 Dialog 없이 일반 메인 화면을 유지합니다.
- Magisk나 LSPosed Manager를 찾지 못하면 오류 메시지만 표시하며 앱은 종료되지 않습니다.

Android 10은 백그라운드 Activity 시작을 제한할 수 있으므로 부팅 후 약 10초 뒤 Dialog Activity 표시 여부는 대상 리디페이퍼 4 ROM에서 실기로 확인해야 합니다.

## 부팅 진단

재부팅 후 앱이 자동으로 표시되지 않으면 앱을 수동으로 열고 `마지막 부팅 진단`을 확인합니다.

- `BOOT_COMPLETED 수신 기록이 없습니다` 또는 표시된 시각이 이번 부팅보다 이전이면 Receiver가 실행되지 않은 것입니다. 설치 후 앱을 한 번 직접 실행했는지, 앱이 강제 종료 상태인지 확인합니다.
- `복구 안내 실행: startActivity 요청 전달됨`인데 Dialog가 자동으로 뜨지 않았다면 Android가 백그라운드 Activity 실행을 조용히 차단했을 가능성이 큽니다.
- `복구 안내 실행: 요청 실패`이면 함께 표시되는 예외 내용을 확인합니다.
- `복구 안내 실행: 실행하지 않음`이면 해당 부팅 시점에 LSPosed `disable` 파일이 없어 조건을 충족하지 않은 것입니다.
- `상태 검사`에는 루트 확인, LSPosed 파일 확인, ADB 10초 지연 예약·실행 결과 및 복구 Dialog 요청 중 마지막으로 처리된 단계가 표시됩니다.
- `ADB 10초 지연 실행 예약됨` 단계에서는 아직 명령을 실행하지 않은 상태입니다. 부팅 후 약 10초가 지나면 `지연된 ADB 실행 결과`로 갱신됩니다.

부팅 Receiver가 남긴 Logcat 기록은 다음 명령으로 확인할 수 있습니다.

```sh
adb logcat -d -s RootRecoveryBoot:I '*:S'
```

핵심 로그 태그는 `RootRecoveryBoot`입니다. `RootRecoveryActivity startActivity request returned normally` 로그는 Android 시스템이 실행 요청을 받았다는 뜻이며 실제 Dialog 표시 성공을 보장하지는 않습니다.

## 실기 테스트

아래 명령은 테스트 대상 기기의 LSPosed 모듈 상태를 직접 바꿉니다. 리디페이퍼 4 테스트 기기에서만 실행하고 각 단계의 조건을 확인한 뒤 진행하세요.

### A. 최초 실행

1. 앱을 설치하고 직접 실행합니다.
2. Magisk 슈퍼유저 요청을 승인합니다.
3. 준비 완료 메시지를 확인합니다.

### B. LSPosed 비활성화 감지

```sh
adb shell su -c 'touch /data/adb/modules/zygisk_lsposed/disable'
adb reboot
```

다음을 확인합니다.

- `루팅 해제가 감지되었습니다!` 안내 표시
- `Magisk로 이동` 버튼 동작
- `settings get global adb_enabled` 결과가 `1`
- `getprop init.svc.adbd` 결과가 `running`

### C. LSPosed 재활성화 후 안내

Magisk에서 모듈을 활성화하거나 테스트 파일을 제거한 뒤 재부팅합니다.

```sh
adb shell su -c 'rm -f /data/adb/modules/zygisk_lsposed/disable'
adb reboot
```

다음을 확인합니다.

- `추가 설정이 필요합니다` 안내 표시
- `LSPosed Manager로 이동` 버튼 동작
- 그다음 정상 재부팅에서는 후속 안내가 다시 표시되지 않음

### D. 정상 상태

`disable` 파일이 없고 이전 복구 대기 상태도 없는 조건에서 재부팅합니다. 일반 메인 화면은 표시되지만 어떤 복구 Dialog Activity도 표시되지 않아야 합니다.

추가로 Magisk 또는 LSPosed Manager가 없는 조건에서 각 버튼을 눌러 지정된 오류 메시지가 표시되고 앱이 크래시하지 않는지 확인합니다.
