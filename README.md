# Passport Paper

보호된 Paper 서버에서 회원 접근 상태를 재확인하고 통합 prefix와 표시 이름을 제공하는 플러그인입니다.

**현재 상태: 개발 준비 문서만 작성했습니다.** Java 소스, Gradle 설정, 플러그인 메타데이터와 JAR은 아직 없습니다.

## 역할

- 해당 서버에 대한 중앙 접근 정책을 확인하고 미허용 사용자의 활동을 제한합니다.
- Velocity의 접속 제어와 함께 백엔드 서버에서도 접근 제한을 적용합니다.
- 중앙에서 정한 표시 정책을 LuckPerms와 연동하고 Paper 채팅 renderer에 반영합니다.
- prefix와 표시 이름을 적용하되 다른 플러그인과 충돌하지 않도록 명시적인 소유 범위를 둡니다.
- 정지·권한 회수·정책 장애 상태를 처리하고 Velocity의 대기 서버 이동과 연계합니다.

첫 버전에는 인증용 책이나 인벤토리 GUI를 만들지 않습니다. 학교 인증 진입과 최종 확인은 NanoLimbo 대기 상태에서 Velocity와 웹이 담당합니다.

## 예정 스택과 지원 기준

- Java 25 + Gradle Kotlin DSL
- Paper 26.2 build 129를 초기 호환성 검증 대상으로 사용
- Adventure, LuckPerms API, Paper 채팅 renderer
- `passport-contracts`의 버전이 고정된 API 계약·생성 클라이언트 배포본

위 조합은 향후 빌드·실행 검증 대상입니다. 표시용 prefix로 접근 권한을 판단하지 않으며, 임의의 Minecraft OP 권한을 부여하지 않습니다.

## 다음 구현 순서

1. 접근 판정·표시 프로필 계약을 확정합니다.
2. 플러그인 부트스트랩과 비동기 API 클라이언트를 만듭니다.
3. 보호 서버의 초기 접근 차단과 정책 회수를 구현합니다.
4. LuckPerms의 관리 범위를 정하고 prefix·채팅 표시를 구현합니다.
5. 직접 접속 차단, API 장애, 재접속과 다른 표시 플러그인의 충돌을 검증합니다.

세부 범위는 [구현 계획](docs/implementation-plan.md), 소스 경계는 [src 안내](src/README.md)를 참고하세요.

## 관련 저장소

개인 계정의 관련 저장소입니다.

- [passport-api](https://github.com/underconnor/passport-api): 접근 정책과 표시 프로필
- [passport-contracts](https://github.com/underconnor/passport-contracts): API 계약과 배포 산출물
- [passport-velocity](https://github.com/underconnor/passport-velocity): 최초 접속과 모든 서버 이동 제어
- [passport-admin](https://github.com/underconnor/passport-admin): 권한·정지 관리

각 저장소는 독립적으로 빌드·배포합니다. 다른 저장소의 `../src`를 직접 참조하지 않습니다.
