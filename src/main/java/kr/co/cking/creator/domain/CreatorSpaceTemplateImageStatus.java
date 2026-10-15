package kr.co.cking.creator.domain;

/**
 * 관리자 템플릿 이미지 업로드의 저장소 상태다(이슈 #598). Creator 이미지와 달리 "연결" 상태는 없다.
 * 템플릿·Space가 URL을 직접 저장하므로, 쓰이는지는 상태가 아니라 정리 때 URL 컬럼을 대조해 판단한다.
 *
 * <p>{@code DELETE_PENDING}은 정리 대상으로 선점했지만 아직 저장소 객체를 지우기로 확정하지 않은 상태다. 템플릿 저장이 이 이미지를
 * 쓰려 하면 {@code UPLOADED}로 되돌려 정리를 막는다. {@code DELETING}은 저장소 객체를 지우기로 확정한 상태로, 이후에는
 * 되돌릴 수 없고 이 이미지를 쓰는 템플릿 저장은 거절된다. 저장소 삭제는 DB 트랜잭션에 묶을 수 없어서, 두 상태 전이를
 * 같은 행에 대한 조건부 UPDATE로 직렬화해 "삭제 확정"과 "템플릿에서 쓰기 시작"이 동시에 성공하지 못하게 한다.
 *
 * <p>{@code DELETED}는 저장소 객체를 지운 뒤 남기는 기록이다. 행을 지우면 나중에 관리자가 옛 URL을 템플릿에 붙여 넣을 때
 * "추적한 적 없는 URL"과 구분할 수 없어, 이미 지워진 이미지를 가리키는 저장이 허용된다. 남겨 두어야 그 저장을 거절할 수 있다.
 */
public enum CreatorSpaceTemplateImageStatus {
    UPLOADING, UPLOADED, DELETE_PENDING, DELETING, DELETED
}
