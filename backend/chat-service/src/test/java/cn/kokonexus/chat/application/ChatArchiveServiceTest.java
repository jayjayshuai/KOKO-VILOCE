package cn.kokonexus.chat.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.chat.domain.ChatMessage;
import cn.kokonexus.chat.domain.Conversation;
import cn.kokonexus.chat.domain.Member;
import cn.kokonexus.chat.persistence.ArchiveMapper;
import cn.kokonexus.chat.persistence.ConversationMapper;
import cn.kokonexus.chat.persistence.MemberMapper;
import cn.kokonexus.chat.persistence.MessageMapper;
import cn.kokonexus.common.api.ResourceNotFoundException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatArchiveServiceTest {

    private final String id = "11111111-1111-4111-8111-111111111111";
    private final String messageId = "22222222-2222-4222-8222-222222222222";
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final ArchiveMapper archive = mock(ArchiveMapper.class);
    private final ChatArchiveService service = new ChatArchiveService(conversations, members, messages, archive);

    @BeforeEach
    void eligible() {
        var conversation = new Conversation();
        conversation.setId(id);
        conversation.setStatus("ACTIVE");
        conversation.setLastSeq(2205L);
        when(conversations.lock(id)).thenReturn(conversation);
        var member = new Member();
        member.setJoinedSeq(0L);
        when(members.selectOne(any())).thenReturn(member);
    }

    @Test
    void patternEscapesLiteralCharactersAndRejectsInvalidQueries() {
        assertEquals("100=%=_==", ChatArchiveService.literalPattern(" 100%_= "));
        for (String value : new String[] { "", "x", "  ", "\0x", "x".repeat(65) }) {
            assertThrows(IllegalArgumentException.class, () -> service.search(42, id, value, null, 20));
        }
        verifyNoInteractions(archive);
    }

    @Test
    void emptySearchWindowStillReturnsEarlierCursor() {
        when(archive.search(id, 205, 2206, "needle", 21)).thenReturn(List.of());
        var page = service.search(42, id, "needle", null, 20);
        assertTrue(page.items().isEmpty());
        assertEquals(206L, page.nextBefore());
        verify(archive).search(id, 205, 2206, "needle", 21);
    }

    @Test
    void paginationKeepsExtraMatchForNextRequest() {
        var values = List.of(message(205), message(204), message(203));
        when(archive.search(id, 0, 206, "needle", 3)).thenReturn(values);
        var page = service.search(42, id, "needle", 206L, 2);
        assertEquals(2, page.items().size());
        assertEquals(204L, page.nextBefore());
    }

    @Test
    void missingMemberAndClosedConversationNeverReadArchive() {
        when(members.selectOne(any())).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.bookmarked(43, id, null, 20));
        assertThrows(ResourceNotFoundException.class, () -> service.search(43, id, "needle", null, 20));
        var closed = new Conversation();
        closed.setStatus("CLOSED");
        when(conversations.lock(id)).thenReturn(closed);
        assertThrows(ResourceNotFoundException.class, () -> service.clear(42, id));
        verifyNoInteractions(archive);
    }

    @Test
    void cannotSaveForeignMessageOrJoinedBoundary() {
        var message = message(1);
        message.setConversationId("33333333-3333-4333-8333-333333333333");
        when(messages.selectById(messageId)).thenReturn(message);
        assertThrows(ResourceNotFoundException.class, () -> service.save(42, id, messageId));
        message.setConversationId(id);
        var member = new Member();
        member.setJoinedSeq(1L);
        when(members.selectOne(any())).thenReturn(member);
        assertThrows(ResourceNotFoundException.class, () -> service.save(42, id, messageId));
        verifyNoInteractions(archive);
    }

    @Test
    void retryDoesNotConsumeQuotaOrInsertAgain() {
        when(messages.selectById(messageId)).thenReturn(message(1));
        when(archive.selectCount(any())).thenReturn(1L);
        service.save(42, id, messageId);
        verify(archive, never()).insert(any(cn.kokonexus.chat.domain.ChatBookmark.class));
        verify(archive).selectCount(any());
    }

    @Test
    void invalidCursorAndSizeNeverQueryArchive() {
        assertThrows(IllegalArgumentException.class, () -> service.bookmarked(42, id, 9999L, 20));
        assertThrows(IllegalArgumentException.class, () -> service.search(42, id, "needle", 0L, 20));
        assertThrows(IllegalArgumentException.class, () -> service.bookmarked(42, id, null, 51));
        verifyNoInteractions(archive);
    }

    private ChatMessage message(long seq) {
        var message = new ChatMessage();
        message.setId(messageId);
        message.setConversationId(id);
        message.setSeq(seq);
        return message;
    }
}
