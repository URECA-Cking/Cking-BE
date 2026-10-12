package kr.co.cking.member.application;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class MemberProfileImageDeletionListenerTest {

    private final MemberProfileImageStorageCleaner storageCleaner = mock(MemberProfileImageStorageCleaner.class);

    @Test
    void commit_뒤_삭제가_실패해도_예외를_전파하지_않아_scheduler가_회수하게_한다() {
        MemberProfileImageDeletionListener listener = new MemberProfileImageDeletionListener(storageCleaner);
        willThrow(new IllegalStateException("storage down")).given(storageCleaner).delete("old-key");

        listener.deleteAfterCommit(new MemberProfileImageDeletionRequestedEvent("old-key"));

        verify(storageCleaner).delete("old-key");
    }
}
