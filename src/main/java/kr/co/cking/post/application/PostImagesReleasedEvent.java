package kr.co.cking.post.application;

import java.util.List;

/** 게시글 수정·삭제로 DELETE_PENDING이 된 이미지 key. Commit 후 저장소에서 지운다. */
public record PostImagesReleasedEvent(List<String> objectKeys) {

    public PostImagesReleasedEvent {
        objectKeys = List.copyOf(objectKeys);
    }
}
