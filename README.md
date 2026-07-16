# Root Recovery Helper

리디페이퍼 4(Android 10)에서 부팅 후 필수 Magisk 모듈의 `disable` 파일을 사용자 동의하에 제거하고 LSPosed 후속 설정을 안내하는 Android 앱입니다.

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

- 이 브랜치는 [PRD_AUTO_REENABLE.md](PRD_AUTO_REENABLE.md)의 자동 필수 모듈 복구 동작을 구현합니다.
- 부팅 직후에는 10초 예약만 수행하고, 약 10초 뒤 하나의 root 셸 세션에서 ADB를 항상 활성화합니다.
- 같은 세션에서 `/data/adb/modules/zygisk_lsposed/disable`과 Magisk의 `zygisk` 설정을 함께 검사합니다.
- `disable` 파일이 있거나 Zygisk가 꺼져 있으면 `루팅 해제가 감지되었습니다!` Dialog를 표시합니다. 두 상태 중 하나라도 불명확하고 명시적인 비활성화가 확인되지 않으면 Dialog를 띄우지 않습니다.
- `루팅 복구`를 누르면 자동 삭제 화면으로 이동하고, `무시`를 누르면 모듈과 Zygisk를 수정하지 않고 종료합니다. ADB 활성화는 선택 전에 이미 실행됩니다.
- 자동 삭제 화면에서는 하나의 root 셸 세션으로 ADB를 활성화하고 고정 allowlist의 6개 모듈만 검사합니다.
- 각 모듈의 `disable` 파일이 있으면 해당 파일만 삭제합니다.
- 처리 화면에는 현재 모듈과 삭제 완료·파일 없음·디렉터리 없음·실패 결과가 순서대로 표시됩니다.
- 6개 모듈 처리 직후 `magisk --sqlite`로 Zygisk 설정을 활성화하고 재조회하여 적용 여부를 확인합니다. 특정 Magisk 버전 번호를 하드코딩하지 않습니다.
- 하나 이상의 파일을 삭제했거나 Zygisk를 새로 활성화했고, 모든 검증이 성공하면 `10초 후 재부팅` 카운트다운 뒤 기기를 재부팅합니다.
- 자동 복구 후 재부팅되면 약 10초 뒤 LSPosed 후속 설정 Dialog를 표시합니다.
- 삭제 대상이 없고 Zygisk도 이미 활성화되어 있으면 자동 재부팅하지 않고 화면을 닫습니다.
- 접근 거부, 확인 불가, 삭제 실패 또는 Zygisk 활성화 실패가 있으면 자동 재부팅하지 않고 오류를 표시합니다.
- 수동 실행 시에는 기존처럼 즉시 ADB 활성화와 LSPosed 상태 확인을 수행합니다.

Android 10은 백그라운드 Activity 시작을 제한할 수 있으므로 `zygisk_lsposed/disable`이 있는 상태에서 부팅 후 약 10초 뒤 복구 Dialog 표시 여부는 대상 리디페이퍼 4 ROM에서 실기로 확인해야 합니다.

## 부팅 진단

재부팅 후 앱이 자동으로 표시되지 않으면 앱을 수동으로 열고 `마지막 부팅 진단`을 확인합니다.

- `BOOT_COMPLETED 수신 기록이 없습니다` 또는 표시된 시각이 이번 부팅보다 이전이면 Receiver가 실행되지 않은 것입니다. 설치 후 앱을 한 번 직접 실행했는지, 앱이 강제 종료 상태인지 확인합니다.
- `자동 복구 실행: startActivity 요청 전달됨`인데 Dialog가 뜨지 않았다면 Android가 백그라운드 Activity 실행을 조용히 차단했을 가능성이 큽니다.
- `자동 복구 실행: 요청 실패`이면 함께 표시되는 예외 내용을 확인합니다.
- `상태 검사`에는 자동 복구 실행, 모듈별 삭제 결과, 재부팅 카운트다운 중 마지막 단계가 표시됩니다.
- `현재 부팅 ADB 상세 진단`에는 활성화 전후의 `adb_enabled`, `adbd`, USB 구성과 각 명령의 종료 코드·출력이 표시됩니다.
- `최근 부팅별 ADB 진단 이력`에는 최근 5회 부팅의 진단이 최신순으로 누적되며 다음 재부팅에서도 삭제되지 않습니다.
- `ADB 활성화 및 루팅 상태 확인 10초 지연 예약됨` 단계에서는 아직 root 명령을 실행하지 않은 상태입니다.

부팅 Receiver가 남긴 Logcat 기록은 다음 명령으로 확인할 수 있습니다.

```sh
adb logcat -d -s RootRecoveryBoot:I '*:S'
```

핵심 로그 태그는 `RootRecoveryBoot`입니다. `Recovery dialog startActivity request returned normally` 로그는 Android 시스템이 실행 요청을 받았다는 뜻이며 실제 화면 표시 성공을 보장하지는 않습니다.

ADB 활성화 진단만 확인하려면 `ADB_DIAG` 로그를 필터링합니다.

```sh
adb logcat -d -s RootRecoveryBoot:I '*:S' | grep ADB_DIAG
```

## 실기 테스트

아래 명령은 테스트 대상 기기의 LSPosed 모듈 상태를 직접 바꿉니다. 리디페이퍼 4 테스트 기기에서만 실행하고 각 단계의 조건을 확인한 뒤 진행하세요.

### A. 최초 실행

1. 앱을 설치하고 직접 실행합니다.
2. Magisk 슈퍼유저 요청을 승인합니다.
3. 준비 완료 메시지를 확인합니다.

### B. 허용 모듈 자동 재활성화

```sh
adb shell su -c 'touch /data/adb/modules/zygisk_lsposed/disable'
adb reboot
```

다음을 확인합니다.

- 약 10초 뒤 복구 Dialog 표시
- `루팅 복구`를 누르면 자동 삭제 화면으로 이동
- 자동 삭제 화면에 `zygisk_lsposed` 처리 상태 표시
- `disable 파일 삭제 완료` 결과 표시
- `Zygisk 활성화 및 설정 확인 완료` 또는 `Zygisk가 이미 활성화되어 있습니다.` 결과 표시
- 10초 카운트다운 뒤 자동 재부팅
- 재부팅 약 10초 뒤 LSPosed 설정 Dialog 표시
- 재부팅 후 `disable` 파일이 없음
- `settings get global adb_enabled` 결과가 `1`
- `getprop init.svc.adbd` 결과가 `running`
- `magisk --sqlite "SELECT value FROM settings WHERE key='zygisk';"` 결과가 `1` 또는 `value=1`

Zygisk만 끈 상태에서도 재부팅하여 약 10초 뒤 같은 복구 Dialog가 표시되고, `루팅 복구` 후 Zygisk 활성화와 자동 재부팅이 진행되는지 확인합니다.

### C. 목록 밖 모듈 보호

allowlist에 없는 테스트 모듈에 `disable` 파일을 만든 뒤 재부팅하고 파일이 유지되는지 확인합니다.

### D. 정상 상태

`zygisk_lsposed/disable` 파일이 없고 Zygisk가 활성화되어 있으며 LSPosed 후속 설정 대기 상태가 없는 조건에서 재부팅합니다. ADB는 활성화되지만 루팅 해제 Dialog와 자동 삭제 화면은 표시되지 않아야 합니다.

### E. 실패 상태

모듈 경로 접근 거부 또는 삭제 실패를 재현하고 상태가 `확인 불가` 또는 `삭제 실패`로 표시되며 자동 재부팅하지 않는지 확인합니다.
