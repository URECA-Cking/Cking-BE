package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.CreatorPostFields;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.domain.CreatorPostImage;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 MySQL에서 업로드 기록의 조건부 연결·해제와 정리 선점 경합을 확인한다. */
@SpringBootTest
class CreatorPostImageIntegrationTest {

    @Autowired
    private PostImageUploadService uploadService;
    @Autowired
    private CreatorPostService postService;
    @Autowired
    private CreatorPostImageRepository imageRepository;
    @Autowired
    private CreatorPostRepository postRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;

    private final List<Member> members = new ArrayList<>();
    private final List<Creator> creators = new ArrayList<>();
    private Member owner;
    private Creator creator;

    @BeforeEach
    void setUp() {
        owner = member();
        creator = creator(owner);
    }

    @AfterEach
    void cleanUp() {
        for (Creator created : creators) {
            postRepository.findAll().stream()
                    .filter(post -> post.getCreatorId().equals(created.getCreatorId()))
                    .forEach(post -> postService.delete(created.getMemberId(), post.getPostId()));
            imageRepository.findAll().stream()
                    .filter(image -> image.getCreatorId().equals(created.getCreatorId()))
                    .forEach(imageRepository::delete);
        }
        creatorRepository.deleteAll(creators);
        memberRepository.deleteAll(members);
    }

    @Test
    void 다른_Creator가_올린_이미지는_게시글에_연결할_수_없다() throws IOException {
        Member otherOwner = member();
        creator(otherOwner);
        String othersKey = uploadService.upload(otherOwner.getMemberId(), png());

        assertThatThrownBy(() -> postService.create(owner.getMemberId(),
                new CreatorPostFields("본문", PostVisibility.PUBLIC, List.of(othersKey))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.POST_IMAGE_UNAVAILABLE));
        assertThat(image(othersKey).getPostId()).isNull();
    }

    @Test
    void 같은_이미지로_동시에_게시글을_만들면_하나만_연결된다() throws Exception {
        String key = uploadService.upload(owner.getMemberId(), png());
        CreatorPostFields fields = new CreatorPostFields("본문", PostVisibility.PUBLIC, List.of(key));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        postService.create(owner.getMemberId(), fields);
                        return true;
                    } catch (BusinessException exception) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(postRepository.findAll().stream()
                .filter(post -> post.getCreatorId().equals(creator.getCreatorId()))).hasSize(1);
    }

    @Test
    void 정리_스케줄러가_선점한_이미지는_게시글에_연결할_수_없다() throws IOException {
        String key = uploadService.upload(owner.getMemberId(), png());
        imageRepository.claimStaleUnlinked(Instant.now().plusSeconds(60), Instant.now());

        assertThatThrownBy(() -> postService.create(owner.getMemberId(),
                new CreatorPostFields("본문", PostVisibility.PUBLIC, List.of(key))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.POST_IMAGE_UNAVAILABLE));
    }

    @Test
    void 게시글에_연결된_이미지는_정리_스케줄러가_선점하지_않는다() throws IOException {
        String key = uploadService.upload(owner.getMemberId(), png());
        CreatorPostView post = postService.create(owner.getMemberId(),
                new CreatorPostFields("본문", PostVisibility.PUBLIC, List.of(key)));

        imageRepository.claimStaleUnlinked(Instant.now().plusSeconds(60), Instant.now());

        CreatorPostImage linked = image(key);
        assertThat(linked.getPostId()).isEqualTo(post.postId());
        assertThat(linked.getDisplayOrder()).isZero();
    }

    private CreatorPostImage image(String objectKey) {
        return imageRepository.findAll().stream()
                .filter(image -> image.getObjectKey().equals(objectKey))
                .findFirst()
                .orElseThrow();
    }

    private Member member() {
        Member member = memberRepository.saveAndFlush(new Member("post-" + suffix(), null, null, MemberRole.USER));
        members.add(member);
        return member;
    }

    private Creator creator(Member member) {
        Creator created = creatorRepository.saveAndFlush(new Creator(member.getMemberId(), "creator-" + suffix()));
        creators.add(created);
        return created;
    }

    private byte[] png() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
