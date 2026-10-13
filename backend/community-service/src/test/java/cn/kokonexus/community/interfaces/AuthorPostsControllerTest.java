package cn.kokonexus.community.interfaces;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.kokonexus.common.api.GlobalExceptionHandler;
import cn.kokonexus.community.application.CreatorPostApplicationService;
import cn.kokonexus.community.domain.CreatorPost;
import cn.kokonexus.community.infrastructure.AssetBindingValidator;
import cn.kokonexus.community.infrastructure.persistence.CreatorPostMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 实际MVC映射/DTO投影，持久层为夹具；只读SQL另以真实MySQL验收。 */
class AuthorPostsControllerTest {

    @Test
    void anonymousReadIsBoundedAndNeverSerializesFullEntityOrTrustedIdentityHeader() throws Exception {
        var mapper = mock(CreatorPostMapper.class);
        var first = row(30);
        var extra = row(29);
        when(mapper.selectPublishedByAuthor(12, null, null, 2)).thenReturn(List.of(first, extra));
        var mvc = MockMvcBuilders.standaloneSetup(
            new CreatorPostController(
                new CreatorPostApplicationService(mapper, mock(AssetBindingValidator.class), "/api/assets/images")
            )
        )
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        mvc.perform(get("/api/discovery/posts/by-author/12").param("size", "1").header("X-Koko-User-Id", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ownerId").value("12"))
            .andExpect(jsonPath("$.timeZone").value("UTC"))
            .andExpect(jsonPath("$.items[0].id").value("30"))
            .andExpect(jsonPath("$.items[0].body").doesNotExist())
            .andExpect(jsonPath("$.items[0].status").doesNotExist())
            .andExpect(jsonPath("$.items[0].version").doesNotExist())
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.nextCursor").isString());
        mvc.perform(get("/api/discovery/posts/by-author/12").param("size", "31"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/discovery/posts/by-author/12").param("after", "invalid!")).andExpect(
            status().isBadRequest()
        );
        verify(mapper).selectPublishedByAuthor(12, null, null, 2);
        verifyNoMoreInteractions(mapper);
    }

    private CreatorPost row(long id) {
        var value = new CreatorPost();
        value.setId(id);
        value.setOwnerId(12L);
        value.setSlug("post-" + id);
        value.setTitle("测试作品");
        value.setBody("不应在摘要接口返回的正文");
        value.setStatus("PUBLISHED");
        value.setVersion(2L);
        value.setKind("ARTICLE");
        value.setLikeCount(0L);
        value.setCommentCount(0L);
        value.setFavoriteCount(0L);
        value.setPublishedAt(LocalDateTime.of(2026, 10, 13, 22, 0, 0));
        return value;
    }
}
