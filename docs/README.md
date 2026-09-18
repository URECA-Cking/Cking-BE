# 문서 인덱스

## 기본 문서

- [전체 API 인덱스](api-index.md): 외부 API와 내부 Service 호출을 찾는다.
- [공통 API 규약](common/api.md): 응답 형식, 사용자 식별, 멱등성, 오류 코드 배치 원칙과 공통 API를 확인한다.
- [통합 작업단위 관리표](management/work-items.csv): 작업 배정·진척을 확인한다.
- [통합 RTM](management/rtm.csv): 요구사항 추적·릴리스 점검에 사용한다.
- [DrawingEngine JMH 성능 테스트](performance/drawing-engine-jmh.md): 추첨 엔진의 처리량, p95 및 메모리 할당량 측정 방법을 확인한다.

## 정본

- DB 스키마: `src/main/resources/db/migration/`
- 공통 오류 코드: `src/main/java/kr/co/cking/common/exception/CommonErrorCode.java`
- 도메인 오류 코드: 각 도메인의 `*ErrorCode.java`
- API 목록: 이 문서의 [전체 API 인덱스](api-index.md)

## 도메인 문서

`domains/<도메인>/README.md`와 `domains/<도메인>/api.md`는 해당 도메인 작업자가 처음 필요해질 때 추가한다. 책임, 소유 데이터, 상태 전이, 불변조건, 외부 API 계약처럼 코드만으로 파악하기 어려운 정보를 적는다.

- [Snapshot](domains/snapshot/README.md): 공식 후보 확정, 멱등 생성, Snapshot Hash 정규화 계약
- [Drawing](domains/drawing/README.md): 추첨 엔진 알고리즘과 당첨 결과 영속성 경계
- [Drawing API](domains/drawing/api.md): 관리자 Drawing 요청·공개 API 계약
- [Notification](domains/notification/README.md): 인앱 알림 조회와 읽음 상태

빈 도메인 문서나 디렉터리는 미리 만들지 않는다. 문서는 원칙적으로 300줄 이하로 유지하며, 이를 넘으면 관심사별 파일로 분리한다.
