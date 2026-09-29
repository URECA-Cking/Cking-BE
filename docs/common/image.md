# 이미지 검증·정규화 공통 규칙

업로드 이미지는 도메인과 무관하게 `kr.co.cking.common.image.ImageNormalizer`로 검증·정규화한다. 저장·조회 경로는 공통 Object Storage port가 담당하며 이 문서의 범위가 아니다.

## 공통 규칙

모든 도메인에 같은 값이 적용된다.

| 항목 | 규칙 |
| --- | --- |
| 형식 | 실제 이미지 헤더 기준 JPEG·PNG만 허용한다. 파일명과 Content-Type은 신뢰하지 않는다. |
| 원본 크기 | 1 byte 이상 5MB 이하 |
| 픽셀 수 | 20MP 이하. 전체 디코딩 전에 헤더로 확인한다. |
| 방향 | EXIF Orientation(1~8)을 반영한다. 범위를 벗어난 값은 기본 방향으로 본다. |
| 축소 | 방향을 반영한 긴 변이 정책 최대값을 넘으면 비율을 유지해 축소한다. 업스케일하지 않는다. |
| 출력 | 흰 배경 RGB의 baseline JPEG(품질 0.90)로 다시 인코딩한다. 투명 영역은 흰색이 되고 EXIF 등 메타데이터는 남지 않는다. |
| Hash | 원본 bytes와 정규화 bytes의 SHA-256 lowercase hex를 함께 반환한다. |
| 버전 | 정규화 결과는 `JPEG_V1`이다. 출력 bytes가 달라지는 변경은 버전을 올린다. 정규화 hash로 중복을 판별하는 도메인이 있기 때문이다. |

## 도메인별 정책

`ImagePolicy(minWidth, minHeight, maxLongEdge)`로 도메인이 정한다.

| 도메인 | 최소 해상도 | 긴 변 최대 | 잘못된 이미지 오류 |
| --- | --- | --- | --- |
| YouTube 구독 인증 | 480×480 | 2048px | `INVALID_VERIFICATION_IMAGE` |
| Creator 게시글 (예정) | 200×200 | 2048px | 게시글 도메인 ErrorCode |

## 오류

- 형식·크기·해상도 위반과 손상된 이미지는 공통 `InvalidImageException`으로 던진다. 업무 오류 코드는 도메인마다 다르므로 호출한 도메인이 자기 `ErrorCode`로 변환한다.
- 입력은 유효하지만 정규화 중 서버 오류가 나면 `SYSTEM_ERROR`로 처리한다.
