# passport-paper

중앙 Passport 정책에 따른 Paper 입장 거부와 채팅·탭 목록 prefix를 구현한 첫 개발 버전입니다.

대상 API는 **Paper 26.2 build 129 stable**, Java 25입니다. 산출물: `build/libs/passport-paper-0.1.0-SNAPSHOT.jar`.

## 구현한 동작

- 비동기 pre-login 단계에서 서버 UUID 정책을 확인하고, 오류·미허가 결과는 입장 거부
- 로그인 직전과 입장 직후에도 유효 정책 확인
- 20초 간격 재조회, 1초 간격 만료 확인 후 종료. 활동 이벤트는 만료 즉시 거부
- 채팅과 탭 목록의 `[roleLabel] MinecraftName` 표시. 중앙의 실명·학번을 공개하지 않음
- Adventure plain text로 prefix를 만들고 HTML·MiniMessage·legacy 색상 명령으로 해석하지 않음
- 기존 LuckPerms 그룹·메타데이터를 변경하지 않음

머리 위 이름 및 LuckPerms 쓰기 연동은 아직 구현하지 않았습니다. 채팅 renderer나 탭 표시를 관리하는 다른 플러그인과 겹치면 아래 변수를 false로 설정합니다. 서버 플러그인 reload는 지원 운영 방식이 아니며 재시작으로 적용합니다.

## Paper 환경 변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `PASSPORT_SERVER_ID` | 없음, 필수 | 중앙 정책의 서버 ID |
| `PASSPORT_CHAT_PREFIX` | `true` | 채팅 renderer 설정 |
| `PASSPORT_TAB_PREFIX` | `true` | 탭 목록 이름 설정 |

설정 오류 시 플러그인은 계속 등록된 상태로 신규 입장을 거부하고 기존 접속자를 종료합니다. 플러그인 JAR 제거 또는 비활성화는 이 보호 장치를 제거하므로 운영 변경으로 취급해야 합니다.

## 빌드 및 검사

JDK 25가 필요합니다. 각 저장소를 독립적으로 clone한 뒤 실행합니다.

```sh
./gradlew --no-daemon clean build
```

Gradle 9.4.0 wrapper와 배포 ZIP SHA-256을 고정했습니다. 라이브러리 잠금 파일 및 검증 체크섬을 포함하며, 비공개 contracts 저장소 또는 옆 폴더를 빌드 중 읽지 않습니다. `core` 패키지는 draft 계약의 소비자 구현을 각 저장소가 소유합니다. 계약 변경 시 두 소비자 테스트를 함께 갱신합니다.

## 공통 환경 변수

| 변수 | 용도 |
|---|---|
| `PASSPORT_API_BASE_URL` | 중앙 API 주소. 기본값은 예시 주소이므로 운영값 필요 |
| `API_SERVICE_TOKEN` | 32자 이상의 서비스 Bearer 토큰. 저장소·플러그인 설정 파일에 넣지 않음 |
| `PASSPORT_ALLOW_INSECURE_HTTP` | 기본 false. 격리된 사설망 개발 HTTP에만 명시적으로 true |

HTTP 요청은 2초 제한, 동시 요청은 최대 32개입니다. 리다이렉트는 따라가지 않습니다. UUID·계약 버전·상태·서버 목록·정책 버전·최대 60초 lease를 검사합니다. 2초 이내의 시계 차이는 issuedAt 검사에만 허용하며 만료 시각을 연장하지 않습니다. 운영 서버 시간 동기화가 필요합니다.

UUID별 버전 기준은 로그아웃해도 프로세스 메모리에 남습니다. 낮은 버전, 같은 버전의 같거나 이전 issuedAt 응답은 버립니다. 프로세스 재시작 후에는 새 유효 API 응답이 오기 전까지 허가하지 않습니다.

## 현재 한계

- 실제 정품 계정의 웹·게임 양쪽 확인 전체 흐름은 아직 실기기 검증 전입니다.
- 학교 인증·회원 명부의 실제 연결은 API 저장소의 제공자 준비 상태에 따릅니다. 개발 fixture 검증은 학교 본인 인증을 증명하지 않습니다.
- SSE 정책 무효화는 아직 구현하지 않았습니다. 회수 인지는 아래 주기적 조회 간격과 최대 lease에 따르며, 5초 회수 목표를 달성했다고 간주하지 않습니다.
- 플러그인은 방화벽·프록시 forwarding 설정을 대신하지 않습니다. Paper 직접 접속 차단과 현대식 forwarding은 운영 배치의 필수 조건입니다.
- 서비스별 토큰 분리·회전은 운영 구성 단계의 후속 작업입니다. 현재 API와 공유된 서비스 토큰을 사용합니다.
