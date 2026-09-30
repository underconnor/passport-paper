# 예정 소스 경계

아직 Java 소스는 없습니다. 부트스트랩 시 다음 경계로 구성합니다.

| 모듈 | 책임 |
| --- | --- |
| `bootstrap` | 플러그인 수명 주기와 의존성 구성 |
| `config` | 서버 식별자, API 주소, 표시 모드 |
| `api` | 계약 기반 비동기 HTTP 클라이언트 |
| `access` | 보호 서버 접근 상태와 권한 회수 |
| `profile` | 중앙 표시 프로필 캐시와 갱신 |
| `luckperms` | Passport가 소유한 그룹·메타데이터 연동 |
| `chat` | Adventure 기반 Paper 채팅 renderer |
| `proxy` | 대기 서버 이동 요청과 실패 처리 |

공유 모델은 `passport-contracts`의 특정 버전 배포본을 사용합니다. 인접 저장소 소스 참조, DB 직접 접속, 학적 파서, 인증 GUI와 Discord OAuth는 포함하지 않습니다.
