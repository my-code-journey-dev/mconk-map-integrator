# MConK 지도 통합 프로토타입 (초기 소스)

## 이 프로젝트가 하는 일
- DeX에 맞춘 가로형 화면
- 네이버 지도 / 카카오맵 / 티맵 탭 전환
- 각 서비스의 공개 웹 지도 페이지를 앱 내부 WebView에서 열어보는 프로토타입

## 중요한 제한
1. 이 버전은 **프로토타입**이며, 세 서비스의 완전한 내비게이션 SDK 통합본이 아닙니다.
2. 지도 사이트가 WebView 표시를 차단하거나 앱 전용 로그인/인증/정책을 요구하면 화면이 제대로 나오지 않을 수 있습니다.
3. 티맵의 전체 경로 안내 기능이 웹 페이지 안에서 동작한다고 보장할 수 없습니다.
4. MCon K / HUD Q / HUD Q MAX 통신 기능은 구현하지 않았습니다. 기존 MCon K 앱을 삭제하거나 수정하지 마세요.
5. 실제 운전 중 조작하지 말고, 정차 상태에서 테스트하세요.

## 프로젝트 파일
- `settings.gradle`
- `build.gradle`
- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/mconkmap/MainActivity.kt`

## 빌드 방법 개요
이 프로젝트는 Android Gradle Plugin 8.6.1 및 JDK 17을 사용하도록 설정했습니다.
PC 없이 빌드하려면 Android Gradle 빌드를 지원하는 클라우드 IDE/빌드 서비스가 필요합니다. 단순한 코드 편집 웹사이트만으로는 APK가 생성되지 않습니다.
빌드 서비스에서 프로젝트를 열고 `assembleDebug` 작업을 실행하면 일반적으로 `app/build/outputs/apk/debug/app-debug.apk`가 생성됩니다.

## 다음 개발 단계
1. 우선 앱 화면 및 WebView 전환 동작 확인
2. 지도 서비스별 정책과 WebView 지원 여부 확인
3. 가능하면 각 서비스의 공식 Android SDK로 지도 화면 교체
4. HUD 연동은 제조사에서 지원하는 공식 인터페이스/문서를 확인한 뒤 별도로 검토
