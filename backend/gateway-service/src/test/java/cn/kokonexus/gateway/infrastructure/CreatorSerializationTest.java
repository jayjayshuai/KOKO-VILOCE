package cn.kokonexus.gateway.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.kokonexus.api.identity.CreatorFollowState;
import cn.kokonexus.api.identity.CreatorPage;
import cn.kokonexus.api.identity.CreatorProfile;
import cn.kokonexus.api.identity.SaveCreatorProfileCommand;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization;
import org.junit.jupiter.api.Test;

/** 显式创作者契约在真实Hessian2中往返；拒绝未知载荷由既有STRICT负向测试同时保护。 */
class CreatorSerializationTest {

    @Test
    void creatorContractsRoundTripWithoutPackageWideTrust() throws Exception {
        var profile = new CreatorProfile("12", "page", "测试作者", "", "介绍", null, null, null, null, "ACTIVE", 3, 1);
        var command = new SaveCreatorProfileCommand("page", "测试作者", "", "介绍", null, null, null, null, 3);
        var page = new CreatorPage(List.of(profile), 1, 12, 1);
        var follow = new CreatorFollowState("12", 1, true);
        var codec = new Hessian2Serialization();
        var url = URL.valueOf("tri://127.0.0.1:1/isolated-creator-codec");
        for (var value : List.of(command, profile, page, follow)) {
            var bytes = new ByteArrayOutputStream();
            var output = codec.serialize(url, bytes);
            output.writeObject(value);
            output.flushBuffer();
            var input = codec.deserialize(url, new ByteArrayInputStream(bytes.toByteArray()));
            assertThat(input.readObject(value.getClass())).isEqualTo(value);
        }
    }
}
