# 작업 규칙

- 기본으로 `README.md`와 `docs/README.md`만 읽는다.
- 작업 범위에 관련된 문서·코드·마이그레이션만 추가로 읽는다.
- API 계약의 정본은 `docs/api-index.md`와 해당 도메인 API 문서다.
- DB 구조의 정본은 `src/main/resources/db/migration/`이다.
- 공통 오류는 `common`, 업무 오류는 각 도메인 `ErrorCode` enum에 둔다.
- 다른 도메인의 Entity·Repository로 상태를 직접 변경하지 않는다.
- Event 상태 변경은 `EventCommandService`만 사용한다.
- 문서는 원칙적으로 300줄 이하로 유지하고, 초과하면 관심사별로 분리한다.
- API·DB·상태 전이·외부 동작을 바꾸면 관련 정본 문서도 함께 갱신한다.
- Issue 생성, PR 생성, push는 사용자의 명시적 요청 없이는 하지 않는다.
- Issue·PR 생성 전 해당 템플릿과 필수 항목을 확인하고, 생성 후 제목·라벨·본문이 양식과 일치하는지 검증한다. 템플릿 적용이 불가하면 임의 본문으로 생성하지 말고 사용자에게 알린다.
- 커밋도 사용자의 명시적 요청이 있을 때만 수행한다.
- 커밋·브랜치·PR 형식은 조직 `CONTRIBUTING.md`를 따른다.
- 커밋 메시지는 한국어로 작성한다. 예: `feat: 이벤트 생성 기능 추가`
