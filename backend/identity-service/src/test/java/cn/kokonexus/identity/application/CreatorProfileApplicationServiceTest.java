package cn.kokonexus.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.identity.CreatorProfileConflictException;
import cn.kokonexus.api.identity.SaveCreatorProfileCommand;
import cn.kokonexus.identity.domain.CreatorProfileEntity;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.AssetBindingValidator;
import cn.kokonexus.identity.infrastructure.persistence.CreatorProfileMapper;
import cn.kokonexus.identity.infrastructure.persistence.UserMapper;
import java.io.Serializable;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreatorProfileApplicationServiceTest {

    private final CreatorProfileMapper creatorProfileMapper = mock(CreatorProfileMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AssetBindingValidator assetBindingValidator = mock(AssetBindingValidator.class);
    private final CreatorProfileApplicationService service = new CreatorProfileApplicationService(
        creatorProfileMapper,
        userMapper,
        assetBindingValidator,
        "/api/assets/images"
    );

    @Test
    void fullReferenceLookupIsSeparateFromPublicAuthorization() {
        String id = "0fc78935-9839-4721-92f5-7aaf13f00877";
        when(creatorProfileMapper.hasAssetReference(id)).thenReturn(true);
        assertThat(service.hasAssetReference(id)).isTrue();
        assertThat(service.isPublishedAsset(id)).isFalse();
        verify(creatorProfileMapper).hasAssetReference(id);
    }

    @Test
    void fullReferenceLookupRejectsEmptyOrInvalidAssetId() {
        for (String id : new String[] { null, "", " ", "invalid", "0FC78935-9839-4721-92F5-7AAF13F00877" }) {
            assertThatThrownBy(() -> service.hasAssetReference(id)).isInstanceOf(IllegalArgumentException.class);
        }
        org.mockito.Mockito.verifyNoInteractions(creatorProfileMapper);
    }

    @Test
    void creatorRpcCommandUsesDubboStrictSerializationContract() {
        assertThat(command(0)).isInstanceOf(Serializable.class);
    }

    @Test
    void createsDraftForActiveOwner() {
        when(userMapper.selectById(1001L)).thenReturn(activeUser());
        when(creatorProfileMapper.insert(any(CreatorProfileEntity.class))).thenReturn(1);

        var profile = service.save("1001", command(0));

        assertThat(profile.slug()).isEqualTo("creator-one");
        assertThat(profile.status()).isEqualTo("DRAFT");
        assertThat(profile.version()).isZero();
    }

    @Test
    void rejectsStaleUpdate() {
        when(userMapper.selectById(1001L)).thenReturn(activeUser());
        when(creatorProfileMapper.selectById(1001L)).thenReturn(profile("DRAFT", 2));
        when(creatorProfileMapper.updateOwnedProfile(any(CreatorProfileEntity.class), any(Long.class))).thenReturn(0);

        assertThatThrownBy(() -> service.save("1001", command(1)))
            .isInstanceOf(CreatorProfileConflictException.class)
            .hasMessageContaining("刷新");
    }

    @Test
    void publishesDraftWithOptimisticVersion() {
        when(userMapper.selectById(1001L)).thenReturn(activeUser());
        when(creatorProfileMapper.selectById(1001L)).thenReturn(profile("DRAFT", 2), profile("ACTIVE", 3));
        when(creatorProfileMapper.publishOwnedProfile(1001L, 2)).thenReturn(1);

        var published = service.publish("1001", 2);

        assertThat(published.status()).isEqualTo("ACTIVE");
        assertThat(published.version()).isEqualTo(3);
    }

    @Test
    void capsPublicDiscoveryLimit() {
        when(creatorProfileMapper.selectPublished(50)).thenReturn(List.of(profile("ACTIVE", 1)));

        assertThat(service.listPublished(500)).hasSize(1);
        verify(creatorProfileMapper).selectPublished(50);
    }

    @Test
    void managedAvatarRequiresOwnerAndPurposeBeforeSaving() {
        String assetId = "0fc78935-9839-4721-92f5-7aaf13f00877";
        when(userMapper.selectById(1001L)).thenReturn(activeUser());
        when(creatorProfileMapper.insert(any(CreatorProfileEntity.class))).thenReturn(1);
        SaveCreatorProfileCommand command = new SaveCreatorProfileCommand(
            "creator-one",
            "创作者一号",
            "",
            "",
            "",
            "",
            assetId,
            null,
            0
        );

        var profile = service.save("1001", command);

        verify(assetBindingValidator).assertOwnedReady(1001L, assetId, "AVATAR");
        assertThat(profile.avatarAssetId()).isEqualTo(assetId);
        assertThat(profile.avatarUrl()).isEqualTo("/api/assets/images/" + assetId + "/content");
    }

    @Test
    void rejectsNewExternalAvatarAddress() {
        when(userMapper.selectById(1001L)).thenReturn(activeUser());
        SaveCreatorProfileCommand command = new SaveCreatorProfileCommand(
            "creator-one",
            "创作者一号",
            "",
            "",
            "https://outside.example/avatar.png",
            "",
            null,
            null,
            0
        );
        assertThatThrownBy(() -> service.save("1001", command))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("受管图片");
    }

    private SaveCreatorProfileCommand command(long version) {
        return new SaveCreatorProfileCommand(
            " Creator-One ",
            "创作者一号",
            "实时内容创作者",
            "专注社区、语音和直播。",
            "",
            "",
            null,
            null,
            version
        );
    }

    private UserAccount activeUser() {
        UserAccount user = new UserAccount();
        user.setId(1001L);
        user.setStatus("ACTIVE");
        return user;
    }

    private CreatorProfileEntity profile(String status, long version) {
        CreatorProfileEntity profile = new CreatorProfileEntity();
        profile.setUserId(1001L);
        profile.setSlug("creator-one");
        profile.setDisplayName("创作者一号");
        profile.setHeadline("实时内容创作者");
        profile.setBio("专注社区、语音和直播。");
        profile.setStatus(status);
        profile.setVersion(version);
        return profile;
    }
}
