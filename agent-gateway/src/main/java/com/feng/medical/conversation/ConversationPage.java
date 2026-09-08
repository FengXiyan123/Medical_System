package com.feng.medical.conversation;

import java.util.List;

public record ConversationPage(List<Conversation> items, String nextCursor) {
}
