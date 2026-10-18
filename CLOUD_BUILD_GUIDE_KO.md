# PC 없이 클라우드에서 APK 빌드하기

이 프로젝트에는 GitHub Actions 자동 빌드 설정(`.github/workflows/build-apk.yml`)이 포함되어 있습니다. 이 설정은 소스를 GitHub에 올린 뒤 클라우드에서 APK를 빌드하고 결과 파일을 보관합니다.

## 휴대폰에서 진행
1. GitHub 계정에 로그인합니다.
2. 새 저장소(repository)를 만듭니다. 예: `mconk-map-integrator`
3. 이 ZIP을 풀고 프로젝트 파일과 `.github/workflows/build-apk.yml`을 저장소에 업로드합니다. `.github` 폴더가 업로드에서 빠지지 않았는지 확인합니다.
4. 저장소의 **Actions** 탭을 엽니다.
5. **Build Android APK** 워크플로를 선택하고 **Run workflow**를 누릅니다. 첫 push 때 자동으로 실행될 수도 있습니다.
6. 실행이 끝나면 성공한 workflow 실행 화면 아래의 **Artifacts**에서 `MConK-Map-Integrator-debug-apk`를 다운로드합니다.
7. 내려받은 artifact ZIP 안의 `app-debug.apk`를 추출해 휴대폰에 설치합니다.

## 중요
- GitHub 웹 UI의 폴더/파일 업로드 기능은 모바일 화면에서 불편할 수 있습니다. `.github/workflows` 경로가 제대로 유지되는지 꼭 확인해야 합니다.
- 이 작업은 소스가 컴파일되는지 확인하는 빌드이지, 실제 휴대폰/DeX/HUD 시뮬레이션은 아닙니다.
- 앱은 프로토타입이며, 지도 사이트가 WebView에서 차단되거나 일부 기능이 제한될 수 있습니다.
- HUD Q/HUD Q MAX와의 통신은 구현되어 있지 않습니다. 기존 MCon K 앱을 유지하세요.
- 실제 운전 중 테스트하지 마세요.
