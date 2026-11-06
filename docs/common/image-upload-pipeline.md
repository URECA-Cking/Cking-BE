# 이미지 업로드 파이프라인

업로드 5곳(회원 프로필, Creator Space 프로필·배너, Event 배너, Creator 게시글 이미지)은 원본을 앱이 받지 않고 브라우저가 S3에 직접 올린 뒤, 앱 밖의 정규화 Lambda가 처리한다(#780). YouTube 구독 인증 업로드는 정규화 결과 hash로 중복을 판별하므로 기존 방식([이미지 검증·정규화 공통 규칙](image.md))을 그대로 쓴다.

## 켜는 조건

`cking.image-pipeline.results-queue-url`(`IMAGE_PIPELINE_RESULTS_QUEUE_URL`)이 있을 때만 예약·상태 API, 결과 소비자, 정리 스케줄러가 뜬다. 값이 없는 환경은 `/api/uploads`가 404이고, FE는 용도별 기존 업로드 API로 올린다.

## 흐름

| 단계 | 주체 | 내용 |
| --- | --- | --- |
| 1 | 브라우저 → 앱 | `POST /api/uploads`(`purpose`, `contentType`, `size`). 용도별 권한 확인과 업로드 기록 예약 뒤 업로드를 `PENDING`으로 저장하고 `raw/<최종 키>`에 대한 사전 서명 PUT(5분 유효)을 준다. 서명에 Content-Type, Content-Length, 최소 크기 메타데이터(`min-width`, `min-height`)가 들어가 다른 형식·크기는 S3가 거부한다 |
| 2 | 브라우저 → S3 | 응답의 `uploadUrl`에 `headers`를 붙여 원본을 PUT한다 |
| 3 | S3 → 작업 큐 → Lambda | `raw/` 생성 이벤트로 정규화 Lambda(Cking-Infra `image-normalizer/`)가 검사·회전·축소·JPEG 변환 뒤 최종 키에 저장한다. 규칙은 [공통 규칙](image.md)과 같다. 3번 실패하면 작업 DLQ로 간다 |
| 4 | Lambda → 결과 큐 → 앱 | 결과(`READY` 또는 `FAILED`와 사유)를 앱 서버마다 하나인 결과 소비자가 긴 폴링(20초)으로 읽어 업로드 상태를 바꾸고 용도별 업로드 기록을 완료로 표시한다. 반영에 실패한 메시지는 다시 보이고 5번 실패하면 결과 DLQ로 간다 |
| 5 | 브라우저 → 앱 | `GET /api/uploads/{uploadId}`로 상태를 확인한다. `READY`의 `imageKey`는 기존 API(프로필 수정, 게시글 작성 등)에 그대로 쓴다 |

## 정확성

- 결과는 업로드가 `PENDING`일 때만 반영한다. 큐는 최소 1회 전달이라 같은 결과가 다시 와도 상태가 바뀌지 않는다
- 최종 키는 예약 때 정해, Lambda가 같은 원본을 다시 처리해도 같은 키에 덮어쓴다
- 원본이 올라오지 않은 `PENDING`은 1시간 뒤 `FAILED`(`expired`)로 끝낸다. 주소 유효 5분과 Lambda 재시도 최대 18분(보이지 않는 시간 6분 × 3번)보다 길게 잡았다
- 업로드 기록은 원본(`raw/`) 수명 주기와 같은 1일 뒤 지운다(`ImageUploadCleanupScheduler`, 기본 10분마다)

## 오류

| 상황 | 결과 |
| --- | --- |
| 용도·형식이 비었거나 알 수 없는 용도, 크기가 0 이하 | `VALIDATION_FAILED`(400) |
| JPEG·PNG가 아니거나 5MB 초과 | `INVALID_UPLOAD_REQUEST`(400) |
| 용도별 권한 없음 | 기존 용도별 업로드 API와 같은 오류(예약 단계에서 확인) |
| 본인 업로드가 아니거나 없음 | `UPLOAD_NOT_FOUND`(404) |
| 정규화 실패 | 상태 `FAILED`, `failureReason`에 사유 |

## 브라우저 상태 확인

3초까지 0.5초, 6초까지 1초, 그 뒤 2초 간격으로 확인하고 30초가 넘으면 처리 지연으로 안내한다(Cking-FE `apps/web/src/api/imageUploadFlow.js`).

## 관련 문서

- 부하 테스트: [이미지 업로드](../operations/loadtest.md#이미지-업로드)
- 인프라·경보: Cking-Infra `envs/dev/image_pipeline.tf`, `envs/prod/image_pipeline.tf`, 런북 `docs/runbooks/image-pipeline-dlq.md`
