# passport-paper

중앙 Passport 정책에 따른 Paper 입장 제어, 실명 표시, 플러그인용 최소 신원 API, 플레이 기록 수집을 제공합니다.

대상 API는 **Paper 26.2 build 129 stable**, Java 25입니다. 산출물: `build/libs/passport-paper-0.1.0-SNAPSHOT.jar`.

## 구현한 동작

- 비동기 pre-login 단계에서 서버 UUID 정책을 확인하고, 오류·미허가 결과는 입장 거부
- 로그인 직전과 입장 직후에도 유효 정책 확인
- 20초 간격 재조회, 1초 간격 만료 확인 후 종료. 활동 이벤트는 만료 즉시 거부
- 게임 정보 제공 동의 후 채팅·탭 목록에 `[roleLabel] MinecraftName (실명)` 표시. 전체 학번은 전송하지 않음
- Adventure plain text로 prefix를 만들고 HTML·MiniMessage·legacy 색상 명령으로 해석하지 않음
- 기존 LuckPerms 그룹·메타데이터를 변경하지 않음

머리 위 이름에는 Passport가 소유한 scoreboard team의 suffix로 실명을 표시합니다. 다른 플러그인의 기존 팀은 덮어쓰지 않아 그 경우 머리 위 실명은 생략되며 채팅·탭은 유지됩니다. LuckPerms 쓰기는 하지 않습니다. 채팅 renderer나 탭 표시를 관리하는 다른 플러그인과 겹치면 아래 변수를 false로 설정합니다. 서버 플러그인 reload는 지원 운영 방식이 아니며 재시작으로 적용합니다.

이름·역할 표시는 흰색/회색을 사용합니다. 채팅 본문은 기존 Component의 색·장식·클릭 정보를 그대로 이어 붙이며 이름 색을 본문에 상속시키지 않습니다.

## 서버 등록과 상태 보고

시작 시 한 번, 이후 30초마다 서비스 Bearer 인증으로 `POST /v1/minecraft/servers/heartbeat`를 비동기 전송합니다. 진행 중 요청은 하나만 유지하며 2초 HTTP 제한을 적용합니다. Paper는 `PASSPORT_SERVER_ID`와 `PASSPORT_SERVER_LABEL`을 전송합니다. 표시명 기본값은 서버 ID입니다. IP·포트·플레이어 정보는 보내지 않습니다.

본문은 `{source: "paper", servers: [{id, label}]}`이고 서버는 최대 64개, ID는 소문자로 시작하는 1–64자의 영문 소문자·숫자·밑줄·하이픈입니다. 표시명은 공백 제거 후 1–80자이며 제어 문자를 허용하지 않습니다. 성공 응답의 `{received, registered}` 수치는 권한 결정에 사용하지 않습니다.

새 서버는 중앙에서 비활성 상태로 발견됩니다. 관리자가 활성화와 접근 대상을 정하기 전에는 입장이 허용되지 않습니다. heartbeat는 기존 표시명·활성화·접근 설정을 덮어쓰지 않으며, 관리자 화면의 온라인 상태는 Paper heartbeat를 기준으로 계산합니다. 등록 실패는 기존 정책을 취소하거나 lease를 연장하지 않습니다. 연속 실패는 첫 오류와 복구 시점에만 고정 문구로 기록하며 HTTP 오류 본문을 로그에 남기지 않습니다.

## 정책 변경 알림

서비스 Bearer 인증으로 `/v1/minecraft/events`를 2초마다 조회합니다. 처음이나 `reset:true` 응답에서는 온라인 UUID 전체의 정책을 다시 받고, 이후에는 변경된 온라인 UUID만 조회합니다. 동일 UUID의 이벤트는 가장 높은 정책 버전 하나로 합칩니다. 이벤트 내용 자체로 접속 권한을 부여하지 않습니다.

이벤트 batch는 하나만 처리하며, 필요한 정책 조회와 적용이 모두 성공한 뒤에만 cursor를 전진시킵니다. API 오류·낮은 버전 응답·부분 실패에서는 다음 poll에 재시도합니다. 일반 정기 조회와 명령·입장 검사도 UUID별 진행 중 요청을 공유합니다. 정책 조회의 동시 실행은 8개로 제한하고 나머지는 대기시켜 큰 batch가 HTTP 요청 한도를 소진하지 않도록 합니다.

정상 전달 시 **2초 poll + 최대 2초 정책 조회, 약 5초 내 권한 회수 반영**을 목표로 합니다. 접속자 수·HTTP 대기열·서버 tick·대기실 연결 지연에 따라 늘어날 수 있으며, 실제 Minecraft 클라이언트에서 측정한 보장 수치는 아닙니다. 이벤트 API 장애 시에도 기존 정기 조회와 60초 이하 lease 만료 차단은 유지됩니다. [복구 절차](docs/outbox-recovery.md)를 참고하세요.

## Paper 환경 변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `PASSPORT_SERVER_ID` | 없음, 필수 | 중앙 정책의 서버 ID |
| `PASSPORT_SERVER_LABEL` | 서버 ID | 신규 발견 시 표시명. 기존 중앙 설정은 변경하지 않음 |
| `PASSPORT_CHAT_PREFIX` | `true` | 채팅 renderer 설정 |
| `PASSPORT_TAB_PREFIX` | `true` | 탭 목록 이름 설정 |
| `PASSPORT_NAME_TAG` | `true` | Passport 소유 팀에 머리 위 실명 표시 |

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

UUID별 버전 기준은 로그아웃해도 프로세스 메모리에 남습니다. 낮은 버전 또는 같은 버전의 이전 issuedAt 응답은 버립니다. 내용과 issuedAt이 완전히 같은 응답은 현재 lease를 확인하는 데만 사용하며 만료를 연장하지 않습니다. 프로세스 재시작 후에는 새 유효 API 응답이 오기 전까지 허가하지 않습니다.

## 현재 한계

- 기존 수동 웹·게임 양쪽 확인 후 로비 입장은 실제 정품 계정으로 확인했습니다. 새 자동 연결과 heartbeat 버전은 배포 후 실제 클라이언트로 재검증해야 합니다.
- 학교 인증·회원 명부의 실제 연결은 API 저장소의 제공자 준비 상태에 따릅니다. 개발 fixture 검증은 학교 본인 인증을 증명하지 않습니다.
- SSE 대신 DB outbox를 2초 간격으로 poll합니다. 약 5초 회수 목표의 실제 클라이언트 측정은 아직 남아 있습니다.
- 플러그인은 방화벽·프록시 forwarding 설정을 대신하지 않습니다. Paper 직접 접속 차단과 현대식 forwarding은 운영 배치의 필수 조건입니다.
- 서비스별 토큰 분리·회전은 운영 구성 단계의 후속 작업입니다. 현재 API와 공유된 서비스 토큰을 사용합니다.

## 신원 API와 PlaceholderAPI

게임 정보 제공 동의(.4 이상)와 유효한 서버 접근 정책을 가진 현재 접속자만 조회됩니다. 전체 학번·Discord ID·학교 토큰은 플러그인으로 보내지 않습니다. 응답은 최대 60초 정책 lease에 묶인 메모리 캐시이며 조회 시 HTTP를 호출하지 않습니다.

선택 설치한 PlaceholderAPI **2.12.3**에서 `%passport_real_name%`, `%passport_member%` (`true`/`false`), `%passport_admission_year%` (`26`/`25` 등)을 사용합니다. 미동의·미접속·만료 계정은 빈 값(회원 여부는 false)을 반환합니다. PAPI가 없어도 Passport는 정상 시작합니다.

다른 Paper 플러그인은 Passport JAR을 compileOnly로 참조하고 `depend: [Passport]`를 선언한 뒤 사용합니다.

```java
PassportIdentityService identities = Bukkit.getServicesManager().load(PassportIdentityService.class);
Optional<PassportIdentity> identity = identities.identity(player.getUniqueId());
List<UUID> matches = identities.resolveOnline("실명 또는 IGN");
// 동명이인은 matches.size() != 1 이므로 사용자가 IGN을 지정하도록 안내합니다.
```

타 플러그인의 명령 인수를 강제로 바꾸지 않습니다. 해당 플러그인이 `resolveOnline`을 사용해 실명 대상을 선택할 수 있습니다. 로그인 UUID와 실제 IGN은 변경하지 않습니다.

## 접속 상태와 플레이 기록

서버별 presence를 30초마다 보냅니다. `{serverId, observedAt, players:[uuid]}`이며 종료 시 빈 목록을 전송합니다. `telemetry.presenceEnabled=true` 및 해당 서버 접근권한을 받은 계정만 포함합니다. 접속 상태는 통계 수집 설정과 별개이므로 사용자나 서버가 통계를 꺼도 계속 표시할 수 있습니다. API의 90초 TTL이 지나면 오프라인으로 간주합니다. 통계 장애는 접속 권한을 허가하거나 취소하지 않습니다.

게임 정보 동의 후 `policy.telemetry.enabled=true`, 유효한 epoch와 `telemetry.serverIds`에 현재 서버 ID가 모두 있어야 기록합니다. 구 API처럼 서버 목록이 없으면 통계만 중단합니다. 서버 설정은 중앙 관리자에서, 본인 수집 여부·초기화는 웹에서 관리하며 로비의 초기 통계 설정은 OFF입니다. 재접속/설정 변경 때 로컬 임의 기본값으로 중앙 OFF를 덮어쓰지 않습니다.

**설치·동의 이후** 플레이 시간, 성공한 블록 파괴·설치, 최종 받은 피해, 사망, 몹 처치, 플레이어 처치, 대략적인 이동 거리(cm)를 수집합니다. 취소된 이벤트와 `canBuild=false` 설치는 제외합니다. 복수 블록 설치는 실제 블록 수를 세고, 플레이어 처치는 취소되지 않은 사망 이벤트의 다른 플레이어 killer만 한 번 기록하여 몹 처치와 구분합니다. 받은 피해는 방어구·흡수 등 이벤트 최종 보정 이후 피해량 × 1000을 반올림한 값입니다. 화면의 1 피해량은 반 칸 하트이며, 이 수치는 남은 체력을 넘는 마지막 타격 피해도 포함할 수 있습니다. limbo는 Paper가 아니므로 집계하지 않습니다.

플레이 시간은 기존 1초 메인 스레드 타이머에서 vanilla 플레이 tick 차이를 읽고 20tick당 1초로 변환합니다(AFK 포함). 서버 TPS가 낮으면 실제 시계보다 천천히 증가합니다. 처음 읽은 평생 기록은 가져오지 않고, 같은 접속 중 1초 미만의 나머지는 다음 샘플로 넘깁니다. 퇴장·정상 종료 시 마지막 차이를 반영하며 접속마다 남은 1초 미만은 버립니다.

거리는 기존 1초 메인 스레드 타이머에서 vanilla `*_ONE_CM` 누적값의 차이만 읽습니다. 걷기·달리기·웅크리기·수영·수면/수중 이동·등반·비행·겉날개·광산 수레·보트·돼지·말·스트라이더·행복한 가스트·노틸러스의 16개 이동 방식을 합산하고, 낙하 거리는 중복을 피하기 위해 제외합니다. 좌표나 `PlayerMoveEvent`를 저장하지 않습니다. 접속·수집 시작·epoch 변경 때 기준값을 새로 잡아 기존 평생 기록을 가져오지 않으며, 통계 감소는 해당 항목 초기화로 처리합니다. 퇴장과 정상 종료 직전 마지막 차이를 반영합니다. 위치 간 직선 거리가 아니므로 순간이동은 거리로 추가하지 않으며, 샘플 사이 권한 변경·비정상 종료 시 일부 거리가 누락될 수 있습니다.

`plugins/Passport/statistics.json`은 0600 영속 outbox입니다. 메인 스레드는 메모리 이벤트만 추가하고 별도 worker가 1초 간격으로 원자 저장·fsync 후 전송합니다. API 장애 시 한 개의 고정 batch와 후속 누적 카운터를 보관하며 재시작 후 같은 batch ID와 내용으로 재시도합니다. 정상 종료 시 잔여 이벤트를 저장합니다. 강제 종료·전원 차단은 아직 checkpoint하지 않은 이벤트(정상 상태 약 1초, 느린 HTTP가 진행 중이면 추가 지연)를 잃을 수 있습니다. 영속 파일을 삭제하거나 다른 서버에 복사하지 마세요. 손상 파일은 덮어쓰지 않고 수집만 중단합니다.

연결 해제·삭제·재연결·수집 설정 변경·초기화 시 API가 telemetry epoch를 바꾸므로 예전 queue가 재전송돼도 이전 기록을 되살리지 않습니다. Paper는 OFF/epoch 변경 때 이동·플레이 시간의 기준값을 초기화합니다. 서버 설정 변경도 계정 epoch를 회전하므로 다른 서버의 아직 전송되지 않은 일부 기록이 함께 폐기될 수 있습니다. 이전 pending batch는 수정하지 않고 그대로 전송하며 중앙에서 오래된 epoch를 무시한 뒤 확인 응답을 보냅니다. 통계 API는 batch ID와 payload를 영속적으로 중복 검사합니다. 중앙 수신·PostgreSQL 저장·개인/관리자 화면은 API 및 웹 배포가 함께 필요합니다.

## 관리자 위치 이동

Velocity의 `/passport tp` 요청을 `passport:teleport` 채널로 받습니다. 채널 이름만으로 신뢰하지 않으며 서비스 비밀키 HMAC, 요청 ID, actor·target UUID, 목적 서버, 15초 만료를 검증합니다. 요청을 전달한 실제 플레이어가 actor여야 하고 같은 nonce는 다시 실행하지 않습니다.

두 계정의 중앙 정책을 새로 조회한 후 메인 스레드에서 현재 정책과 접속 상태를 다시 확인합니다. actor는 중앙 관리자이면서 목적 서버 접근권한이 있어야 하고 target도 목적 서버 접근권한이 있어야 합니다. 같은 Paper에서 `teleportAsync`가 실제로 성공한 경우에만 서명된 성공 응답을 반환합니다. 플러그인 종료·접속 종료·오래된 요청·정책 조회 장애는 이동을 허가하지 않습니다.

공식 API 참고: [Paper Player](https://jd.papermc.io/paper/26.2/org/bukkit/entity/Player.html), [이동 통계](https://jd.papermc.io/paper/26.2/org/bukkit/Statistic.html), [Scoreboard Team](https://jd.papermc.io/paper/26.2/org/bukkit/scoreboard/Team.html), [PlaceholderAPI 내부 expansion](https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion/).
