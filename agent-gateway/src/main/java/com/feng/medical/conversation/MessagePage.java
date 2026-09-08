package com.feng.medical.conversation;

import java.util.List;

public record MessagePage(List<ChatMessage> items, String nextCursor) {
}
