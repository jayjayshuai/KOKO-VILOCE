package cn.kokonexus.community.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.community.domain.CreatorPost;
import cn.kokonexus.community.infrastructure.AssetBindingValidator;
import cn.kokonexus.community.infrastructure.persistence.CreatorPostMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CreatorPostApplicationServiceTest {

    @Test
    void fullReferenceLookupProtectsPrivateReferencesWithoutGrantingPublicRead() {
        CreatorPostMapper mapper = mock(CreatorPostMapper.class);
        String id = "0fc78935-9839-4721-92f5-7aaf13f00877";
        when(mapper.hasCoverReference(id)).thenReturn(true);
        assertThat(service(mapper).hasCoverReference(id)).isTrue();
        assertThat(service(mapper).isPublishedCover(id)).isFalse();
        verify(mapper).hasCoverReference(id);
    }

    @Test
    void fullReferenceLookupRejectsEmptyOrInvalidAssetId() {
        CreatorPostMapper mapper = mock(CreatorPostMapper.class);
        for (String id : new String[] { null, "", " ", "invalid", "0FC78935-9839-4721-92F5-7AAF13F00877" }) {
            assertThatThrownBy(() -> service(mapper).hasCoverReference(id)).isInstanceOf(
                IllegalArgumentException.class
            );
        }
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void createBuildsNormalizedDraft() {
        CreatorPostMapper mapper = mock(CreatorPostMapper.class);
        when(mapper.insert(any(CreatorPost.class))).thenAnswer(invocation -> {
            CreatorPost post = invocation.getArgument(0);
            post.setId(3001L);
            return 1;
        });

        CreatorPost created = service(mapper).create(
            1001L,
            " My-First-Post ",
            " 第一篇内容 ",
            " 摘要 ",
            " 正文 ",
            "",
            null
        );

        assertThat(created.getSlug()).isEqualTo("my-first-post");
        assertThat(created.getTitle()).isEqualTo("第一篇内容");
        assertThat(created.getStatus()).isEqualTo("DRAFT");
        assertThat(created.getVersion()).isZero();
        assertThat(created.getKind()).isEqualTo("ARTICLE");
    }

    @Test
    void updateUsesOwnerAndOptimisticVersion() {
        CreatorPostMapper mapper = mock(CreatorPostMapper.class);
        CreatorPost updated = post(3001L, 1001L, 5L, "PUBLISHED");
        updated.setTitle("新标题");
        when(mapper.updateOwned(3001L, 1001L, 4L, "new-post", "新标题", "摘要", "正文", null, null)).thenReturn(1);
        when(mapper.selectById(3001L)).thenReturn(updated);

        CreatorPost result = service(mapper).update(1001L, 3001L, 4L, "new-post", " 新标题 ", "摘要", "正文", "", null);

        assertThat(result.getVersion()).isEqualTo(5L);
        verify(mapper).updateOwned(3001L, 1001L, 4L, "new-post", "新标题", "摘要", "正文", null, null);
    }

    @Test
    void publishTransitionsDraftAndReturnsNewVersion() {
        CreatorPostMapper mapper = mock(CreatorPostMapper.class);
        CreatorPost published = post(3001L, 1001L, 1L, "PUBLISHED");
        when(mapper.publishOwned(3001L, 1001L, 0L)).thenReturn(1);
        when(mapper.selectById(3001L)).thenReturn(published);

        CreatorPost result = service(mapper).publish(1001L, 3001L, 0L);

        assertThat(result.getStatus()).isEqualTo("PUBLISHED");
        assertThat(result.getVersion()).isEqualTo(1L);
    }

    @Test
    void mutationRejectsNonOwner() {
        CreatorPostMapper mapper = mock(CreatorPostMapper.class);
        when(mapper.archiveOwned(3001L, 9999L, 1L)).thenReturn(0);
        when(mapper.selectById(3001L)).thenReturn(post(3001L, 1001L, 1L, "PUBLISHED"));

        assertThatThrownBy(() -> service(mapper).archive(9999L, 3001L, 1L))
            .isInstanceOf(ForbiddenOperationException.class)
            .hasMessage("只有内容所有者可以执行此操作");
    }

    @Test
    void mutationRejectsStaleVersion() {
        CreatorPostMapper mapper = mock(CreatorPostMapper.class);
        when(mapper.archiveOwned(3001L, 1001L, 0L)).thenReturn(0);
        when(mapper.selectById(3001L)).thenReturn(post(3001L, 1001L, 1L, "PUBLISHED"));

        assertThatThrownBy(() -> service(mapper).archive(1001L, 3001L, 0L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("内容已被其他操作更新，请刷新后重试");
    }

    @Test
    void coverUrlRejectsNewExternalLocation() {
        CreatorPostMapper mapper = mock(CreatorPostMapper.class);

        assertThatThrownBy(() ->
            service(mapper).create(1001L, "unsafe-post", "标题", "摘要", "正文", "javascript:alert(1)", null)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("受管图片");
    }

    @Test
    void managedCoverRequiresOwnerAndCorrectPurpose() {
        CreatorPostMapper mapper = mock(CreatorPostMapper.class);
        AssetBindingValidator validator = mock(AssetBindingValidator.class);
        when(mapper.insert(any(CreatorPost.class))).thenAnswer(invocation -> {
            CreatorPost post = invocation.getArgument(0);
            post.setId(3001L);
            return 1;
        });
        String assetId = "74de67b7-80b0-4c18-bcba-e0c060229674";

        CreatorPost created = new CreatorPostApplicationService(mapper, validator, "/api/assets/images").create(
            1001L,
            "first-post",
            "标题",
            "",
            "正文",
            "",
            assetId
        );

        verify(validator).assertOwnedReady(1001L, assetId, "POST_COVER");
        assertThat(created.getCoverAssetId()).isEqualTo(assetId);
        assertThat(created.getCoverUrl()).isEqualTo("/api/assets/images/" + assetId + "/content");
    }

    private CreatorPostApplicationService service(CreatorPostMapper mapper) {
        return new CreatorPostApplicationService(mapper, mock(AssetBindingValidator.class), "/api/assets/images");
    }

    private CreatorPost post(long id, long ownerId, long version, String status) {
        CreatorPost post = new CreatorPost();
        post.setId(id);
        post.setOwnerId(ownerId);
        post.setVersion(version);
        post.setStatus(status);
        return post;
    }
}
