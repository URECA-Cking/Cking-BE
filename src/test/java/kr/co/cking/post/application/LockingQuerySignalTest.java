package kr.co.cking.post.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

class LockingQuerySignalTest {

    private final LockingQuerySignal signal = new LockingQuerySignal();

    @AfterEach
    void tearDown() {
        LockingQuerySignal.clear();
    }

    @Test
    void 기다리는_테이블의_잠금_조회에서만_신호를_보낸다() {
        CountDownLatch postShare = LockingQuerySignal.expect("creator_post", "for share");

        signal.inspect("select cp1_0.post_id from creator_post cp1_0 where cp1_0.post_id=?");
        assertThat(postShare.getCount()).as("잠금 없는 일반 조회").isEqualTo(1);

        signal.inspect("select c1_0.comment_id from creator_post_comment c1_0 where c1_0.comment_id=? for share");
        assertThat(postShare.getCount()).as("이름이 겹치는 다른 테이블").isEqualTo(1);

        signal.inspect("select cp1_0.post_id from creator_post cp1_0 where cp1_0.post_id=? for share");
        assertThat(postShare.getCount()).isZero();
    }

    @Test
    void SQL을_바꾸지_않고_그대로_돌려준다() {
        String sql = "select 1 from creator_post p for share";

        assertThat(signal.inspect(sql)).isSameAs(sql);
    }
}
