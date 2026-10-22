package kr.co.cking.post.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostImageDeletionListenerTest {

    private final PostImageStorageCleaner storageCleaner = mock(PostImageStorageCleaner.class);

    @Test
    void 리스너는_한_이미지_삭제가_실패해도_예외를_전파하지_않고_나머지를_지운다() {
        PostImageDeletionListener listener = new PostImageDeletionListener(storageCleaner);
        willThrow(new IllegalStateException("storage down")).given(storageCleaner).delete("a");

        listener.onReleased(new PostImagesReleasedEvent(List.of("a", "b")));

        verify(storageCleaner).delete("b");
    }
}
